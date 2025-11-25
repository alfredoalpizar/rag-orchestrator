# Implementation Specification

A detailed technical specification of the RAG Orchestrator's agentic loop implementation.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                         CLIENT                               │
│  (HTTP/SSE Requests)                                         │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                    CONTROLLER LAYER                          │
│  • ChatController (conversations + SSE streaming)            │
│  • AgentController (tools listing)                           │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                  ORCHESTRATOR SERVICE                        │
│  • Model-agnostic agentic loop coordination                  │
│  • Manages iterations (max 10)                               │
│  • Delegates to ModelStrategyExecutor                        │
└─────────────────────────────────────────────────────────────┘
           ↓                ↓                ↓
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│   CONTEXT    │  │   STRATEGY   │  │     TOOL     │
│   MANAGER    │  │   EXECUTOR   │  │   REGISTRY   │
│              │  │              │  │              │
│ • History    │  │ • DeepSeek   │  │ • RAG tool   │
│ • Rolling    │  │ • Qwen       │  │ • Custom     │
│   window     │  │ • Future...  │  │   functions  │
└──────────────┘  └──────────────┘  └──────────────┘
                         ↓
              ┌──────────────────┐
              │  MODEL PROVIDER  │
              │                  │
              │ • DeepSeek API   │
              │ • Qwen API       │
              └──────────────────┘
```

---

## The Agentic Loop

The core of this system is the **agentic loop** - an iterative process where the LLM can invoke tools to gather information before producing a final response.

### Loop Flow

```
1. User sends message
2. Load conversation context (history)
3. Perform initial RAG search
4. Enter agentic loop:
   ┌─────────────────────────────────────────────────┐
   │  while (shouldContinue && iteration < max):    │
   │                                                 │
   │    a. Send messages + tools to LLM             │
   │    b. LLM returns response                     │
   │                                                 │
   │    c. IF response contains tool_calls:         │
   │         - Execute each tool                    │
   │         - Add results to messages              │
   │         - shouldContinue = true                │
   │                                                 │
   │    d. ELSE (no tool_calls):                    │
   │         - This is the final answer             │
   │         - shouldContinue = false               │
   │         - Save and return response             │
   └─────────────────────────────────────────────────┘
5. Emit Completed event with stats
```

### How the Loop Decides to Finalize

The loop termination is **model-driven**, not rule-based:

1. **The LLM decides** - When the model has sufficient information to answer, it returns a response without `tool_calls`
2. **No explicit confidence scores** - The model's decision to stop using tools IS the signal
3. **Max iterations as safety** - Prevents infinite loops (default: 10)

The model naturally learns to:
- Use tools when it needs more information
- Answer directly when it has enough context
- Stop after gathering sufficient data

**There is no explicit system prompt instructing the model when to finalize.** The model is given:
- Conversation history
- RAG results (as a system message with retrieved context)
- Available tool definitions

The model's inherent reasoning determines when to use tools vs. respond.

### Code: Loop in OrchestratorService

```kotlin
// Simplified from OrchestratorService.kt
while (continueLoop && iteration < properties.maxIterations) {
    iteration++

    strategyExecutor.executeIteration(messages, tools, iterationContext)
        .collect { strategyEvent ->
            when (strategyEvent) {
                is StrategyEvent.ToolCallsComplete -> {
                    // Execute tools, add results to messages
                    // Loop will continue
                }

                is StrategyEvent.FinalResponse -> {
                    // No tool calls - this is the answer
                    // Save and emit response
                }

                is StrategyEvent.IterationComplete -> {
                    // shouldContinue = true if tool calls were made
                    // shouldContinue = false if final response
                    continueLoop = strategyEvent.shouldContinue
                }
            }
        }
}
```

---

## Model Strategy Pattern

The orchestrator uses a **Strategy Pattern** to support multiple LLM providers without changing the core loop logic.

### Strategy Interface

```kotlin
interface ModelStrategyExecutor {
    suspend fun executeIteration(
        messages: List<Message>,
        tools: List<*>,
        iterationContext: IterationContext
    ): Flow<StrategyEvent>

    fun getStrategyMetadata(): StrategyMetadata
}
```

### Available Strategies

| Strategy | Config Value | Model | Reasoning Traces |
|----------|-------------|-------|------------------|
| DeepSeekSingleStrategy | `deepseek_single` | deepseek-chat | No |
| QwenSingleThinkingStrategy | `qwen_single_thinking` | qwen-max | Yes |
| QwenSingleInstructStrategy | `qwen_single_instruct` | qwen-plus | No |

### Strategy Selection

Strategies are Spring beans with `@ConditionalOnProperty`:

```kotlin
@Component
@ConditionalOnProperty(
    prefix = "loop",
    name = ["model-strategy"],
    havingValue = "deepseek_single",
    matchIfMissing = true  // Default strategy
)
class DeepSeekSingleStrategy(...) : ModelStrategyExecutor
```

Configure in `application.yml`:

```yaml
loop:
  model-strategy: deepseek_single  # or qwen_single_thinking, qwen_single_instruct
```

---

## Strategy Events

Strategies emit events via Kotlin Flow. The OrchestratorService transforms these into client-facing StreamEvents.

### StrategyEvent Types

```kotlin
sealed class StrategyEvent {
    // Progressive content (streaming mode)
    data class ContentChunk(val content: String)

    // Reasoning traces (thinking models only)
    data class ReasoningChunk(val content: String, val metadata: Map<String, Any>)

    // Single tool call detected (streaming)
    data class ToolCallDetected(val toolCall: ToolCall, val toolCallId: String)

    // All tool calls at once (synchronous mode)
    data class ToolCallsComplete(val toolCalls: List<ToolCall>, val assistantContent: String?)

    // Final answer (no tool calls) - signals loop should end
    data class FinalResponse(val content: String, val tokensUsed: Int)

    // Iteration complete marker
    data class IterationComplete(val tokensUsed: Int, val shouldContinue: Boolean)

    // Status updates
    data class StatusUpdate(val status: String, val phase: String?)
}
```

### Event Flow Diagram

```
Strategy (internal)              →    OrchestratorService    →    Client (SSE)
──────────────────────────────────────────────────────────────────────────────
ContentChunk("Hello...")         →    transform              →    ResponseChunk
ToolCallDetected(rag_search)     →    execute tool           →    ToolCallStart + ToolCallResult
FinalResponse("Answer is...")    →    save to context        →    ResponseChunk
IterationComplete(continue=false)→    exit loop              →    Completed
```

---

## Streaming Modes

Strategies support two execution modes:

### PROGRESSIVE (Chat UI)

Used for SSE streaming to web clients:
- Emits `ContentChunk` as tokens arrive
- Emits `ToolCallDetected` as tool calls are identified
- Emits `ReasoningChunk` for thinking models
- Real-time updates to the user

### FINAL_ONLY (Internal Services)

Used for synchronous operations:
- Waits for complete response
- Emits `ToolCallsComplete` with all tool calls
- Emits `FinalResponse` with complete content
- More efficient for batch operations

```kotlin
data class IterationContext(
    val conversationId: String,
    val iteration: Int,
    val maxIterations: Int,
    val streamingMode: StreamingMode  // PROGRESSIVE or FINAL_ONLY
)
```

---

## Tool System

### Tool Interface

```kotlin
interface Tool {
    val name: String
    val description: String
    val parameters: Map<String, Any>  // JSON Schema

    suspend fun execute(arguments: Map<String, Any>): ToolResult
}
```

### Tool Registration

Tools are auto-discovered via Spring:

```kotlin
@Service
class ToolRegistry(tools: List<Tool>) {
    private val toolMap = tools.associateBy { it.name }

    fun getToolDefinitions(): List<ToolDefinition>
    suspend fun executeTool(toolCall: ToolCall): ToolResult
}
```

### RAG Tool

The built-in RAG tool queries ChromaDB:

```kotlin
@Component
class RAGTool(private val chromaDBClient: ChromaDBClient) : Tool {
    override val name = "rag_search"
    override val description = "Search the knowledge base for relevant information"

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val query = arguments["query"] as String
        val results = chromaDBClient.query(query).awaitSingle()
        return ToolResult(result = formatResults(results), success = true)
    }
}
```

---

## Context Management

### Storage Modes

The system supports two storage modes, configured via `conversation.storage-mode`:

| Mode | Use Case | Persistence |
|------|----------|-------------|
| `in-memory` | Development, quick start | None (lost on restart) |
| `database` | Production, audit trails | PostgreSQL |

### Storage Abstraction

```kotlin
interface ConversationStorage {
    fun saveConversation(conversation: Conversation): Conversation
    fun findConversationById(id: String): Conversation?
    fun findConversationsByCallerId(callerId: String, limit: Int): List<Conversation>
    fun saveMessage(message: ConversationMessage): ConversationMessage
    fun findMessagesByConversationId(conversationId: String): List<ConversationMessage>
}
```

Implementations:
- `InMemoryConversationStorage` - ConcurrentHashMap-based, zero setup
- `DatabaseConversationStorage` - JPA repositories, requires PostgreSQL

### Rolling Window

To prevent unbounded context growth, messages are windowed:

```kotlin
val windowSize = properties.rollingWindowSize  // default: 20
val recentMessages = if (allMessages.size > windowSize) {
    allMessages.takeLast(windowSize)
} else {
    allMessages
}
```

### Database Schema (when using database mode)

```sql
CREATE TABLE conversation_messages (
    message_id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,  -- user, assistant, tool, system
    content TEXT NOT NULL,
    tool_call_id VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    token_count INT
);
```

---

## Model Providers

Providers abstract the LLM API differences:

```kotlin
interface ModelProvider {
    fun buildRequest(messages: List<Message>, tools: List<*>, config: RequestConfig): Any
    suspend fun chat(request: Any): Any
    fun chatStream(request: Any): Flow<Any>
    fun extractMessage(response: Any): ExtractedMessage
    fun extractStreamChunk(chunk: Any): StreamChunkData
}
```

### DeepSeekModelProvider

- Endpoint: `https://api.deepseek.com/chat/completions`
- Models: `deepseek-chat`, `deepseek-reasoner`
- Tool format: OpenAI-compatible

### QwenModelProvider

- Endpoint: `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
- Models: `qwen-max` (thinking), `qwen-plus` (instruct)
- Supports `reasoning_content` field for thinking traces

---

## Adding a New Model Strategy

1. Create provider in `service/orchestrator/provider/`:

```kotlin
@Component
class NewModelProvider(...) : ModelProvider { ... }
```

2. Create strategy in `service/orchestrator/strategy/impl/`:

```kotlin
@Component
@ConditionalOnProperty(
    prefix = "loop",
    name = ["model-strategy"],
    havingValue = "new_strategy"
)
class NewModelStrategy(
    private val provider: NewModelProvider,
    private val properties: LoopProperties
) : ModelStrategyExecutor {

    override suspend fun executeIteration(...): Flow<StrategyEvent> = flow {
        // Call provider
        // Emit StrategyEvents
        // Set shouldContinue based on tool_calls presence
    }
}
```

3. Add config enum in `LoopProperties`:

```kotlin
enum class ModelStrategy {
    DEEPSEEK_SINGLE,
    QWEN_SINGLE_THINKING,
    QWEN_SINGLE_INSTRUCT,
    NEW_STRATEGY  // Add here
}
```

See [STRATEGY_IMPLEMENTATION_GUIDE.md](STRATEGY_IMPLEMENTATION_GUIDE.md) for detailed instructions.

---

## Key Design Decisions

### Why Model-Driven Termination?

The loop terminates when the LLM stops requesting tools. This is intentional:

1. **Simpler logic** - No complex heuristics or confidence thresholds
2. **Model intelligence** - Modern LLMs naturally know when they have enough info
3. **Flexibility** - Works across different models without tuning
4. **Safety** - Max iterations prevents runaway loops

### Why Strategy Pattern?

1. **Multi-model support** - Easy to add new providers
2. **Isolation** - Strategies are stateless and testable
3. **Configuration-driven** - Switch models via config, not code
4. **Progressive enhancement** - Add reasoning traces without breaking existing strategies

### Why Kotlin Flow?

1. **Streaming native** - Natural fit for SSE
2. **Backpressure** - Handles slow consumers
3. **Composable** - Easy to transform events
4. **Coroutine integration** - Works with suspend functions

---

## File Structure

```
src/main/kotlin/com/alfredoalpizar/rag/
├── controller/
│   ├── chat/ChatController.kt          # Conversation endpoints
│   ├── agent/AgentController.kt        # Tool listing
│   └── HealthController.kt             # Health check
├── service/
│   ├── orchestrator/
│   │   ├── OrchestratorService.kt      # Main loop coordinator
│   │   ├── strategy/
│   │   │   ├── ModelStrategyExecutor.kt
│   │   │   ├── StrategyEvent.kt
│   │   │   ├── StrategyMetadata.kt
│   │   │   └── impl/
│   │   │       ├── DeepSeekSingleStrategy.kt
│   │   │       ├── QwenSingleThinkingStrategy.kt
│   │   │       └── QwenSingleInstructStrategy.kt
│   │   └── provider/
│   │       ├── ModelProvider.kt
│   │       ├── DeepSeekModelProvider.kt
│   │       └── QwenModelProvider.kt
│   ├── context/ContextManager.kt       # Conversation history
│   ├── tool/
│   │   ├── Tool.kt
│   │   ├── ToolRegistry.kt
│   │   └── RAGTool.kt
│   └── finalizer/FinalizerStrategy.kt
├── client/
│   ├── deepseek/DeepSeekClient.kt
│   ├── qwen/QwenClient.kt
│   └── chromadb/ChromaDBClient.kt
├── model/
│   ├── domain/                         # Entities
│   ├── request/                        # DTOs
│   └── response/                       # DTOs + StreamEvent
└── config/
    ├── LoopProperties.kt
    └── ConversationProperties.kt
```
