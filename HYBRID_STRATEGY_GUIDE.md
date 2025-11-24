# Hybrid Strategy Guide

## Overview

This guide explains how to implement and use complex multi-stage hybrid strategies, focusing on the Qwen Hybrid Staged approach and community best practices.

---

## What is a Hybrid Strategy?

A **hybrid strategy** uses **multiple models** within a single agentic loop, optimizing for different stages:

```
┌─────────────────────────────────────────────────────────┐
│                  AGENTIC LOOP                           │
│                                                         │
│  ┌──────────────┐   ┌──────────────┐   ┌────────────┐ │
│  │ PLANNING     │ → │ EXECUTION    │ → │ SYNTHESIS  │ │
│  │ (Thinking)   │   │ (Instruct)   │   │ (Thinking) │ │
│  │              │   │              │   │            │ │
│  │ qwen-max     │   │ qwen-plus    │   │ qwen-max   │ │
│  │ (reasoning)  │   │ (fast tools) │   │ (reasoning)│ │
│  └──────────────┘   └──────────────┘   └────────────┘ │
└─────────────────────────────────────────────────────────┘
```

### **Why Hybrid?**

Different models have different strengths:

| Model Type | Strength | Weakness | Best For |
|------------|----------|----------|----------|
| **Thinking** (qwen-max) | Deep reasoning, planning | Slower, expensive | Planning, synthesis |
| **Instruct** (qwen-plus) | Fast execution, precise | Less creative | Tool calling, data retrieval |

**Hybrid = Use the right model for each stage.**

---

## Qwen Community Recommendations

Based on [Qwen documentation](https://help.aliyun.com/zh/model-studio/developer-reference/qwen-reasoning-model) and community feedback:

### **1. Use Thinking Models for Planning**

Qwen thinking models (`qwen-max`, `qwen-turbo` with `enable_thinking=true`) excel at:
- **Breaking down complex tasks** into steps
- **Understanding user intent** deeply
- **Planning tool usage** strategically

```kotlin
// Stage 1: Planning with thinking model
val planningConfig = RequestConfig(
    streamingEnabled = true,
    extraParams = mapOf(
        "useThinkingModel" to true,
        "enableThinking" to true  // Enable reasoning
    )
)
```

**Qwen will emit:**
- `reasoning_content`: Internal thinking process
- `content`: Planned approach
- `tool_calls`: Strategic tool selections

### **2. Use Instruct Models for Execution**

Once the plan is clear, switch to fast instruct models for:
- **Executing tool calls** efficiently
- **Processing results** quickly
- **Iterating** without overhead

```kotlin
// Stage 2: Execution with instruct model
val executionConfig = RequestConfig(
    streamingEnabled = false,  // Fast synchronous
    extraParams = mapOf(
        "useInstructModel" to true,
        "enableThinking" to false
    )
)
```

### **3. Use Thinking Models for Synthesis**

After gathering data, return to thinking model to:
- **Synthesize insights** from tool results
- **Generate high-quality** final answer
- **Ensure accuracy** and completeness

```kotlin
// Stage 3: Synthesis with thinking model
val synthesisConfig = RequestConfig(
    streamingEnabled = true,
    extraParams = mapOf(
        "useThinkingModel" to true,
        "enableThinking" to true
    )
)
```

---

## Implementation: Qwen Hybrid Staged Strategy

Our implementation follows community best practices:

### **Architecture**

```kotlin
@Component
@ConditionalOnProperty(
    prefix = "loop",
    name = ["model-strategy"],
    havingValue = "qwen_hybrid_staged"
)
class QwenHybridStagedStrategy(
    private val provider: QwenModelProvider,
    private val properties: LoopProperties
) : ModelStrategyExecutor {

    override suspend fun executeIteration(...): Flow<StrategyEvent> = flow {
        // STAGE 1: Planning
        emit(StatusUpdate("Planning with thinking model...", "planning"))
        val plan = executePlanningStage(...)

        if (plan.toolCalls.isNotEmpty()) {
            // STAGE 2: Execution
            emit(StatusUpdate("Executing with instruct model...", "executing"))
            emit(ToolCallsComplete(plan.toolCalls, plan.content))
            // Orchestrator executes tools...
        } else {
            // STAGE 3: Direct synthesis (no tools needed)
            emit(StatusUpdate("Synthesizing final response...", "synthesizing"))
            emit(FinalResponse(plan.content, plan.tokensUsed))
        }
    }
}
```

### **Stage 1: Planning (Thinking Model)**

```kotlin
private suspend fun FlowCollector<StrategyEvent>.executePlanningStage(
    messages: List<Message>,
    tools: List<*>,
    context: IterationContext
): PlanningResult {

    val requestConfig = RequestConfig(
        streamingEnabled = context.streamingMode == StreamingMode.PROGRESSIVE,
        temperature = properties.temperature,
        maxTokens = properties.maxTokens,
        extraParams = mapOf(
            "useThinkingModel" to true,
            "enableThinking" to true  // ← Key: Enable reasoning
        )
    )

    val request = provider.buildRequest(messages, tools, requestConfig)

    var planningContent = StringBuilder()
    var reasoningContent = StringBuilder()
    var toolCalls = mutableListOf<ToolCall>()

    if (context.streamingMode == StreamingMode.PROGRESSIVE) {
        // Stream reasoning traces in real-time
        provider.chatStream(request).collect { chunk ->
            val parsed = provider.extractStreamChunk(chunk)

            // ✨ REASONING STREAM
            parsed.reasoningDelta?.let { delta ->
                reasoningContent.append(delta)
                if (properties.thinking.showReasoningTraces) {
                    emit(StrategyEvent.ReasoningChunk(
                        content = delta,
                        metadata = mapOf(
                            "stage" to "planning",
                            "model" to "qwen-max"
                        )
                    ))
                }
            }

            // Regular content
            parsed.contentDelta?.let { planningContent.append(it) }

            // Tool calls from planning
            parsed.toolCalls?.let { toolCalls.addAll(it) }
        }
    } else {
        // Synchronous planning
        val response = provider.chat(request)
        val message = provider.extractMessage(response)

        planningContent.append(message.content ?: "")
        toolCalls.addAll(message.toolCalls ?: emptyList())

        // Reasoning in response
        message.reasoningContent?.let { reasoning ->
            reasoningContent.append(reasoning)
            if (properties.thinking.showReasoningTraces) {
                emit(StrategyEvent.ReasoningChunk(
                    content = reasoning,
                    metadata = mapOf("stage" to "planning", "model" to "qwen-max")
                ))
            }
        }
    }

    return PlanningResult(
        planningContent = planningContent.toString(),
        reasoningContent = reasoningContent.toString(),
        toolCalls = toolCalls,
        tokensUsed = /* from response */
    )
}
```

### **Stage 2: Execution (Orchestrator)**

The orchestrator handles tool execution:

```kotlin
// In OrchestratorService.kt
when (strategyEvent) {
    is StrategyEvent.ToolCallsComplete -> {
        // Add assistant message with tool calls
        messages.add(Message(
            role = MessageRole.ASSISTANT,
            content = strategyEvent.assistantContent,
            toolCalls = strategyEvent.toolCalls
        ))

        // Execute each tool
        for (toolCall in strategyEvent.toolCalls) {
            emit(StreamEvent.ToolCallStart(...))
            val result = toolRegistry.executeTool(toolCall)
            emit(StreamEvent.ToolCallResult(...))

            // Add tool result to messages
            messages.add(Message(
                role = MessageRole.TOOL,
                content = result.result,
                toolCallId = toolCall.id
            ))
        }
    }
}

// Next iteration uses instruct model for fast follow-up
// if needed, or thinking model for synthesis
```

### **Stage 3: Synthesis (Thinking Model)**

After tools execute, the next iteration synthesizes:

```kotlin
// Orchestrator calls strategy again with tool results
strategyExecutor.executeIteration(
    messages = messages,  // Now includes tool results
    tools = toolRegistry.getToolDefinitions(),
    iterationContext = IterationContext(...)
).collect { event ->
    // Strategy uses thinking model again to synthesize
    when (event) {
        is StrategyEvent.ReasoningChunk -> {
            // Synthesis reasoning
            emit(StreamEvent.ReasoningTrace(
                content = event.content,
                stage = ReasoningStage.SYNTHESIS
            ))
        }
        is StrategyEvent.FinalResponse -> {
            // Final answer after synthesis
            emit(StreamEvent.ResponseChunk(content = event.content))
        }
    }
}
```

---

## Reasoning Stream: How It Works

### **Qwen Thinking Models**

When `enable_thinking=true`, Qwen models emit two types of content:

1. **`reasoning_content`** (or `reasoning_delta` in streaming):
   - Internal thinking process
   - Step-by-step reasoning
   - Problem breakdown
   - **Not shown to end user by default**

2. **`content`** (or `content_delta` in streaming):
   - Final response
   - Polished output
   - User-facing content

### **Streaming Example**

**Request:**
```json
{
  "model": "qwen-max",
  "messages": [...],
  "enable_thinking": true,
  "stream": true
}
```

**Response Stream:**
```
data: {"choices":[{"delta":{"reasoning_content":"Let me think about this query. I need to: 1) Understand what the user is asking..."}}]}

data: {"choices":[{"delta":{"reasoning_content":" 2) Determine which tools are relevant 3) Plan the sequence of tool calls"}}]}

data: {"choices":[{"delta":{"reasoning_content":" Based on my analysis, I should call the rag_search tool first."}}]}

data: {"choices":[{"delta":{"content":"I'll search the knowledge base for relevant information."}}]}

data: {"choices":[{"delta":{"tool_calls":[{"id":"call_1","function":{"name":"rag_search","arguments":"..."}}]}}]}

data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
```

### **Our Implementation**

```kotlin
provider.chatStream(request).collect { chunk ->
    val parsed = provider.extractStreamChunk(chunk)

    // Reasoning stream (thinking process)
    parsed.reasoningDelta?.let { delta ->
        logger.trace { "🧠 Reasoning: $delta" }

        if (properties.thinking.showReasoningTraces) {
            emit(StrategyEvent.ReasoningChunk(
                content = delta,
                metadata = mapOf("model" to "qwen-max")
            ))
        }
    }

    // Content stream (user-facing)
    parsed.contentDelta?.let { delta ->
        logger.debug { "💬 Content: $delta" }
        emit(StrategyEvent.ContentChunk(delta))
    }
}
```

### **Client Experience**

With `loop.streaming.show-reasoning-traces: true`:

```
event: ReasoningTrace
data: {"conversationId":"123","content":"Let me think about this query...","stage":"PLANNING"}

event: ReasoningTrace
data: {"conversationId":"123","content":"I need to search the knowledge base first","stage":"PLANNING"}

event: ToolCallStart
data: {"conversationId":"123","toolName":"rag_search",...}

event: ToolCallResult
data: {"conversationId":"123","result":"Found 3 documents...",...}

event: ReasoningTrace
data: {"conversationId":"123","content":"Based on the search results, I can now formulate an answer","stage":"SYNTHESIS"}

event: ResponseChunk
data: {"conversationId":"123","content":"Based on the knowledge base..."}
```

**UI can display:**
- "thinking" indicator during reasoning
- Expandable "Show reasoning" panel
- Real-time thought process
- Final answer separately

---

## Configuration

### **Enable Hybrid Strategy**

```yaml
# application.yml
loop:
  model-strategy: qwen_hybrid_staged

  thinking:
    timeout-seconds: 120  # Thinking models are slower
    show-reasoning-traces: true  # Log reasoning

  instruct:
    timeout-seconds: 30  # Instruct is fast
    max-retries: 3

  streaming:
    show-reasoning-traces: true  # Stream to client
```

### **Qwen API Keys**

```bash
# .env
QWEN_API_KEY=sk-your-qwen-api-key
QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
QWEN_THINKING_MODEL=qwen-max
QWEN_INSTRUCT_MODEL=qwen-plus
```

---

## Performance Characteristics

### **Token Usage**

| Stage | Model | Avg Tokens | Cost (per 1M) |
|-------|-------|------------|---------------|
| Planning | qwen-max | 500-1000 | $2.00 |
| Execution | qwen-plus | 200-400 | $0.50 |
| Synthesis | qwen-max | 300-600 | $2.00 |
| **Total** | **Hybrid** | **1000-2000** | **~$4.50** |

**Compare to:**
- **qwen-max only**: 1500-2500 tokens, $5-7
- **qwen-plus only**: 800-1200 tokens, $1-2

**Hybrid = Better quality than qwen-plus, cheaper than qwen-max.**

### **Latency**

| Stage | Model | Avg Time | Streaming |
|-------|-------|----------|-----------|
| Planning | qwen-max | 3-5s | ✓ Real-time |
| Execution | qwen-plus | 0.5-1s | ✗ Batch |
| Synthesis | qwen-max | 2-3s | ✓ Real-time |
| **Total** | **Hybrid** | **5-9s** | **Mostly streaming** |

---

## Best Practices

### **1. Use Thinking for Complex Reasoning**

```kotlin
// ✅ Good: Complex multi-step query
"Analyze the last quarter's sales data, identify trends,
 and recommend actions for Q4"
→ qwen_hybrid_staged (planning needed)

// ❌ Overkill: Simple lookup
"What is the capital of France?"
→ qwen_single_instruct (no planning needed)
```

### **2. Stream Reasoning Selectively**

```yaml
# For debugging/development
loop:
  thinking:
    show-reasoning-traces: true  # Log to console
  streaming:
    show-reasoning-traces: false  # Don't send to client

# For production with advanced UI
loop:
  thinking:
    show-reasoning-traces: false  # No logs
  streaming:
    show-reasoning-traces: true  # Send to client UI
```

### **3. Tune Timeouts Per Stage**

```yaml
loop:
  thinking:
    timeout-seconds: 120  # Generous for complex reasoning
  instruct:
    timeout-seconds: 30   # Fast for tool execution
```

### **4. Monitor Token Usage**

```kotlin
// In your monitoring/logging
logger.info {
    "Hybrid iteration complete: " +
    "planning_tokens=$planningTokens, " +
    "execution_tokens=$executionTokens, " +
    "synthesis_tokens=$synthesisTokens, " +
    "total=${planningTokens + executionTokens + synthesisTokens}"
}
```

---

## Advanced: Custom Hybrid Strategies

### **Example: DeepSeek + Qwen Hybrid**

```kotlin
@Component
@ConditionalOnProperty(name = ["loop.model-strategy"], havingValue = "deepseek_qwen_hybrid")
class DeepSeekQwenHybridStrategy(
    private val deepseekProvider: DeepSeekModelProvider,
    private val qwenProvider: QwenModelProvider
) : ModelStrategyExecutor {

    override suspend fun executeIteration(...): Flow<StrategyEvent> = flow {
        // Stage 1: DeepSeek reasoner for planning
        emit(StatusUpdate("Planning with DeepSeek reasoner...", "planning"))
        val plan = planWithDeepSeek(messages, tools)

        if (plan.toolCalls.isNotEmpty()) {
            // Stage 2: Qwen instruct for fast tool execution
            emit(StatusUpdate("Executing with Qwen instruct...", "executing"))
            emit(ToolCallsComplete(plan.toolCalls, plan.content))
        } else {
            // Stage 3: Qwen thinking for synthesis
            emit(StatusUpdate("Synthesizing with Qwen thinking...", "synthesizing"))
            val synthesis = synthesizeWithQwen(messages + plan)
            emit(FinalResponse(synthesis.content, synthesis.tokensUsed))
        }
    }
}
```

### **Example: Conditional Hybrid**

```kotlin
@Component
class SmartHybridStrategy : ModelStrategyExecutor {

    override suspend fun executeIteration(...): Flow<StrategyEvent> = flow {
        // Analyze query complexity
        val complexity = analyzeComplexity(messages.last().content)

        when {
            complexity > 0.8 -> {
                // Very complex → Full hybrid
                emit(StatusUpdate("Complex query detected, using full hybrid..."))
                executeFullHybrid(...)
            }
            complexity > 0.4 -> {
                // Medium → Thinking model only
                emit(StatusUpdate("Medium complexity, using thinking model..."))
                executeThinkingOnly(...)
            }
            else -> {
                // Simple → Instruct model only
                emit(StatusUpdate("Simple query, using instruct model..."))
                executeInstructOnly(...)
            }
        }
    }
}
```

---

## Troubleshooting

### **Issue: No reasoning traces**

**Check:**
```yaml
loop:
  thinking:
    show-reasoning-traces: true  # ← Must be true
  streaming:
    show-reasoning-traces: true  # ← For client streaming
```

**Also check:**
```kotlin
// In strategy
extraParams = mapOf(
    "enableThinking" to true  // ← Must be enabled
)
```

### **Issue: Slow performance**

**Solutions:**
1. Use instruct model for execution stage (already done)
2. Reduce `max_tokens` for planning
3. Use synchronous mode for execution (no streaming overhead)
4. Cache planning results for similar queries

### **Issue: High token usage**

**Solutions:**
1. Limit reasoning tokens:
```kotlin
RequestConfig(
    maxTokens = 2000,  // Limit reasoning output
    extraParams = mapOf("enableThinking" to true)
)
```

2. Use instruct model when thinking not needed
3. Monitor and log token usage per stage

---

## Comparison: Single vs Hybrid

### **Single Strategy (qwen_single_thinking)**

```
┌────────────────────────────────┐
│  Iteration 1: qwen-max         │
│  • Thinks about query          │
│  • Plans tool calls            │
│  • Emits tool calls            │
└────────────────────────────────┘
         ↓ (tools execute)
┌────────────────────────────────┐
│  Iteration 2: qwen-max         │
│  • Thinks about results        │
│  • Synthesizes answer          │
└────────────────────────────────┘

Total: 2 qwen-max calls
Cost: $$
Speed: Slower
Quality: High
```

### **Hybrid Strategy (qwen_hybrid_staged)**

```
┌────────────────────────────────┐
│  Iteration 1:                  │
│  • Planning: qwen-max          │
│  • Execution: (orchestrator)   │
│  • Tools execute in parallel   │
└────────────────────────────────┘
         ↓ (tools execute)
┌────────────────────────────────┐
│  Iteration 2:                  │
│  • Synthesis: qwen-max         │
└────────────────────────────────┘

Total: 2 qwen-max calls (same)
Cost: $$ (similar)
Speed: Faster (parallel tools)
Quality: High
```

**Main benefit**: Better organization, clearer stages, easier to optimize each phase.

---

## Key Takeaways

### **When to Use Hybrid**

✅ **Use hybrid when:**
- Complex multi-step reasoning required
- Tool-heavy workflows
- Quality matters more than cost
- You want visibility into thinking process

❌ **Don't use hybrid when:**
- Simple queries
- Cost-sensitive applications
- Latency-critical (use instruct only)
- No tools needed

### **Qwen Community Recommendations**

1. **Enable thinking** for planning and synthesis
2. **Stream reasoning** to show work in progress
3. **Use instruct** for fast tool execution
4. **Monitor tokens** per stage to optimize

### **Architecture Benefits**

- Each stage is isolated and testable
- Easy to swap models per stage
- Can optimize each stage independently
- Clear separation of concerns

---

## Next Steps

1. **Try it**: Set `model-strategy: qwen_hybrid_staged`
2. **Monitor**: Watch reasoning traces in logs
3. **Tune**: Adjust timeouts and token limits
4. **Experiment**: Try different model combinations
5. **Measure**: Compare quality, cost, and latency vs single-model

**The hybrid strategy is production-ready. Start experimenting!** 🚀
