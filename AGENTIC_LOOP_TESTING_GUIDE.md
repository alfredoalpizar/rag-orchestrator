# Agentic Loop Testing Guide

## Overview

Testing agentic loop strategies presents unique challenges because:
- **Non-deterministic outputs**: LLMs don't produce identical responses
- **Real API dependencies**: Need actual model behavior, not mocks
- **Complex state machines**: Multiple iterations, tool calls, streaming
- **Cost considerations**: Real API calls cost money and time

This guide provides practical approaches for testing strategy implementations locally with real API calls.

---

## Testing Philosophy

### ✅ **DO: Verify Behavior, Not Exact Content**

```kotlin
// ✅ GOOD: Verify structure and completion
assertThat(events).anyMatch { it is StrategyEvent.FinalResponse }
assertThat(finalResponse.content).isNotBlank()
assertThat(finalResponse.tokensUsed).isGreaterThan(0)
```

```kotlin
// ❌ BAD: Expect exact strings
assertThat(finalResponse.content).isEqualTo("The capital of France is Paris.")
```

### ✅ **DO: Test Strategy Mechanics, Not Model Intelligence**

```kotlin
// ✅ GOOD: Verify the strategy handles tool calls correctly
assertThat(events).containsSequence(
    { it is StrategyEvent.ToolCallDetected },
    { it is StrategyEvent.IterationComplete }
)
```

```kotlin
// ❌ BAD: Test if the model is "smart enough"
assertThat(response).contains("correct answer")  // Too brittle
```

### ✅ **DO: Save Outputs for Manual Review**

```kotlin
// ✅ GOOD: Capture full interaction for analysis
testOutputRecorder.save(
    testName = "deepseek_weather_tool_call",
    strategy = "deepseek_single",
    messages = messages,
    events = events,
    tokensUsed = totalTokens
)
// Outputs to: test-results/deepseek_weather_tool_call_2024-11-24_10-30-15.json
```

---

## Test Categories

### 1. **Integration Tests** (Real API Calls)

**Purpose**: Verify strategy works end-to-end with actual models

**Characteristics**:
- Call real OpenAI-compatible APIs
- Use test API keys (separate from production)
- Run on-demand (not in CI by default)
- Save outputs for review

**Example**:
```kotlin
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
class DeepSeekSingleStrategyIntegrationTest {

    @Autowired
    lateinit var strategy: DeepSeekSingleStrategy

    @Autowired
    lateinit var outputRecorder: TestOutputRecorder

    @Test
    fun `should complete simple question without tool calls`() = runBlocking {
        // Given
        val messages = listOf(
            Message(role = "user", content = "What is 2+2?")
        )
        val context = IterationContext(
            conversationId = "test-simple",
            iteration = 1,
            maxIterations = 5,
            streamingMode = StreamingMode.FINAL_ONLY
        )

        // When
        val events = strategy.executeIteration(messages, emptyList(), context).toList()

        // Then - Verify structure, not content
        val finalResponse = events.filterIsInstance<StrategyEvent.FinalResponse>().first()
        assertThat(finalResponse.content).isNotBlank()
        assertThat(finalResponse.tokensUsed).isGreaterThan(0)

        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>().first()
        assertThat(iterationComplete.shouldContinue).isFalse() // No tool calls = done

        // Save for manual review
        outputRecorder.save("deepseek_simple_math", events, context)
    }

    @Test
    fun `should call weather tool when asked about weather`() = runBlocking {
        // Given
        val messages = listOf(
            Message(role = "user", content = "What's the weather in San Francisco?")
        )
        val tools = listOf(
            ToolDefinition(
                name = "get_weather",
                description = "Get current weather for a location",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "location" to mapOf("type" to "string")
                    ),
                    "required" to listOf("location")
                )
            )
        )
        val context = IterationContext(
            conversationId = "test-weather",
            iteration = 1,
            maxIterations = 5,
            streamingMode = StreamingMode.FINAL_ONLY
        )

        // When
        val events = strategy.executeIteration(messages, tools, context).toList()

        // Then - Verify tool call behavior (loose assertions)
        val toolCalls = events.filterIsInstance<StrategyEvent.ToolCallsComplete>()
        if (toolCalls.isNotEmpty()) {
            // Model chose to use tool (expected behavior)
            val toolCallEvent = toolCalls.first()
            assertThat(toolCallEvent.toolCalls).isNotEmpty()
            assertThat(toolCallEvent.toolCalls.first().name).isEqualTo("get_weather")

            val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>().first()
            assertThat(iterationComplete.shouldContinue).isTrue() // Tool called = continue
        } else {
            // Model chose not to use tool (also valid, just different behavior)
            // This is OK - we're testing the strategy handles both cases
            println("⚠️  Model did not call tool (valid but unexpected)")
        }

        // Save for manual review
        outputRecorder.save("deepseek_weather_tool", events, context)
    }
}
```

### 2. **Strategy Mechanics Tests** (Minimal API Calls)

**Purpose**: Verify strategy emits correct event sequences

**Characteristics**:
- Focus on event flow, not content quality
- Use simple prompts to reduce cost
- Can use cheaper/faster models
- Verify streaming vs synchronous modes

**Example**:
```kotlin
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
class StrategyMechanicsTest {

    @Autowired
    lateinit var deepSeekStrategy: DeepSeekSingleStrategy

    @Autowired
    lateinit var qwenThinkingStrategy: QwenSingleThinkingStrategy

    @Test
    fun `PROGRESSIVE mode should emit ContentChunk events`() = runBlocking {
        // Given
        val messages = listOf(Message(role = "user", content = "Say 'hello'"))
        val context = IterationContext(
            conversationId = "test-streaming",
            iteration = 1,
            maxIterations = 1,
            streamingMode = StreamingMode.PROGRESSIVE // <-- Streaming enabled
        )

        // When
        val events = deepSeekStrategy.executeIteration(messages, emptyList(), context).toList()

        // Then - Verify streaming mechanics
        assertThat(events).anyMatch { it is StrategyEvent.ContentChunk }
        assertThat(events).anyMatch { it is StrategyEvent.IterationComplete }

        // Verify order: ContentChunks come before IterationComplete
        val firstComplete = events.indexOfFirst { it is StrategyEvent.IterationComplete }
        val lastChunk = events.indexOfLast { it is StrategyEvent.ContentChunk }
        assertThat(lastChunk).isLessThan(firstComplete)
    }

    @Test
    fun `FINAL_ONLY mode should emit FinalResponse without chunks`() = runBlocking {
        // Given
        val messages = listOf(Message(role = "user", content = "Say 'hello'"))
        val context = IterationContext(
            conversationId = "test-sync",
            iteration = 1,
            maxIterations = 1,
            streamingMode = StreamingMode.FINAL_ONLY // <-- No streaming
        )

        // When
        val events = deepSeekStrategy.executeIteration(messages, emptyList(), context).toList()

        // Then - Verify synchronous mechanics
        assertThat(events).noneMatch { it is StrategyEvent.ContentChunk }
        assertThat(events).anyMatch { it is StrategyEvent.FinalResponse }
        assertThat(events).anyMatch { it is StrategyEvent.IterationComplete }
    }

    @Test
    fun `Qwen thinking strategy should emit ReasoningChunk events`() = runBlocking {
        // Given
        val messages = listOf(Message(role = "user", content = "Calculate 15 * 23"))
        val context = IterationContext(
            conversationId = "test-reasoning",
            iteration = 1,
            maxIterations = 1,
            streamingMode = StreamingMode.PROGRESSIVE
        )

        // When
        val events = qwenThinkingStrategy.executeIteration(messages, emptyList(), context).toList()

        // Then - Verify reasoning is exposed
        val reasoningChunks = events.filterIsInstance<StrategyEvent.ReasoningChunk>()
        assertThat(reasoningChunks).isNotEmpty() // Qwen should emit reasoning

        val reasoningContent = reasoningChunks.joinToString("") { it.content }
        assertThat(reasoningContent).isNotBlank()
    }
}
```

### 3. **Comparative Tests** (Multi-Strategy Analysis)

**Purpose**: Compare behavior across different strategies

**Characteristics**:
- Same prompt, different strategies
- Measure performance, token usage, quality
- Generate comparison reports
- Ideal for strategy selection

**Example**:
```kotlin
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@Tag("comparison")
class StrategyComparisonTest {

    @Autowired
    lateinit var deepSeekStrategy: DeepSeekSingleStrategy

    @Autowired
    lateinit var qwenInstructStrategy: QwenSingleInstructStrategy

    @Autowired
    lateinit var qwenThinkingStrategy: QwenSingleThinkingStrategy

    @Autowired
    lateinit var comparisonRecorder: ComparisonRecorder

    @Test
    fun `compare all strategies on complex reasoning task`() = runBlocking {
        // Given
        val messages = listOf(
            Message(
                role = "user",
                content = """
                    A farmer has 17 sheep. All but 9 die. How many are left?
                    Explain your reasoning step by step.
                """.trimIndent()
            )
        )
        val context = IterationContext(
            conversationId = "comparison-reasoning",
            iteration = 1,
            maxIterations = 1,
            streamingMode = StreamingMode.FINAL_ONLY
        )

        // When - Run all strategies
        val results = mapOf(
            "deepseek_single" to measureStrategy(deepSeekStrategy, messages, context),
            "qwen_instruct" to measureStrategy(qwenInstructStrategy, messages, context),
            "qwen_thinking" to measureStrategy(qwenThinkingStrategy, messages, context)
        )

        // Then - Generate comparison report (for manual review)
        comparisonRecorder.saveComparison(
            testName = "reasoning_task_comparison",
            prompt = messages,
            results = results
        )

        // Basic sanity checks (all should complete)
        results.values.forEach { result ->
            assertThat(result.events).anyMatch {
                it is StrategyEvent.FinalResponse || it is StrategyEvent.ToolCallsComplete
            }
            assertThat(result.durationMs).isGreaterThan(0)
            assertThat(result.tokensUsed).isGreaterThan(0)
        }

        // Print summary
        println("\n${"=".repeat(60)}")
        println("STRATEGY COMPARISON RESULTS")
        println("=".repeat(60))
        results.forEach { (strategy, result) ->
            println("Strategy: $strategy")
            println("  Duration: ${result.durationMs}ms")
            println("  Tokens: ${result.tokensUsed}")
            println("  Response: ${result.finalContent.take(100)}...")
            println()
        }
    }

    private suspend fun measureStrategy(
        strategy: ModelStrategyExecutor,
        messages: List<Message>,
        context: IterationContext
    ): StrategyResult {
        val start = System.currentTimeMillis()
        val events = strategy.executeIteration(messages, emptyList(), context).toList()
        val duration = System.currentTimeMillis() - start

        val finalResponse = events.filterIsInstance<StrategyEvent.FinalResponse>().firstOrNull()
        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>().first()

        return StrategyResult(
            events = events,
            durationMs = duration,
            tokensUsed = iterationComplete.tokensUsed,
            finalContent = finalResponse?.content ?: "(no final response)"
        )
    }

    data class StrategyResult(
        val events: List<StrategyEvent>,
        val durationMs: Long,
        val tokensUsed: Int,
        val finalContent: String
    )
}
```

---

## Test Utilities

### **TestOutputRecorder** - Save Results for Manual Review

```kotlin
@Component
class TestOutputRecorder {
    private val outputDir = File("test-outputs").apply { mkdirs() }

    fun save(
        testName: String,
        events: List<StrategyEvent>,
        context: IterationContext,
        metadata: Map<String, Any> = emptyMap()
    ) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val filename = "${testName}_${timestamp}.json"

        val output = mapOf(
            "testName" to testName,
            "timestamp" to timestamp,
            "conversationId" to context.conversationId,
            "iteration" to context.iteration,
            "streamingMode" to context.streamingMode.name,
            "events" to events.map { event ->
                when (event) {
                    is StrategyEvent.FinalResponse -> mapOf(
                        "type" to "FinalResponse",
                        "content" to event.content,
                        "tokensUsed" to event.tokensUsed,
                        "metadata" to event.metadata
                    )
                    is StrategyEvent.ToolCallsComplete -> mapOf(
                        "type" to "ToolCallsComplete",
                        "toolCalls" to event.toolCalls.map {
                            mapOf("name" to it.name, "arguments" to it.arguments)
                        },
                        "assistantContent" to event.assistantContent
                    )
                    is StrategyEvent.ContentChunk -> mapOf(
                        "type" to "ContentChunk",
                        "content" to event.content
                    )
                    is StrategyEvent.ReasoningChunk -> mapOf(
                        "type" to "ReasoningChunk",
                        "content" to event.content
                    )
                    is StrategyEvent.IterationComplete -> mapOf(
                        "type" to "IterationComplete",
                        "tokensUsed" to event.tokensUsed,
                        "shouldContinue" to event.shouldContinue
                    )
                    else -> mapOf("type" to event::class.simpleName)
                }
            },
            "metadata" to metadata
        )

        val file = File(outputDir, filename)
        file.writeText(Json.encodeToString(output))

        println("📝 Test output saved: ${file.absolutePath}")
    }
}
```

### **Assertion Helpers** - Loose, Structural Assertions

```kotlin
object StrategyAssertions {

    /**
     * Verify strategy completed successfully (with or without tool calls)
     */
    fun assertCompletedSuccessfully(events: List<StrategyEvent>) {
        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>()
        assertThat(iterationComplete).hasSize(1)
        assertThat(iterationComplete.first().tokensUsed).isGreaterThan(0)
    }

    /**
     * Verify strategy finalized (no more iterations needed)
     */
    fun assertFinalized(events: List<StrategyEvent>) {
        val iterationComplete = events.filterIsInstance<StrategyEvent.IterationComplete>().first()
        assertThat(iterationComplete.shouldContinue).isFalse()
    }

    /**
     * Verify strategy called at least one tool
     */
    fun assertCalledTools(events: List<StrategyEvent>) {
        val toolEvents = events.filter {
            it is StrategyEvent.ToolCallDetected || it is StrategyEvent.ToolCallsComplete
        }
        assertThat(toolEvents).isNotEmpty()
    }

    /**
     * Verify streaming produced incremental chunks
     */
    fun assertStreamedContent(events: List<StrategyEvent>) {
        val chunks = events.filterIsInstance<StrategyEvent.ContentChunk>()
        assertThat(chunks).hasSizeGreaterThan(1) // Should have multiple chunks
    }

    /**
     * Verify final response exists and is substantive
     */
    fun assertHasFinalResponse(events: List<StrategyEvent>, minLength: Int = 10) {
        val finalResponse = events.filterIsInstance<StrategyEvent.FinalResponse>()
        assertThat(finalResponse).hasSize(1)
        assertThat(finalResponse.first().content.length).isGreaterThanOrEqualTo(minLength)
    }

    /**
     * Verify response mentions specific keywords (loose matching)
     */
    fun assertMentions(events: List<StrategyEvent>, vararg keywords: String) {
        val finalResponse = events.filterIsInstance<StrategyEvent.FinalResponse>().firstOrNull()
        requireNotNull(finalResponse) { "No final response found" }

        val content = finalResponse.content.lowercase()
        val mentionedKeywords = keywords.filter { content.contains(it.lowercase()) }

        assertThat(mentionedKeywords).isNotEmpty()
            .withFailMessage(
                "Expected response to mention at least one of: ${keywords.joinToString()}\n" +
                "But got: $content"
            )
    }
}
```

---

## Running Tests Locally

### **Configuration**

Create `application-integration-test.yml`:

```yaml
# Use real API keys from environment variables
deepseek:
  api:
    base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}
    api-key: ${DEEPSEEK_API_KEY}
    model: ${DEEPSEEK_MODEL:deepseek-chat}

qwen:
  api:
    base-url: ${QWEN_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
    api-key: ${QWEN_API_KEY}
    model-instruct: ${QWEN_MODEL_INSTRUCT:qwen-plus}
    model-thinking: ${QWEN_MODEL_THINKING:qwen-plus}

loop:
  model-strategy: ${TEST_STRATEGY:deepseek_single}
  max-iterations: 5
  temperature: 0.7
  max-tokens: 2000

# Test-specific settings
test:
  output-dir: ./test-outputs
  save-all-outputs: true
```

### **Running Tests**

```bash
# Set API keys
export DEEPSEEK_API_KEY="your-key-here"
export QWEN_API_KEY="your-key-here"

# Run all integration tests
./gradlew test --tests "*IntegrationTest" -Dspring.profiles.active=integration-test

# Run specific strategy test
./gradlew test --tests "DeepSeekSingleStrategyIntegrationTest" -Dspring.profiles.active=integration-test

# Run comparison tests only
./gradlew test --tests "*ComparisonTest" -Dspring.profiles.active=integration-test

# Review outputs
ls -la test-outputs/
cat test-outputs/deepseek_simple_math_2024-11-24_10-30-15.json | jq .
```

### **Test Execution Strategy**

1. **During Development**: Run focused tests on the strategy you're modifying
   ```bash
   ./gradlew test --tests "DeepSeekSingleStrategyIntegrationTest.should complete simple question" \
     -Dspring.profiles.active=integration-test
   ```

2. **Before Commit**: Run mechanics tests (fast, cheap)
   ```bash
   ./gradlew test --tests "StrategyMechanicsTest" \
     -Dspring.profiles.active=integration-test
   ```

3. **Weekly/Manual**: Run full comparison suite and review outputs
   ```bash
   ./gradlew test --tests "*ComparisonTest" \
     -Dspring.profiles.active=integration-test
   # Then manually review test-outputs/
   ```

---

## Best Practices

### 1. **Use Loose Assertions**
- ✅ Assert structure (has final response, called tool, completed iteration)
- ✅ Assert numeric bounds (tokens > 0, response length > 10)
- ✅ Assert event sequences (chunks before completion)
- ❌ Don't assert exact strings
- ❌ Don't assert specific word counts

### 2. **Save Everything for Manual Review**
- Every test should save its full output to `test-outputs/`
- Include timestamps, strategy name, token usage
- Use JSON for easy analysis with `jq`, Python scripts, etc.
- Commit notable outputs to `test-outputs/examples/` for documentation

### 3. **Test Strategy Mechanics, Not Model Intelligence**
- ✅ Does streaming produce chunks?
- ✅ Does synchronous mode skip chunks?
- ✅ Are tool calls detected correctly?
- ✅ Does iteration complete with correct flags?
- ❌ Don't test "is the answer correct?" (too brittle)
- ❌ Don't test "is the reasoning good?" (subjective)

### 4. **Use Tiered Testing**
- **Tier 1 (Fast)**: Mechanics tests with simple prompts
- **Tier 2 (Medium)**: Integration tests with realistic prompts
- **Tier 3 (Slow)**: Comparison tests across all strategies

### 5. **Handle Non-Determinism Gracefully**
```kotlin
// ✅ GOOD: Accept multiple valid outcomes
val toolCalls = events.filterIsInstance<StrategyEvent.ToolCallsComplete>()
if (toolCalls.isNotEmpty()) {
    // Model used tool (expected)
    assertThat(toolCalls.first().toolCalls.first().name).isEqualTo("get_weather")
} else {
    // Model didn't use tool (also valid, just different behavior)
    println("⚠️  Model chose not to use tool")
}
```

### 6. **Cost Management**
- Use cheaper models for mechanics tests (`deepseek-chat` < `deepseek-reasoner`)
- Use shorter prompts when testing infrastructure, not quality
- Tag expensive tests with `@Tag("expensive")` and run separately
- Set timeouts to prevent runaway costs

### 7. **Document Surprising Behavior**
When a test reveals unexpected behavior, document it:
```kotlin
@Test
fun `qwen thinking sometimes skips reasoning for simple math`() = runBlocking {
    // KNOWN BEHAVIOR: Qwen thinking model may skip reasoning chunks
    // for very simple questions. This is model behavior, not a bug.

    val events = qwenThinkingStrategy.executeIteration(...)
    val reasoningChunks = events.filterIsInstance<StrategyEvent.ReasoningChunk>()

    // We accept both outcomes
    if (reasoningChunks.isEmpty()) {
        println("ℹ️  Model skipped reasoning (expected for simple question)")
    } else {
        println("ℹ️  Model used reasoning (also valid)")
    }

    // Just verify it completes successfully
    StrategyAssertions.assertCompletedSuccessfully(events)
}
```

---

## Example Test Session

```bash
$ export DEEPSEEK_API_KEY="sk-..."
$ ./gradlew test --tests "DeepSeekSingleStrategyIntegrationTest" -Dspring.profiles.active=integration-test

> Task :test

DeepSeekSingleStrategyIntegrationTest > should complete simple question without tool calls() PASSED
📝 Test output saved: /home/user/rag-orchestrator/test-outputs/deepseek_simple_math_2024-11-24_10-30-15.json

DeepSeekSingleStrategyIntegrationTest > should call weather tool when asked about weather() PASSED
⚠️  Model did not call tool (valid but unexpected)
📝 Test output saved: /home/user/rag-orchestrator/test-outputs/deepseek_weather_tool_2024-11-24_10-30-22.json

BUILD SUCCESSFUL in 12s
3 actionable tasks: 2 executed, 1 up-to-date

$ cat test-outputs/deepseek_simple_math_2024-11-24_10-30-15.json | jq '.events[] | select(.type == "FinalResponse") | .content'
"2 + 2 equals 4."

$ cat test-outputs/deepseek_weather_tool_2024-11-24_10-30-22.json | jq '.events[] | select(.type == "FinalResponse") | .content'
"I don't have access to real-time weather data. Please check a weather website or app for current conditions in San Francisco."
```

---

## Summary

**Key Principles**:
1. ✅ Test with real APIs, not mocks
2. ✅ Assert structure and behavior, not exact content
3. ✅ Save all outputs for manual review
4. ✅ Verify strategies finalize correctly
5. ✅ Handle non-determinism gracefully

**Test Types**:
- **Integration Tests**: End-to-end with real models
- **Mechanics Tests**: Event flow and streaming behavior
- **Comparison Tests**: Cross-strategy analysis

**Running Locally**:
```bash
export DEEPSEEK_API_KEY="..." QWEN_API_KEY="..."
./gradlew test --tests "*IntegrationTest" -Dspring.profiles.active=integration-test
ls -la test-outputs/  # Review results
```

This approach gives you confidence that strategies work correctly while embracing the non-deterministic nature of LLMs. Focus on what you can control (event flow, completion) and manually review what you can't (content quality).
