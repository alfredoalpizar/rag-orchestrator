package com.alfredoalpizar.rag.service.orchestrator.strategy

import com.alfredoalpizar.rag.model.domain.Message
import com.alfredoalpizar.rag.model.domain.MessageRole
import com.alfredoalpizar.rag.service.orchestrator.strategy.impl.DeepSeekSingleStrategy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Integration tests for DeepSeek Single Strategy with REAL API calls.
 *
 * These tests verify that the strategy works correctly with actual DeepSeek models.
 * They use loose assertions (structure, not exact content) and save outputs for manual review.
 *
 * Prerequisites:
 * - Set DEEPSEEK_API_KEY environment variable
 * - Run with: ./gradlew test --tests "DeepSeekSingleStrategyIntegrationTest" -Dspring.profiles.active=integration-test
 *
 * Philosophy:
 * - Test strategy mechanics, not model intelligence
 * - Assert structure (has response, used tokens, completed) not exact strings
 * - Save all outputs to test-outputs/ for manual review
 * - Handle non-determinism gracefully (models may behave differently each time)
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
class DeepSeekSingleStrategyIntegrationTest {

    @Autowired
    lateinit var strategy: DeepSeekSingleStrategy

    private val outputDir = File("test-outputs").apply { mkdirs() }

    @Test
    fun `should complete simple question without tool calls`() = runBlocking {
        // Given - Simple math question, no tools available
        val messages = listOf(
            Message(role = MessageRole.USER, content = "What is 2+2? Just give the answer.")
        )
        val context = IterationContext(
            conversationId = "test-simple-math",
            iteration = 1,
            maxIterations = 5,
            streamingMode = StreamingMode.FINAL_ONLY
        )

        // When - Execute strategy with real API
        val events = strategy.executeIteration(messages, emptyList(), context).toList()

        // Then - Verify structure, not exact content
        println("\n${"=".repeat(60)}")
        println("TEST: Simple question without tool calls")
        println("=".repeat(60))
        printEvents(events)

        // Assert: Should have a final response
        val finalResponse = events.filterIsInstance<StrategyEvent.FinalResponse>()
        assertThat(finalResponse).hasSize(1)
        assertThat(finalResponse.first().content).isNotBlank()
        assertThat(finalResponse.first().tokensUsed).isGreaterThan(0)

        // Assert: Should complete without needing more iterations
        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>()
        assertThat(iterationComplete).hasSize(1)
        assertThat(iterationComplete.first().shouldContinue).isFalse()

        // Assert: Should NOT have tool calls
        val toolCalls = events.filterIsInstance<StrategyEvent.ToolCallsComplete>()
        assertThat(toolCalls).isEmpty()

        // Assert: Should NOT have streaming chunks (we're in FINAL_ONLY mode)
        val chunks = events.filterIsInstance<StrategyEvent.ContentChunk>()
        assertThat(chunks).isEmpty()

        // Save for manual review
        saveTestOutput("deepseek_simple_math", events, context)
    }

    @Test
    fun `should emit progressive chunks in PROGRESSIVE streaming mode`() = runBlocking {
        // Given - Enable streaming
        val messages = listOf(
            Message(role = MessageRole.USER, content = "Count from 1 to 5")
        )
        val context = IterationContext(
            conversationId = "test-streaming",
            iteration = 1,
            maxIterations = 1,
            streamingMode = StreamingMode.PROGRESSIVE // <-- Streaming enabled
        )

        // When
        val events = strategy.executeIteration(messages, emptyList(), context).toList()

        // Then
        println("\n${"=".repeat(60)}")
        println("TEST: Progressive streaming mode")
        println("=".repeat(60))
        printEvents(events)

        // Assert: Should have ContentChunk events
        val chunks = events.filterIsInstance<StrategyEvent.ContentChunk>()
        assertThat(chunks).isNotEmpty()

        // Assert: Chunks should come before IterationComplete
        val firstCompleteIndex = events.indexOfFirst { it is StrategyEvent.IterationComplete }
        val lastChunkIndex = events.indexOfLast { it is StrategyEvent.ContentChunk }
        assertThat(lastChunkIndex).isLessThan(firstCompleteIndex)

        // Assert: Should still have IterationComplete
        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>()
        assertThat(iterationComplete).hasSize(1)

        // Save for manual review
        saveTestOutput("deepseek_streaming_chunks", events, context)
    }

    @Test
    fun `should handle tool-calling scenario gracefully`() = runBlocking {
        // Given - Question that MIGHT trigger tool use (if model decides to)
        val messages = listOf(
            Message(
                role = MessageRole.USER,
                content = "What's the weather like in San Francisco?"
            )
        )

        // Provide a weather tool
        val tools = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "get_weather",
                    "description" to "Get current weather for a location",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "location" to mapOf(
                                "type" to "string",
                                "description" to "City name"
                            )
                        ),
                        "required" to listOf("location")
                    )
                )
            )
        )

        val context = IterationContext(
            conversationId = "test-tool-calling",
            iteration = 1,
            maxIterations = 5,
            streamingMode = StreamingMode.FINAL_ONLY
        )

        // When
        val events = strategy.executeIteration(messages, tools, context).toList()

        // Then
        println("\n${"=".repeat(60)}")
        println("TEST: Tool calling scenario")
        println("=".repeat(60)")
        printEvents(events)

        // NOTE: We can't assert the model WILL call the tool (non-deterministic)
        // We can only assert the strategy handles both outcomes correctly

        val toolCallEvents = events.filterIsInstance<StrategyEvent.ToolCallsComplete>()
        val finalResponseEvents = events.filterIsInstance<StrategyEvent.FinalResponse>()
        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>().first()

        if (toolCallEvents.isNotEmpty()) {
            // Model chose to call tool (expected behavior)
            println("✓ Model called tool")
            assertThat(toolCallEvents.first().toolCalls).isNotEmpty()
            assertThat(toolCallEvents.first().toolCalls.first().function.name).isEqualTo("get_weather")
            assertThat(iterationComplete.shouldContinue).isTrue() // Should continue for tool result
        } else if (finalResponseEvents.isNotEmpty()) {
            // Model chose NOT to call tool (also valid - it can refuse)
            println("ℹ️  Model did not call tool (answered directly)")
            assertThat(finalResponseEvents.first().content).isNotBlank()
            assertThat(iterationComplete.shouldContinue).isFalse() // No tool = done
        } else {
            // This would be a bug - must have either tool calls or final response
            throw AssertionError("Strategy must emit either ToolCallsComplete or FinalResponse")
        }

        // Assert: Should always complete iteration
        assertThat(iterationComplete.tokensUsed).isGreaterThan(0)

        // Save for manual review
        saveTestOutput("deepseek_tool_calling", events, context)
    }

    @Test
    fun `should handle complex reasoning question`() = runBlocking {
        // Given - Question requiring multi-step reasoning
        val messages = listOf(
            Message(
                role = MessageRole.USER,
                content = """
                    A farmer has 17 sheep. All but 9 die. How many sheep are left?
                    Think through this carefully and explain your reasoning.
                """.trimIndent()
            )
        )
        val context = IterationContext(
            conversationId = "test-reasoning",
            iteration = 1,
            maxIterations = 1,
            streamingMode = StreamingMode.FINAL_ONLY
        )

        // When
        val events = strategy.executeIteration(messages, emptyList(), context).toList()

        // Then
        println("\n${"=".repeat(60)}")
        println("TEST: Complex reasoning question")
        println("=".repeat(60))
        printEvents(events)

        // Assert: Should have final response
        val finalResponse = events.filterIsInstance<StrategyEvent.FinalResponse>().first()
        assertThat(finalResponse.content).isNotBlank()
        assertThat(finalResponse.content.length).isGreaterThan(20) // Should be substantive

        // Loose assertion: Response should mention the number 9 (correct answer)
        // Note: We're not asserting exact wording, just that it likely got it right
        val content = finalResponse.content.lowercase()
        val mentions9 = content.contains("9") || content.contains("nine")
        if (!mentions9) {
            println("⚠️  Warning: Response doesn't mention '9' - may have gotten it wrong")
            println("    Content: ${finalResponse.content}")
        }

        // Save for manual review
        saveTestOutput("deepseek_reasoning", events, context)
    }

    @Test
    fun `should track token usage across events`() = runBlocking {
        // Given
        val messages = listOf(
            Message(role = MessageRole.USER, content = "Hello!")
        )
        val context = IterationContext(
            conversationId = "test-tokens",
            iteration = 1,
            maxIterations = 1,
            streamingMode = StreamingMode.FINAL_ONLY
        )

        // When
        val events = strategy.executeIteration(messages, emptyList(), context).toList()

        // Then
        println("\n${"=".repeat(60)}")
        println("TEST: Token usage tracking")
        println("=".repeat(60)")
        printEvents(events)

        // Assert: FinalResponse should have token count
        val finalResponse = events.filterIsInstance<StrategyEvent.FinalResponse>().firstOrNull()
        if (finalResponse != null) {
            assertThat(finalResponse.tokensUsed).isGreaterThan(0)
        }

        // Assert: IterationComplete should have token count
        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>().first()
        assertThat(iterationComplete.tokensUsed).isGreaterThan(0)

        // Assert: Token counts should be reasonable (not negative, not absurdly high)
        assertThat(iterationComplete.tokensUsed).isLessThan(10000) // Sanity check

        // Save for manual review
        saveTestOutput("deepseek_token_tracking", events, context)
    }

    // ========================================
    // Helper Methods
    // ========================================

    private fun printEvents(events: List<StrategyEvent>) {
        events.forEachIndexed { index, event ->
            println("[$index] ${event::class.simpleName}")
            when (event) {
                is StrategyEvent.ContentChunk ->
                    println("    Content: ${event.content.take(50)}${if (event.content.length > 50) "..." else ""}")
                is StrategyEvent.FinalResponse -> {
                    println("    Content: ${event.content.take(100)}${if (event.content.length > 100) "..." else ""}")
                    println("    Tokens: ${event.tokensUsed}")
                }
                is StrategyEvent.ToolCallsComplete -> {
                    println("    Tool Calls: ${event.toolCalls.size}")
                    event.toolCalls.forEach { tc ->
                        println("      - ${tc.function.name}(${tc.function.arguments})")
                    }
                }
                is StrategyEvent.IterationComplete -> {
                    println("    Tokens: ${event.tokensUsed}")
                    println("    Should Continue: ${event.shouldContinue}")
                }
                else -> {}
            }
        }
        println("=".repeat(60))
    }

    private fun saveTestOutput(
        testName: String,
        events: List<StrategyEvent>,
        context: IterationContext
    ) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val filename = "${testName}_${timestamp}.txt"
        val file = File(outputDir, filename)

        file.writeText(buildString {
            appendLine("=" .repeat(70))
            appendLine("TEST OUTPUT: $testName")
            appendLine("=" .repeat(70))
            appendLine("Timestamp: $timestamp")
            appendLine("Conversation ID: ${context.conversationId}")
            appendLine("Iteration: ${context.iteration}/${context.maxIterations}")
            appendLine("Streaming Mode: ${context.streamingMode}")
            appendLine()
            appendLine("=" .repeat(70))
            appendLine("EVENTS (${events.size} total)")
            appendLine("=" .repeat(70))

            events.forEachIndexed { index, event ->
                appendLine()
                appendLine("[$index] ${event::class.simpleName}")
                appendLine("-".repeat(70))
                when (event) {
                    is StrategyEvent.ContentChunk -> {
                        appendLine("Content: ${event.content}")
                    }
                    is StrategyEvent.FinalResponse -> {
                        appendLine("Content: ${event.content}")
                        appendLine("Tokens Used: ${event.tokensUsed}")
                        if (event.metadata.isNotEmpty()) {
                            appendLine("Metadata: ${event.metadata}")
                        }
                    }
                    is StrategyEvent.ToolCallsComplete -> {
                        appendLine("Tool Calls: ${event.toolCalls.size}")
                        event.toolCalls.forEach { tc ->
                            appendLine("  - Function: ${tc.function.name}")
                            appendLine("    Arguments: ${tc.function.arguments}")
                            appendLine("    ID: ${tc.id}")
                        }
                        appendLine("Assistant Content: ${event.assistantContent ?: "(none)"}")
                    }
                    is StrategyEvent.IterationComplete -> {
                        appendLine("Tokens Used: ${event.tokensUsed}")
                        appendLine("Should Continue: ${event.shouldContinue}")
                        if (event.metadata.isNotEmpty()) {
                            appendLine("Metadata: ${event.metadata}")
                        }
                    }
                    is StrategyEvent.ReasoningChunk -> {
                        appendLine("Reasoning: ${event.content}")
                        if (event.metadata.isNotEmpty()) {
                            appendLine("Metadata: ${event.metadata}")
                        }
                    }
                    else -> {
                        appendLine("(No additional details)")
                    }
                }
            }

            appendLine()
            appendLine("=" .repeat(70))
            appendLine("END OF TEST OUTPUT")
            appendLine("=" .repeat(70))
        })

        println("📝 Test output saved: ${file.absolutePath}")
    }
}
