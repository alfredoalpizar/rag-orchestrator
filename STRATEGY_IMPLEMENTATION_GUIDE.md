# Strategy Implementation Guide

## Overview

This guide explains how to add new model strategies to the RAG Orchestrator and why this architecture prevents bloat while enabling rapid experimentation.

---

## Why This Approach is Maintainable

### 1. **Clean Separation of Concerns**

Each component has a single responsibility:

```
┌─────────────────────────────────────────────┐
│ OrchestratorService                         │
│ • Manages conversation lifecycle            │
│ • Handles tool execution                    │
│ • Transforms events for clients             │
│ • Strategy-agnostic                         │
└──────────────┬──────────────────────────────┘
               │ delegates to
               ▼
┌─────────────────────────────────────────────┐
│ ModelStrategyExecutor (Interface)           │
│ • Executes LLM iterations                   │
│ • Emits strategy-specific events            │
│ • Stateless (no conversation management)    │
└──────────────┬──────────────────────────────┘
               │ uses
               ▼
┌─────────────────────────────────────────────┐
│ ModelProvider (Interface)                   │
│ • Wraps vendor-specific API clients         │
│ • Normalizes requests/responses             │
│ • Handles streaming                         │
└─────────────────────────────────────────────┘
```

**Result**: Changing one component doesn't affect others.

### 2. **Zero Cross-Contamination**

Each strategy is **completely isolated**:

```
strategy/impl/
├── DeepSeekSingleStrategy.kt      (153 lines)
├── QwenSingleInstructStrategy.kt  (177 lines)
├── QwenSingleThinkingStrategy.kt  (214 lines)
└── QwenHybridStagedStrategy.kt    (189 lines)
```

- No shared state between strategies
- No if/else logic based on strategy type
- Each file is self-contained
- Delete a strategy file = completely remove that strategy

**Result**: Adding/removing strategies has zero impact on existing code.

### 3. **Configuration-Driven Selection**

Spring's `@ConditionalOnProperty` automatically selects the right strategy:

```kotlin
@Component
@ConditionalOnProperty(
    prefix = "loop",
    name = ["model-strategy"],
    havingValue = "qwen_single_thinking"
)
class QwenSingleThinkingStrategy { ... }
```

**Only one strategy is instantiated** - no memory waste, no conditional logic at runtime.

### 4. **Interface-Based Design**

All strategies implement the same contract:

```kotlin
interface ModelStrategyExecutor {
    suspend fun executeIteration(...): Flow<StrategyEvent>
    fun getStrategyMetadata(): StrategyMetadata
}
```

**Result**: OrchestratorService works with any strategy without modifications.

---

## Why This Won't Lead to Bloat

### ❌ **Traditional Approach (Leads to Bloat)**

```kotlin
// OrchestratorService (BLOATED)
class OrchestratorService {
    fun process() {
        if (config.model == "deepseek") {
            // 50 lines of DeepSeek logic
        } else if (config.model == "qwen-thinking") {
            // 50 lines of Qwen thinking logic
        } else if (config.model == "qwen-instruct") {
            // 50 lines of Qwen instruct logic
        } else if (config.model == "claude") {
            // 50 lines of Claude logic
        }
        // Gets worse with each new model...
    }
}
```

**Problems**:
- OrchestratorService grows to 500+ lines
- Hard to test individual strategies
- Risk of breaking existing code when adding new strategies
- Messy git diffs touching core orchestration

### ✅ **Our Approach (Prevents Bloat)**

```kotlin
// OrchestratorService (CLEAN)
class OrchestratorService(
    private val strategy: ModelStrategyExecutor  // Injected
) {
    fun process() {
        strategy.executeIteration(...)  // Same for all strategies
    }
}
```

**Benefits**:
- OrchestratorService stays at ~450 lines regardless of strategy count
- Each new strategy = one new file (150-200 lines)
- Adding 10 strategies = 10 files, 0 changes to core
- Git diffs only touch new files

### **Bloat Prevention Metrics**

| Action | Traditional | Our Approach |
|--------|-------------|--------------|
| Add new strategy | +50 lines to core | +1 file (0 core changes) |
| Remove strategy | Risky refactor | Delete 1 file |
| Test strategy | Mock entire system | Test strategy in isolation |
| Core complexity | O(n) strategies | O(1) constant |

---

## How to Add a New Strategy

### **Step 1: Create Provider (if needed)**

If you're adding a new LLM vendor (e.g., Claude, GPT-4):

```kotlin
// provider/ClaudeModelProvider.kt
@Component
class ClaudeModelProvider(
    private val client: ClaudeClient,
    private val properties: ClaudeProperties
) : ModelProvider<ClaudeRequest, ClaudeResponse, ClaudeStreamChunk> {

    override suspend fun chat(request: ClaudeRequest) =
        client.chat(request)

    override fun chatStream(request: ClaudeRequest) =
        client.chatStream(request).asFlow()

    override fun buildRequest(messages: List<Message>, tools: List<*>, config: RequestConfig) =
        ClaudeRequest(
            model = properties.model,
            messages = messages.map { it.toClaudeMessage() },
            // ... normalize to Claude format
        )

    override fun extractMessage(response: ClaudeResponse) =
        ProviderMessage(
            content = response.content,
            toolCalls = response.toolCalls?.map { it.toToolCall() },
            tokensUsed = response.usage.totalTokens
        )

    override fun extractStreamChunk(chunk: ClaudeStreamChunk) =
        ProviderStreamChunk(
            contentDelta = chunk.delta.text,
            reasoningDelta = null,  // Claude doesn't expose reasoning
            toolCalls = chunk.delta.toolCalls?.map { it.toToolCall() },
            finishReason = chunk.stopReason,
            role = chunk.delta.role
        )

    override fun getProviderInfo() = ProviderInfo(
        name = "Claude",
        supportsStreaming = true,
        supportsReasoningStream = false,
        supportsToolCalling = true
    )
}
```

### **Step 2: Create Strategy**

```kotlin
// strategy/impl/ClaudeSingleStrategy.kt
@Component
@ConditionalOnProperty(
    prefix = "loop",
    name = ["model-strategy"],
    havingValue = "claude_single"
)
class ClaudeSingleStrategy(
    private val provider: ClaudeModelProvider,
    private val properties: LoopProperties
) : ModelStrategyExecutor {

    private val logger = KotlinLogging.logger {}

    init {
        logger.info {
            """
            ╔════════════════════════════════════════════════════════╗
            ║  STRATEGY INITIALIZED: Claude Single                   ║
            ║  Provider: Claude API (Anthropic)                      ║
            ║  Model: claude-3-5-sonnet                              ║
            ║  Reasoning Stream: No                                  ║
            ║  Description: Single Claude model for all iterations   ║
            ╚════════════════════════════════════════════════════════╝
            """.trimIndent()
        }
    }

    override suspend fun executeIteration(
        messages: List<Message>,
        tools: List<*>,
        iterationContext: IterationContext
    ): Flow<StrategyEvent> = flow {

        val requestConfig = RequestConfig(
            streamingEnabled = iterationContext.streamingMode == StreamingMode.PROGRESSIVE,
            temperature = properties.temperature,
            maxTokens = properties.maxTokens
        )

        if (iterationContext.streamingMode == StreamingMode.PROGRESSIVE) {
            executeStreamingIteration(messages, tools, requestConfig)
        } else {
            executeSynchronousIteration(messages, tools, requestConfig)
        }
    }

    private suspend fun FlowCollector<StrategyEvent>.executeStreamingIteration(...) {
        // Stream progressive chunks
        val request = provider.buildRequest(messages, tools, config)
        provider.chatStream(request).collect { chunk ->
            val parsed = provider.extractStreamChunk(chunk)

            parsed.contentDelta?.let { emit(StrategyEvent.ContentChunk(it)) }
            parsed.toolCalls?.let { toolCalls ->
                toolCalls.forEach { emit(StrategyEvent.ToolCallDetected(it, it.id)) }
            }

            if (parsed.finishReason != null) {
                emit(StrategyEvent.IterationComplete(...))
            }
        }
    }

    private suspend fun FlowCollector<StrategyEvent>.executeSynchronousIteration(...) {
        // Return final result only
        val request = provider.buildRequest(messages, tools, config)
        val response = provider.chat(request)
        val message = provider.extractMessage(response)

        if (message.toolCalls != null) {
            emit(StrategyEvent.ToolCallsComplete(...))
        } else {
            emit(StrategyEvent.FinalResponse(...))
        }
        emit(StrategyEvent.IterationComplete(...))
    }

    override fun getStrategyMetadata() = StrategyMetadata(
        name = "Claude Single",
        strategyType = ModelStrategy.CLAUDE_SINGLE,
        supportsReasoningStream = false,
        supportsSynchronous = true,
        description = "Single Claude model for all iterations"
    )
}
```

### **Step 3: Add Configuration**

**Add to `LoopProperties.kt`:**
```kotlin
enum class ModelStrategy {
    DEEPSEEK_SINGLE,
    QWEN_SINGLE_THINKING,
    QWEN_SINGLE_INSTRUCT,
    QWEN_HYBRID_STAGED,
    CLAUDE_SINGLE  // ← Add here
}
```

**Add to `application.yml`:**
```yaml
# Claude API Configuration
claude:
  api:
    base-url: ${CLAUDE_BASE_URL:https://api.anthropic.com}
    api-key: ${CLAUDE_API_KEY:}
    model: ${CLAUDE_MODEL:claude-3-5-sonnet-20241022}
    timeout-seconds: 60
    max-retries: 3

loop:
  model-strategy: claude_single  # ← Use new strategy
```

### **Step 4: Test**

```bash
# Build
./gradlew build

# Run
./gradlew bootRun

# You'll see:
╔════════════════════════════════════════════════════════╗
║  STRATEGY INITIALIZED: Claude Single                   ║
║  Provider: Claude API (Anthropic)                      ║
...
╚════════════════════════════════════════════════════════╝
```

**That's it!** No changes to OrchestratorService, ContextManager, or any other core components.

---

## Hit the Ground Running: Experimentation Workflow

### **Scenario: Testing 4 Different Strategies**

You want to find the best strategy for your use case:

**Day 1: Baseline with DeepSeek**
```yaml
# application.yml
loop:
  model-strategy: deepseek_single
```
- Run benchmarks
- Collect metrics (latency, quality, cost)
- Document edge cases

**Day 2: Try Qwen Fast**
```yaml
loop:
  model-strategy: qwen_single_instruct
```
- Same benchmarks
- Compare speed vs DeepSeek
- **Zero code changes**

**Day 3: Try Qwen Reasoning**
```yaml
loop:
  model-strategy: qwen_single_thinking
```
- Test complex reasoning tasks
- Evaluate reasoning quality
- **Zero code changes**

**Day 4: Try Hybrid Approach**
```yaml
loop:
  model-strategy: qwen_hybrid_staged
```
- Best of both worlds?
- Measure overhead
- **Zero code changes**

### **Rapid Iteration Benefits**

| Task | Time (Traditional) | Time (Our Approach) |
|------|-------------------|---------------------|
| Switch strategies | 2 hours (refactor code) | 30 seconds (change config) |
| Test new model | 1 day (integration) | 2 hours (write strategy) |
| A/B test 2 strategies | Complex (branching logic) | Simple (run twice) |
| Roll back | Risky (revert commits) | Safe (change config) |

---

## Advanced: Custom Strategy Patterns

### **Example: Fallback Strategy**

```kotlin
@Component
@ConditionalOnProperty(name = ["loop.model-strategy"], havingValue = "fallback")
class FallbackStrategy(
    private val primaryStrategy: QwenSingleThinkingStrategy,
    private val fallbackStrategy: DeepSeekSingleStrategy
) : ModelStrategyExecutor {

    override suspend fun executeIteration(...): Flow<StrategyEvent> = flow {
        try {
            primaryStrategy.executeIteration(...).collect { emit(it) }
        } catch (e: Exception) {
            logger.warn { "Primary failed, falling back: ${e.message}" }
            fallbackStrategy.executeIteration(...).collect { emit(it) }
        }
    }
}
```

### **Example: Multi-Model Voting Strategy**

```kotlin
@Component
@ConditionalOnProperty(name = ["loop.model-strategy"], havingValue = "voting")
class VotingStrategy(
    private val strategies: List<ModelStrategyExecutor>
) : ModelStrategyExecutor {

    override suspend fun executeIteration(...): Flow<StrategyEvent> = flow {
        // Execute all strategies in parallel
        val results = strategies.map { strategy ->
            async {
                strategy.executeIteration(...).toList()
            }
        }.awaitAll()

        // Vote on best response
        val winner = selectWinner(results)
        winner.forEach { emit(it) }
    }
}
```

---

## Comparison: Before vs After

### **Before (Monolithic)**

```kotlin
// ❌ Everything in one file (267 lines → 500+ with more models)
class OrchestratorService {
    fun process() {
        if (useDeepSeek) {
            val request = buildDeepSeekRequest()  // 20 lines
            val response = deepSeekClient.chat()  // 30 lines
            // ... DeepSeek-specific logic
        } else if (useQwenThinking) {
            val request = buildQwenThinkingRequest()  // 25 lines
            val response = qwenClient.chat()  // 35 lines
            // ... Qwen thinking logic
        }
        // Every new model adds 50+ lines here
    }
}
```

**Problems**:
- Tight coupling to specific APIs
- Hard to test individual paths
- High risk of regression
- Grows unbounded

### **After (Strategy Pattern)**

```kotlin
// ✅ Clean delegation (450 lines, constant)
class OrchestratorService(
    private val strategy: ModelStrategyExecutor  // Injected by Spring
) {
    fun process() {
        strategy.executeIteration(...)
            .collect { event -> /* transform to StreamEvent */ }
    }
}
```

**Benefits**:
- Zero coupling to specific APIs
- Each strategy tested independently
- Zero regression risk (no core changes)
- Fixed size regardless of strategy count

---

## Key Takeaways

### **Why This Architecture is Maintainable**

1. **Single Responsibility**: Each class does one thing
2. **Open/Closed**: Open for extension (new strategies), closed for modification (core unchanged)
3. **Dependency Inversion**: Core depends on abstractions, not implementations
4. **Interface Segregation**: Minimal, focused interfaces

### **Why This Won't Bloat**

1. **Horizontal scaling**: New strategies = new files (not more code in existing files)
2. **Spring magic**: Only active strategy is instantiated
3. **No conditionals**: Zero if/else based on strategy type
4. **Deletable**: Remove strategy by deleting one file

### **Why This Enables Rapid Experimentation**

1. **Configuration-driven**: Change strategy in 30 seconds
2. **Zero downtime**: Restart to switch strategies
3. **Isolated testing**: Test each strategy independently
4. **Quick iteration**: Write new strategy in 2 hours
5. **Safe rollback**: Just change config back

---

## Next Steps

1. **Read**: [HYBRID_STRATEGY_GUIDE.md](HYBRID_STRATEGY_GUIDE.md) for advanced multi-stage strategies
2. **Experiment**: Try all 4 existing strategies with your workload
3. **Measure**: Collect metrics (latency, tokens, quality) for each
4. **Optimize**: Pick the best strategy for your use case
5. **Extend**: Add your own custom strategies as needed

**The architecture is ready. Now go find what works best for you!** 🚀
