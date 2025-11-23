# Streaming Guide (SSE Implementation)

**Project**: RAG Orchestrator Service
**Version**: 0.0.1-SNAPSHOT
**Last Updated**: 2025-11-23

---

## Table of Contents

1. [Overview](#overview)
2. [SSE Architecture](#sse-architecture)
3. [Event Types](#event-types)
4. [Implementation](#implementation)
5. [Client Integration](#client-integration)
6. [Error Handling](#error-handling)
7. [Best Practices](#best-practices)
8. [Testing](#testing)
9. [Examples](#examples)

---

## Overview

The RAG Orchestrator uses **Server-Sent Events (SSE)** to stream real-time updates during conversation processing.

### Why SSE?

**Advantages over alternatives**:
- ✅ **Simpler than WebSockets**: No bidirectional complexity
- ✅ **Built into HTTP**: Works with standard infrastructure
- ✅ **Auto-reconnection**: Browsers automatically reconnect
- ✅ **Event-based**: Named events for structured data
- ✅ **Text-based**: Easy to debug and monitor
- ✅ **Works with Spring WebFlux**: Perfect for reactive streams

**When to use SSE**:
- Server → Client streaming (one-way)
- Real-time progress updates
- Long-running operations
- Event notifications

**When NOT to use SSE**:
- Need bidirectional communication → Use WebSockets
- Binary data streaming → Use gRPC or WebSockets
- Very high frequency updates (> 100/sec) → Use WebSockets

---

## SSE Architecture

### Request Flow

```
Client (Browser/App)
    ↓
POST /api/v1/chat/conversations/{id}/messages/stream
    ↓
┌─────────────────────────────────────────────────┐
│         ChatController                          │
│                                                 │
│  • Validate request                             │
│  • Call OrchestratorService.processMessageStream│
│  • Convert Flow<StreamEvent> to Flux<SSE>       │
└─────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────┐
│         OrchestratorService                     │
│                                                 │
│  • Load conversation                            │
│  • Add user message                             │
│  • Perform initial RAG search                   │
│  • Enter tool-calling loop:                     │
│    • Emit StatusUpdate events                   │
│    • Call DeepSeek API                          │
│    • Emit ToolCallStart events                  │
│    • Execute tools                              │
│    • Emit ToolCallResult events                 │
│    • Emit ResponseChunk events                  │
│  • Emit Completed event                         │
└─────────────────────────────────────────────────┘
    ↓ (streaming)
Client receives events in real-time
```

### SSE Format

SSE messages have this structure:

```
event: EventType
data: {"key": "value"}
id: optional-event-id

event: StatusUpdate
data: {"conversationId":"123","status":"Processing...","timestamp":"2025-11-23T10:00:00Z"}

event: Completed
data: {"conversationId":"123","iterationsUsed":2,"tokensUsed":523,"timestamp":"2025-11-23T10:00:05Z"}
```

**Key fields**:
- `event`: Event type (used for client-side filtering)
- `data`: JSON payload
- `id`: Optional unique ID (for reconnection)

---

## Event Types

### StreamEvent Sealed Class

All events extend `StreamEvent`:

```kotlin
sealed class StreamEvent {
    abstract val conversationId: String
    abstract val timestamp: Instant

    data class StatusUpdate(...)
    data class ToolCallStart(...)
    data class ToolCallResult(...)
    data class ResponseChunk(...)
    data class Completed(...)
    data class Error(...)
}
```

---

### 1. StatusUpdate

**Purpose**: Progress updates during processing

**When emitted**:
- Loading conversation
- Adding user message
- Performing RAG search
- Starting iterations
- Any significant status change

**Payload**:
```kotlin
data class StatusUpdate(
    override val conversationId: String,
    val status: String,
    val details: String? = null,
    override val timestamp: Instant = Instant.now()
) : StreamEvent()
```

**Example**:
```
event: StatusUpdate
data: {
  "conversationId": "123e4567-e89b-12d3-a456-426614174000",
  "status": "Performing initial knowledge search...",
  "details": null,
  "timestamp": "2025-11-23T10:00:00Z"
}
```

---

### 2. ToolCallStart

**Purpose**: Notify when a tool is about to be executed

**When emitted**:
- LLM decides to call a tool
- Before tool execution

**Payload**:
```kotlin
data class ToolCallStart(
    override val conversationId: String,
    val toolName: String,
    val toolCallId: String,
    val arguments: Map<String, Any>,
    override val timestamp: Instant = Instant.now()
) : StreamEvent()
```

**Example**:
```
event: ToolCallStart
data: {
  "conversationId": "123e4567-e89b-12d3-a456-426614174000",
  "toolName": "rag_search",
  "toolCallId": "call_abc123",
  "arguments": {
    "query": "What is RAG?",
    "max_results": 5
  },
  "timestamp": "2025-11-23T10:00:01Z"
}
```

---

### 3. ToolCallResult

**Purpose**: Return tool execution results

**When emitted**:
- After tool execution completes
- Whether success or failure

**Payload**:
```kotlin
data class ToolCallResult(
    override val conversationId: String,
    val toolName: String,
    val toolCallId: String,
    val result: String,
    val success: Boolean,
    override val timestamp: Instant = Instant.now()
) : StreamEvent()
```

**Example (success)**:
```
event: ToolCallResult
data: {
  "conversationId": "123e4567-e89b-12d3-a456-426614174000",
  "toolName": "rag_search",
  "toolCallId": "call_abc123",
  "result": "Document: RAG stands for Retrieval-Augmented Generation...\n(Relevance: 0.95)",
  "success": true,
  "timestamp": "2025-11-23T10:00:02Z"
}
```

**Example (failure)**:
```
event: ToolCallResult
data: {
  "conversationId": "123e4567-e89b-12d3-a456-426614174000",
  "toolName": "rag_search",
  "toolCallId": "call_abc123",
  "result": "",
  "success": false,
  "timestamp": "2025-11-23T10:00:02Z"
}
```

---

### 4. ResponseChunk

**Purpose**: Stream final AI response to user

**When emitted**:
- When LLM generates final response
- No more tool calls needed

**Payload**:
```kotlin
data class ResponseChunk(
    override val conversationId: String,
    val content: String,
    override val timestamp: Instant = Instant.now()
) : StreamEvent()
```

**Example**:
```
event: ResponseChunk
data: {
  "conversationId": "123e4567-e89b-12d3-a456-426614174000",
  "content": "Based on the knowledge base, RAG (Retrieval-Augmented Generation) is a technique that combines information retrieval with large language models...",
  "timestamp": "2025-11-23T10:00:03Z"
}
```

**Note**: For truly streaming word-by-word responses, this could be emitted multiple times with incremental content.

---

### 5. Completed

**Purpose**: Signal end of processing

**When emitted**:
- All processing complete
- Final statistics available

**Payload**:
```kotlin
data class Completed(
    override val conversationId: String,
    val iterationsUsed: Int,
    val tokensUsed: Int,
    override val timestamp: Instant = Instant.now()
) : StreamEvent()
```

**Example**:
```
event: Completed
data: {
  "conversationId": "123e4567-e89b-12d3-a456-426614174000",
  "iterationsUsed": 2,
  "tokensUsed": 1523,
  "timestamp": "2025-11-23T10:00:05Z"
}
```

---

### 6. Error

**Purpose**: Report errors during processing

**When emitted**:
- Any exception during processing
- Tool execution failures
- External API errors

**Payload**:
```kotlin
data class Error(
    override val conversationId: String,
    val error: String,
    val details: String? = null,
    override val timestamp: Instant = Instant.now()
) : StreamEvent()
```

**Example**:
```
event: Error
data: {
  "conversationId": "123e4567-e89b-12d3-a456-426614174000",
  "error": "Tool execution failed",
  "details": "ChromaDB connection timeout after 30 seconds",
  "timestamp": "2025-11-23T10:00:03Z"
}
```

---

## Implementation

### Controller Layer

**ChatController.kt**

```kotlin
@RestController
@RequestMapping("/api/v1/chat")
class ChatController(
    private val orchestratorService: OrchestratorService,
    private val contextManager: ContextManager,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    @PostMapping(
        path = ["/conversations/{conversationId}/messages/stream"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun sendMessageStream(
        @PathVariable conversationId: String,
        @Valid @RequestBody request: ChatRequest
    ): Flux<ServerSentEvent<String>> {

        logger.info { "Streaming message for conversation: $conversationId" }

        return orchestratorService.processMessageStream(conversationId, request.message)
            .map { event ->
                // Convert StreamEvent to SSE
                ServerSentEvent.builder<String>()
                    .event(event.javaClass.simpleName)  // Event type
                    .data(objectMapper.writeValueAsString(event))  // JSON payload
                    .build()
            }
            .asFlux()  // Convert Kotlin Flow to Reactor Flux
            .doOnError { error ->
                logger.error(error) { "Error streaming for conversation: $conversationId" }
            }
            .doOnComplete {
                logger.info { "Streaming completed for conversation: $conversationId" }
            }
    }
}
```

**Key points**:
- `produces = [MediaType.TEXT_EVENT_STREAM_VALUE]`: Set Content-Type to `text/event-stream`
- `ServerSentEvent.builder()`: Build SSE-formatted messages
- `.asFlux()`: Convert Kotlin Flow to Reactor Flux (required by Spring WebFlux)

---

### Service Layer

**OrchestratorService.kt**

```kotlin
@Service
class OrchestratorService(
    private val contextManager: ContextManager,
    private val toolRegistry: ToolRegistry,
    private val deepSeekClient: DeepSeekClient,
    private val properties: LoopProperties
) {
    private val logger = KotlinLogging.logger {}

    suspend fun processMessageStream(
        conversationId: String,
        userMessage: String
    ): Flow<StreamEvent> = flow {

        try {
            // Step 1: Load conversation
            emit(StreamEvent.StatusUpdate(
                conversationId = conversationId,
                status = "Loading conversation..."
            ))

            var context = contextManager.loadConversation(conversationId)

            // Step 2: Add user message
            emit(StreamEvent.StatusUpdate(
                conversationId = conversationId,
                status = "Adding user message..."
            ))

            val userMsg = Message(role = MessageRole.USER, content = userMessage)
            context = contextManager.addMessage(conversationId, userMsg)

            // Step 3: Initial RAG search
            emit(StreamEvent.StatusUpdate(
                conversationId = conversationId,
                status = "Performing initial knowledge search..."
            ))

            val ragResults = performRAGSearch(userMessage)

            // Build context
            val messages = mutableListOf<Message>()
            messages.addAll(context.messages)
            messages.add(Message(
                role = MessageRole.SYSTEM,
                content = "Knowledge base results:\n$ragResults"
            ))

            // Step 4: Tool-calling loop
            var iteration = 0
            var totalTokens = 0
            var continueLoop = true

            while (continueLoop && iteration < properties.maxIterations) {
                iteration++

                emit(StreamEvent.StatusUpdate(
                    conversationId = conversationId,
                    status = "Iteration $iteration..."
                ))

                // Call LLM
                val request = buildDeepSeekRequest(messages)
                val response = deepSeekClient.chat(request).awaitSingle()

                val assistantMessage = response.choices[0].message
                totalTokens += response.usage?.total_tokens ?: 0

                // Check for tool calls
                if (assistantMessage.tool_calls != null && assistantMessage.tool_calls.isNotEmpty()) {

                    // Add assistant message
                    messages.add(Message(
                        role = MessageRole.ASSISTANT,
                        content = assistantMessage.content ?: "",
                        toolCalls = assistantMessage.tool_calls.map { it.toToolCall() }
                    ))

                    // Execute each tool
                    for (toolCall in assistantMessage.tool_calls) {

                        // Emit ToolCallStart
                        emit(StreamEvent.ToolCallStart(
                            conversationId = conversationId,
                            toolName = toolCall.function.name,
                            toolCallId = toolCall.id,
                            arguments = parseArguments(toolCall.function.arguments)
                        ))

                        // Execute tool
                        val result = toolRegistry.executeTool(toolCall.toToolCall())

                        // Emit ToolCallResult
                        emit(StreamEvent.ToolCallResult(
                            conversationId = conversationId,
                            toolName = result.toolName,
                            toolCallId = result.toolCallId,
                            result = result.result,
                            success = result.success
                        ))

                        // Add result to context
                        messages.add(Message(
                            role = MessageRole.TOOL,
                            content = result.result,
                            toolCallId = result.toolCallId
                        ))

                        // Update stats
                        context.conversation.toolCallsCount++
                    }

                } else {
                    // No tool calls - final response
                    continueLoop = false

                    val finalContent = assistantMessage.content ?: ""
                    messages.add(Message(role = MessageRole.ASSISTANT, content = finalContent))

                    // Save assistant message
                    contextManager.addMessage(
                        conversationId,
                        Message(role = MessageRole.ASSISTANT, content = finalContent)
                    )

                    // Emit response
                    emit(StreamEvent.ResponseChunk(
                        conversationId = conversationId,
                        content = finalContent
                    ))
                }
            }

            // Step 5: Update stats and complete
            context.conversation.totalTokens += totalTokens
            contextManager.saveConversation(context)

            emit(StreamEvent.Completed(
                conversationId = conversationId,
                iterationsUsed = iteration,
                tokensUsed = totalTokens
            ))

        } catch (e: Exception) {
            logger.error(e) { "Error processing message for conversation $conversationId" }

            emit(StreamEvent.Error(
                conversationId = conversationId,
                error = e.message ?: "Unknown error",
                details = e.stackTraceToString()
            ))
        }
    }

    // Helper methods...
    private suspend fun performRAGSearch(query: String): String { /* ... */ }
    private fun buildDeepSeekRequest(messages: List<Message>): DeepSeekChatRequest { /* ... */ }
    private fun parseArguments(json: String): Map<String, Any> { /* ... */ }
}
```

**Key points**:
- `flow { ... }`: Kotlin coroutine Flow for streaming
- `emit(event)`: Send event to stream
- `try/catch`: Always wrap in error handling, emit Error event on failure

---

## Client Integration

### JavaScript (Browser)

Using native `EventSource` API:

```javascript
const conversationId = "123e4567-e89b-12d3-a456-426614174000";
const url = `http://localhost:8080/api/v1/chat/conversations/${conversationId}/messages/stream`;

// Create EventSource
const eventSource = new EventSource(url);

// Listen for specific event types
eventSource.addEventListener('StatusUpdate', (e) => {
    const data = JSON.parse(e.data);
    console.log('Status:', data.status);
    updateStatusBar(data.status);
});

eventSource.addEventListener('ToolCallStart', (e) => {
    const data = JSON.parse(e.data);
    console.log(`Calling tool: ${data.toolName}`);
    showToolExecution(data.toolName, data.arguments);
});

eventSource.addEventListener('ToolCallResult', (e) => {
    const data = JSON.parse(e.data);
    if (data.success) {
        console.log(`Tool ${data.toolName} succeeded`);
    } else {
        console.error(`Tool ${data.toolName} failed`);
    }
});

eventSource.addEventListener('ResponseChunk', (e) => {
    const data = JSON.parse(e.data);
    console.log('Response:', data.content);
    appendToChat(data.content);
});

eventSource.addEventListener('Completed', (e) => {
    const data = JSON.parse(e.data);
    console.log(`Completed in ${data.iterationsUsed} iterations`);
    eventSource.close();
});

eventSource.addEventListener('Error', (e) => {
    const data = JSON.parse(e.data);
    console.error('Error:', data.error);
    showError(data.error);
    eventSource.close();
});

// Handle connection errors
eventSource.onerror = (error) => {
    console.error('Connection error:', error);
    eventSource.close();
};
```

---

### Python

Using `sseclient` library:

```python
import requests
import json
import sseclient

conversation_id = "123e4567-e89b-12d3-a456-426614174000"
url = f"http://localhost:8080/api/v1/chat/conversations/{conversation_id}/messages/stream"

# POST with streaming
response = requests.post(url, json={
    "message": "What is RAG?"
}, stream=True)

# Parse SSE stream
client = sseclient.SSEClient(response)

for event in client.events():
    data = json.loads(event.data)

    if event.event == "StatusUpdate":
        print(f"Status: {data['status']}")

    elif event.event == "ToolCallStart":
        print(f"Calling tool: {data['toolName']}")

    elif event.event == "ToolCallResult":
        if data['success']:
            print(f"Tool result: {data['result'][:100]}...")
        else:
            print(f"Tool failed!")

    elif event.event == "ResponseChunk":
        print(f"Response: {data['content']}")

    elif event.event == "Completed":
        print(f"Completed! Used {data['iterationsUsed']} iterations")
        break

    elif event.event == "Error":
        print(f"Error: {data['error']}")
        break
```

---

### Curl

```bash
curl -N -X POST http://localhost:8080/api/v1/chat/conversations/123e4567-e89b-12d3-a456-426614174000/messages/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "What is RAG?"}' \
  --no-buffer
```

**Output**:
```
event: StatusUpdate
data: {"conversationId":"...","status":"Loading conversation...","timestamp":"..."}

event: StatusUpdate
data: {"conversationId":"...","status":"Performing initial knowledge search...","timestamp":"..."}

event: ToolCallStart
data: {"conversationId":"...","toolName":"rag_search","toolCallId":"call_abc","arguments":{...},"timestamp":"..."}

event: ToolCallResult
data: {"conversationId":"...","toolName":"rag_search","toolCallId":"call_abc","result":"...","success":true,"timestamp":"..."}

event: ResponseChunk
data: {"conversationId":"...","content":"Based on the knowledge base...","timestamp":"..."}

event: Completed
data: {"conversationId":"...","iterationsUsed":2,"tokensUsed":1523,"timestamp":"..."}
```

---

## Error Handling

### Server-Side

Always catch exceptions and emit Error events:

```kotlin
suspend fun processMessageStream(conversationId: String, userMessage: String): Flow<StreamEvent> = flow {
    try {
        // Processing logic...

    } catch (e: DeepSeekApiException) {
        emit(StreamEvent.Error(
            conversationId = conversationId,
            error = "DeepSeek API error",
            details = e.message
        ))

    } catch (e: ChromaDBException) {
        emit(StreamEvent.Error(
            conversationId = conversationId,
            error = "ChromaDB error",
            details = e.message
        ))

    } catch (e: TimeoutException) {
        emit(StreamEvent.Error(
            conversationId = conversationId,
            error = "Operation timeout",
            details = "Processing took longer than ${properties.timeout} seconds"
        ))

    } catch (e: Exception) {
        logger.error(e) { "Unexpected error in stream" }
        emit(StreamEvent.Error(
            conversationId = conversationId,
            error = "Unexpected error",
            details = e.message
        ))
    }
}
```

### Client-Side

Handle connection errors and Error events:

```javascript
eventSource.addEventListener('Error', (e) => {
    const data = JSON.parse(e.data);
    showError(`Error: ${data.error}. Details: ${data.details}`);
    eventSource.close();
});

eventSource.onerror = (error) => {
    console.error('Connection error:', error);
    showError('Lost connection to server');

    // Optionally retry
    setTimeout(() => {
        reconnect();
    }, 5000);
};
```

---

## Best Practices

### 1. Always Emit Completed or Error

Ensure clients know when to close the stream:

```kotlin
try {
    // Processing...
    emit(StreamEvent.Completed(...))
} catch (e: Exception) {
    emit(StreamEvent.Error(...))
}
```

### 2. Keep Events Small

Don't send huge payloads:

✅ **Good**:
```kotlin
emit(StreamEvent.ResponseChunk(
    conversationId = id,
    content = "Response here..."
))
```

❌ **Bad**:
```kotlin
emit(StreamEvent.ResponseChunk(
    conversationId = id,
    content = largeDocument  // 50KB+ string
))
```

### 3. Use Structured Event Names

Use clear, descriptive names:

✅ **Good**: `StatusUpdate`, `ToolCallStart`, `ResponseChunk`
❌ **Bad**: `Event1`, `Update`, `Data`

### 4. Include Timestamps

Always include timestamps for debugging:

```kotlin
data class StreamEvent(..., val timestamp: Instant = Instant.now())
```

### 5. Handle Backpressure

Use Flow to handle backpressure automatically:

```kotlin
flow {
    // Slow producer
    repeat(1000) {
        emit(event)
        delay(10)  // Backpressure handled automatically
    }
}
```

### 6. Set Appropriate Timeouts

Configure request timeout:

```yaml
spring:
  webflux:
    timeout: 120000  # 2 minutes
```

### 7. Monitor Stream Health

Track metrics:

```kotlin
@Timed("stream.duration")
fun sendMessageStream(...): Flux<ServerSentEvent<String>> {
    // Implementation
}
```

### 8. Test with Slow Connections

Simulate slow clients:

```bash
curl -N --limit-rate 100 http://localhost:8080/api/v1/chat/.../stream
```

---

## Testing

### Unit Test (Service Layer)

```kotlin
@Test
fun `should emit all event types during processing`() = runBlocking {
    val events = mutableListOf<StreamEvent>()

    orchestratorService.processMessageStream("conv-123", "Test message")
        .collect { event ->
            events.add(event)
        }

    // Verify event sequence
    assertTrue(events[0] is StreamEvent.StatusUpdate)
    assertTrue(events.any { it is StreamEvent.ToolCallStart })
    assertTrue(events.any { it is StreamEvent.ToolCallResult })
    assertTrue(events.any { it is StreamEvent.ResponseChunk })
    assertTrue(events.last() is StreamEvent.Completed)
}
```

### Integration Test (Controller Layer)

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatControllerIntegrationTest {

    @Autowired
    private lateinit var webClient: WebTestClient

    @Test
    fun `should stream events via SSE`() {
        val request = ChatRequest(message = "Test message")

        val events = webClient.post()
            .uri("/api/v1/chat/conversations/conv-123/messages/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk
            .returnResult(ServerSentEvent::class.java)
            .responseBody
            .collectList()
            .block()

        assertNotNull(events)
        assertTrue(events.isNotEmpty())
        assertEquals("StatusUpdate", events[0].event())
    }
}
```

---

## Examples

### Complete Streaming Flow

```kotlin
// Server: OrchestratorService.kt
suspend fun processMessageStream(conversationId: String, userMessage: String): Flow<StreamEvent> = flow {
    emit(StreamEvent.StatusUpdate(conversationId, "Starting..."))

    // Load conversation
    val context = contextManager.loadConversation(conversationId)

    emit(StreamEvent.StatusUpdate(conversationId, "Processing message..."))

    // Tool-calling loop
    var iteration = 0
    while (iteration < 10) {
        iteration++

        // Call LLM
        val response = deepSeekClient.chat(request).awaitSingle()

        // Check for tool calls
        if (response.hasToolCalls()) {
            for (toolCall in response.toolCalls) {
                emit(StreamEvent.ToolCallStart(
                    conversationId, toolCall.name, toolCall.id, toolCall.args
                ))

                val result = executeTool(toolCall)

                emit(StreamEvent.ToolCallResult(
                    conversationId, result.name, result.id, result.output, result.success
                ))
            }
        } else {
            // Final response
            emit(StreamEvent.ResponseChunk(conversationId, response.content))
            break
        }
    }

    emit(StreamEvent.Completed(conversationId, iteration, totalTokens))
}
```

```javascript
// Client: JavaScript
const eventSource = new EventSource(url);

eventSource.addEventListener('StatusUpdate', (e) => {
    const {status} = JSON.parse(e.data);
    document.getElementById('status').innerText = status;
});

eventSource.addEventListener('ToolCallStart', (e) => {
    const {toolName} = JSON.parse(e.data);
    addToolBadge(toolName);
});

eventSource.addEventListener('ResponseChunk', (e) => {
    const {content} = JSON.parse(e.data);
    appendMessage('assistant', content);
});

eventSource.addEventListener('Completed', (e) => {
    const {iterationsUsed} = JSON.parse(e.data);
    console.log(`Done in ${iterationsUsed} iterations`);
    eventSource.close();
});
```

---

**Documentation complete!** See [IMPLEMENTATION_SPEC.md](IMPLEMENTATION_SPEC.md) to start implementing the code.
