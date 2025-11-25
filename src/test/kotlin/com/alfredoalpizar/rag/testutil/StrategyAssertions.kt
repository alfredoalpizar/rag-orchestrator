package com.alfredoalpizar.rag.testutil

import com.alfredoalpizar.rag.service.orchestrator.strategy.StrategyEvent
import org.assertj.core.api.Assertions.assertThat

/**
 * Assertion helpers for testing agentic loop strategies.
 *
 * These provide loose, structural assertions that work with non-deterministic LLM outputs.
 * Focus on verifying strategy behavior, not exact model responses.
 *
 * Philosophy:
 * - ✅ Assert structure (has response, used tokens, emitted events)
 * - ✅ Assert sequences (chunks before completion, tools before iteration end)
 * - ✅ Assert bounds (tokens > 0, length > 10, duration < timeout)
 * - ❌ Don't assert exact strings
 * - ❌ Don't assert specific word counts or phrasing
 */
object StrategyAssertions {

    /**
     * Verify strategy completed successfully with proper IterationComplete event.
     */
    fun assertCompletedSuccessfully(events: List<StrategyEvent>) {
        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>()
        assertThat(iterationComplete)
            .withFailMessage("Strategy must emit exactly one IterationComplete event")
            .hasSize(1)
        assertThat(iterationComplete.first().tokensUsed)
            .withFailMessage("IterationComplete must report tokens used (got ${iterationComplete.first().tokensUsed})")
            .isGreaterThan(0)
    }

    /**
     * Verify strategy finalized (no more iterations needed).
     */
    fun assertFinalized(events: List<StrategyEvent>) {
        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>()
        assertThat(iterationComplete).hasSize(1)
        assertThat(iterationComplete.first().shouldContinue)
            .withFailMessage("Strategy should be finalized (shouldContinue = false)")
            .isFalse()
    }

    /**
     * Verify strategy wants to continue (e.g., tool calls detected).
     */
    fun assertShouldContinue(events: List<StrategyEvent>) {
        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>()
        assertThat(iterationComplete).hasSize(1)
        assertThat(iterationComplete.first().shouldContinue)
            .withFailMessage("Strategy should continue (shouldContinue = true)")
            .isTrue()
    }

    /**
     * Verify strategy called at least one tool.
     */
    fun assertCalledTools(events: List<StrategyEvent>) {
        val toolEvents = events.filter {
            it is StrategyEvent.ToolCallDetected || it is StrategyEvent.ToolCallsComplete
        }
        assertThat(toolEvents)
            .withFailMessage("Strategy should have called at least one tool")
            .isNotEmpty()
    }

    /**
     * Verify strategy called a specific tool by name.
     */
    fun assertCalledTool(events: List<StrategyEvent>, toolName: String) {
        val toolCalls = events.filterIsInstance<StrategyEvent.ToolCallsComplete>()
            .flatMap { it.toolCalls }

        val matchingCalls = toolCalls.filter { it.function.name == toolName }
        assertThat(matchingCalls)
            .withFailMessage("Strategy should have called tool '$toolName'")
            .isNotEmpty()
    }

    /**
     * Verify streaming produced progressive content chunks.
     */
    fun assertStreamedContent(events: List<StrategyEvent>) {
        val chunks = events.filterIsInstance<StrategyEvent.ContentChunk>()
        assertThat(chunks)
            .withFailMessage("PROGRESSIVE streaming should emit ContentChunk events")
            .isNotEmpty()
    }

    /**
     * Verify NO streaming chunks (FINAL_ONLY mode).
     */
    fun assertNoStreamingChunks(events: List<StrategyEvent>) {
        val chunks = events.filterIsInstance<StrategyEvent.ContentChunk>()
        assertThat(chunks)
            .withFailMessage("FINAL_ONLY mode should not emit ContentChunk events")
            .isEmpty()
    }

    /**
     * Verify streaming produced reasoning chunks (thinking models).
     */
    fun assertStreamedReasoning(events: List<StrategyEvent>) {
        val reasoningChunks = events.filterIsInstance<StrategyEvent.ReasoningChunk>()
        assertThat(reasoningChunks)
            .withFailMessage("Thinking model should emit ReasoningChunk events")
            .isNotEmpty()
    }

    /**
     * Verify final response exists and is substantive.
     */
    fun assertHasFinalResponse(events: List<StrategyEvent>, minLength: Int = 1) {
        val finalResponse = events.filterIsInstance<StrategyEvent.FinalResponse>()
        assertThat(finalResponse)
            .withFailMessage("Strategy should emit exactly one FinalResponse")
            .hasSize(1)
        assertThat(finalResponse.first().content.length)
            .withFailMessage("FinalResponse content should be at least $minLength characters")
            .isGreaterThanOrEqualTo(minLength)
        assertThat(finalResponse.first().tokensUsed)
            .withFailMessage("FinalResponse should report tokens used")
            .isGreaterThan(0)
    }

    /**
     * Verify response mentions at least one of the provided keywords (case-insensitive).
     * Useful for loose content verification without asserting exact strings.
     */
    fun assertMentionsAny(events: List<StrategyEvent>, vararg keywords: String) {
        val finalResponse = events.filterIsInstance<StrategyEvent.FinalResponse>().firstOrNull()
        requireNotNull(finalResponse) { "No FinalResponse found in events" }

        val content = finalResponse.content.lowercase()
        val mentionedKeywords = keywords.filter { content.contains(it.lowercase()) }

        assertThat(mentionedKeywords)
            .withFailMessage(
                "Expected response to mention at least one of: ${keywords.joinToString()}\n" +
                "But got: ${finalResponse.content.take(200)}${if (finalResponse.content.length > 200) "..." else ""}"
            )
            .isNotEmpty()
    }

    /**
     * Verify response mentions ALL provided keywords (case-insensitive).
     */
    fun assertMentionsAll(events: List<StrategyEvent>, vararg keywords: String) {
        val finalResponse = events.filterIsInstance<StrategyEvent.FinalResponse>().firstOrNull()
        requireNotNull(finalResponse) { "No FinalResponse found in events" }

        val content = finalResponse.content.lowercase()
        val missingKeywords = keywords.filter { !content.contains(it.lowercase()) }

        assertThat(missingKeywords)
            .withFailMessage(
                "Expected response to mention ALL of: ${keywords.joinToString()}\n" +
                "Missing: ${missingKeywords.joinToString()}\n" +
                "Response: ${finalResponse.content.take(200)}${if (finalResponse.content.length > 200) "..." else ""}"
            )
            .isEmpty()
    }

    /**
     * Verify event sequence ordering.
     * Example: assertEventOrder(events, ContentChunk::class, IterationComplete::class)
     * Ensures all ContentChunks come before IterationComplete.
     */
    fun assertEventOrder(
        events: List<StrategyEvent>,
        firstEventType: Class<out StrategyEvent>,
        secondEventType: Class<out StrategyEvent>
    ) {
        val lastFirst = events.indexOfLast { firstEventType.isInstance(it) }
        val firstSecond = events.indexOfFirst { secondEventType.isInstance(it) }

        if (lastFirst >= 0 && firstSecond >= 0) {
            assertThat(lastFirst)
                .withFailMessage(
                    "All ${firstEventType.simpleName} events should come before ${secondEventType.simpleName}\n" +
                    "But found ${firstEventType.simpleName} at index $lastFirst and ${secondEventType.simpleName} at $firstSecond"
                )
                .isLessThan(firstSecond)
        }
    }

    /**
     * Verify tokens used is within reasonable bounds.
     */
    fun assertReasonableTokenUsage(events: List<StrategyEvent>, minTokens: Int = 1, maxTokens: Int = 50000) {
        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>().first()
        assertThat(iterationComplete.tokensUsed)
            .withFailMessage("Token usage should be between $minTokens and $maxTokens")
            .isBetween(minTokens, maxTokens)
    }

    /**
     * Print event summary for debugging.
     */
    fun printEventSummary(events: List<StrategyEvent>, label: String = "Events") {
        println("\n${"=".repeat(60)}")
        println(label)
        println("=".repeat(60))
        println("Total events: ${events.size}")

        val eventCounts = events.groupBy { it::class.simpleName }.mapValues { it.value.size }
        eventCounts.forEach { (type, count) ->
            println("  $type: $count")
        }

        val finalResponse = events.filterIsInstance<StrategyEvent.FinalResponse>().firstOrNull()
        if (finalResponse != null) {
            println("\nFinal Response Preview:")
            println("  ${finalResponse.content.take(100)}${if (finalResponse.content.length > 100) "..." else ""}")
            println("  Tokens: ${finalResponse.tokensUsed}")
        }

        val toolCalls = events.filterIsInstance<StrategyEvent.ToolCallsComplete>().firstOrNull()
        if (toolCalls != null) {
            println("\nTool Calls:")
            toolCalls.toolCalls.forEach { tc ->
                println("  - ${tc.function.name}(${tc.function.arguments})")
            }
        }

        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>().firstOrNull()
        if (iterationComplete != null) {
            println("\nIteration Complete:")
            println("  Tokens: ${iterationComplete.tokensUsed}")
            println("  Should Continue: ${iterationComplete.shouldContinue}")
        }

        println("=".repeat(60))
    }
}
