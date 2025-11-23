# Implementation Specification

**Project**: RAG Orchestrator Service
**Version**: 0.0.1-SNAPSHOT
**Last Updated**: 2025-11-23

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Data Models](#data-models)
3. [External Clients](#external-clients)
4. [Repository Layer](#repository-layer)
5. [Service Layer](#service-layer)
6. [Controller Layer](#controller-layer)
7. [Error Handling](#error-handling)
8. [Implementation Order](#implementation-order)

---

## Architecture Overview

### System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         CLIENT                               │
│  (HTTP/SSE Requests)                                         │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                    CONTROLLER LAYER                          │
│  • ChatController (conversation endpoints + SSE streaming)   │
│  • AgentController (management endpoints)                    │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                   ORCHESTRATOR SERVICE                       │
│  • Main conversation loop coordinator                        │
│  • Manages tool-calling iterations (max 10)                  │
│  • Streams events via SSE                                    │
└─────────────────────────────────────────────────────────────┘
           ↓                ↓                ↓
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│   CONTEXT    │  │     TOOL     │  │  FINALIZER   │
│   MANAGER    │  │   REGISTRY   │  │  STRATEGY    │
│              │  │              │  │              │
│ • History    │  │ • RAG tools  │  │ • Format     │
│ • Rolling    │  │ • Custom     │  │   response   │
│   window     │  │   functions  │  │ • Post-      │
│ • Token      │  │ • Discovery  │  │   process    │
│   tracking   │  │              │  │              │
└──────────────┘  └──────────────┘  └──────────────┘
      ↓                 ↓
┌──────────────┐  ┌──────────────┐
│ REPOSITORY   │  │   CLIENTS    │
│              │  │              │
│ • SQL        │  │ • DeepSeek   │
│ • In-Memory  │  │ • ChromaDB   │
└──────────────┘  └──────────────┘
      ↓                 ↓
┌──────────────┐  ┌──────────────┐
│  SYBASE ASE  │  │  EXTERNAL    │
│  DATABASE    │  │  APIs        │
└──────────────┘  └──────────────┘
```

### Request Flow Sequence

```
1. User Request → ChatController.sendMessage()
2. Controller → OrchestratorService.processMessage()
3. Orchestrator → ContextManager.loadConversation()
4. Orchestrator → ToolRegistry.getRAGTool()
5. RAGTool → ChromaDBClient.query()
6. Orchestrator enters loop (max 10 iterations):
   a. Build context with history + tool results
   b. DeepSeekClient.chat() (streaming)
   c. Parse response for tool calls
   d. If tool calls → ToolRegistry.execute()
   e. Add results to context
   f. Repeat or break
7. Orchestrator → FinalizerStrategy.format()
8. Stream response via SSE
9. ContextManager.saveConversation()
```

---

## Data Models

### Package: `com.alfredoalpizar.rag.model`

#### Domain Entities (`domain/`)

**Conversation.kt** - Main conversation entity
```kotlin
@Entity
@Table(name = "conversations")
data class Conversation(
    @Id
    @Column(name = "conversation_id", length = 36)
    val conversationId: String = UUID.randomUUID().toString(),

    @Column(name = "caller_id", length = 100, nullable = false)
    val callerId: String,

    @Column(name = "user_id", length = 100)
    val userId: String?,

    @Column(name = "account_id", length = 100)
    val accountId: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "last_message_at")
    var lastMessageAt: Instant? = null,

    @Column(name = "message_count")
    var messageCount: Int = 0,

    @Column(name = "tool_calls_count")
    var toolCallsCount: Int = 0,

    @Column(name = "total_tokens")
    var totalTokens: Int = 0,

    @Column(name = "status", length = 20)
    var status: ConversationStatus = ConversationStatus.ACTIVE,

    @Column(name = "s3_key", length = 255)
    var s3Key: String? = null,

    @Column(name = "metadata", columnDefinition = "TEXT")
    var metadata: String? = null,

    @OneToMany(mappedBy = "conversation", cascade = [CascadeType.ALL], orphanRemoval = true)
    val messages: MutableList<ConversationMessage> = mutableListOf()
)

enum class ConversationStatus {
    ACTIVE, ARCHIVED, DELETED
}
```

**ConversationMessage.kt** - Individual messages
```kotlin
@Entity
@Table(name = "conversation_messages")
data class ConversationMessage(
    @Id
    @Column(name = "message_id", length = 36)
    val messageId: String = UUID.randomUUID().toString(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    val conversation: Conversation,

    @Column(name = "role", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    val role: MessageRole,

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    val content: String,

    @Column(name = "tool_call_id", length = 100)
    val toolCallId: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "token_count")
    val tokenCount: Int? = null
)

enum class MessageRole {
    USER, ASSISTANT, TOOL, SYSTEM
}
```

**Message.kt** - In-memory message representation (for processing)
```kotlin
data class Message(
    val role: MessageRole,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null
)

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

data class FunctionCall(
    val name: String,
    val arguments: String // JSON string
)
```

**ToolResult.kt** - Result from tool execution
```kotlin
data class ToolResult(
    val toolCallId: String,
    val toolName: String,
    val result: String,
    val success: Boolean = true,
    val error: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)
```

#### Request DTOs (`request/`)

**ChatRequest.kt**
```kotlin
data class ChatRequest(
    @field:NotBlank(message = "Message cannot be blank")
    val message: String,

    val conversationId: String? = null,
    val callerId: String? = null,
    val userId: String? = null,
    val accountId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
```

**CreateConversationRequest.kt**
```kotlin
data class CreateConversationRequest(
    @field:NotBlank(message = "Caller ID is required")
    val callerId: String,

    val userId: String? = null,
    val accountId: String? = null,
    val initialMessage: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
```

#### Response DTOs (`response/`)

**ChatResponse.kt**
```kotlin
data class ChatResponse(
    val conversationId: String,
    val message: String,
    val role: MessageRole,
    val toolCallsCount: Int = 0,
    val iterationsUsed: Int = 0,
    val tokensUsed: Int = 0,
    val timestamp: Instant = Instant.now()
)
```

**ConversationResponse.kt**
```kotlin
data class ConversationResponse(
    val conversationId: String,
    val callerId: String,
    val userId: String?,
    val accountId: String?,
    val status: ConversationStatus,
    val messageCount: Int,
    val toolCallsCount: Int,
    val totalTokens: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastMessageAt: Instant?
)
```

**StreamEvent.kt** - SSE event types
```kotlin
sealed class StreamEvent {
    abstract val conversationId: String
    abstract val timestamp: Instant

    data class StatusUpdate(
        override val conversationId: String,
        val status: String,
        val details: String? = null,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class ToolCallStart(
        override val conversationId: String,
        val toolName: String,
        val toolCallId: String,
        val arguments: Map<String, Any>,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class ToolCallResult(
        override val conversationId: String,
        val toolName: String,
        val toolCallId: String,
        val result: String,
        val success: Boolean,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class ResponseChunk(
        override val conversationId: String,
        val content: String,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class Completed(
        override val conversationId: String,
        val iterationsUsed: Int,
        val tokensUsed: Int,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class Error(
        override val conversationId: String,
        val error: String,
        val details: String? = null,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()
}
```

---

## External Clients

### Package: `com.alfredoalpizar.rag.client`

#### DeepSeek Client (`client/deepseek/`)

**DeepSeekClient.kt** - Main client interface
```kotlin
interface DeepSeekClient {
    fun chat(request: DeepSeekChatRequest): Mono<DeepSeekChatResponse>
    fun chatStream(request: DeepSeekChatRequest): Flux<DeepSeekStreamChunk>
    fun embed(texts: List<String>): Mono<List<List<Float>>>
}
```

**DeepSeekClientImpl.kt** - WebClient implementation
```kotlin
@Component
class DeepSeekClientImpl(
    private val webClient: WebClient,
    private val properties: DeepSeekProperties
) : DeepSeekClient {

    private val logger = KotlinLogging.logger {}

    override fun chat(request: DeepSeekChatRequest): Mono<DeepSeekChatResponse> {
        return webClient.post()
            .uri("${properties.api.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${properties.api.apiKey}")
            .bodyValue(request)
            .retrieve()
            .onStatus({ it.isError }, { handleError(it) })
            .bodyToMono(DeepSeekChatResponse::class.java)
            .retryWhen(retrySpec())
            .timeout(Duration.ofSeconds(properties.api.timeoutSeconds))
    }

    override fun chatStream(request: DeepSeekChatRequest): Flux<DeepSeekStreamChunk> {
        val streamRequest = request.copy(stream = true)

        return webClient.post()
            .uri("${properties.api.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${properties.api.apiKey}")
            .bodyValue(streamRequest)
            .retrieve()
            .onStatus({ it.isError }, { handleError(it) })
            .bodyToFlux(String::class.java)
            .filter { it.startsWith("data: ") && it != "data: [DONE]" }
            .map { parseStreamChunk(it) }
            .timeout(Duration.ofSeconds(properties.api.timeoutSeconds))
    }

    override fun embed(texts: List<String>): Mono<List<List<Float>>> {
        // Implementation for embeddings
    }

    private fun parseStreamChunk(line: String): DeepSeekStreamChunk {
        val json = line.removePrefix("data: ")
        return objectMapper.readValue(json, DeepSeekStreamChunk::class.java)
    }

    private fun retrySpec(): Retry {
        return Retry.backoff(properties.api.maxRetries.toLong(), Duration.ofSeconds(1))
            .filter { it is WebClientRequestException || it is TimeoutException }
    }
}
```

**DeepSeek DTOs**
```kotlin
data class DeepSeekChatRequest(
    val model: String,
    val messages: List<DeepSeekMessage>,
    val temperature: Double = 0.7,
    val max_tokens: Int? = null,
    val stream: Boolean = false,
    val tools: List<DeepSeekTool>? = null
)

data class DeepSeekMessage(
    val role: String,
    val content: String,
    val tool_call_id: String? = null,
    val tool_calls: List<DeepSeekToolCall>? = null
)

data class DeepSeekTool(
    val type: String = "function",
    val function: DeepSeekFunction
)

data class DeepSeekFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class DeepSeekToolCall(
    val id: String,
    val type: String,
    val function: DeepSeekFunctionCall
)

data class DeepSeekFunctionCall(
    val name: String,
    val arguments: String
)

data class DeepSeekChatResponse(
    val id: String,
    val model: String,
    val choices: List<DeepSeekChoice>,
    val usage: DeepSeekUsage?
)

data class DeepSeekChoice(
    val index: Int,
    val message: DeepSeekMessage,
    val finish_reason: String?
)

data class DeepSeekUsage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

data class DeepSeekStreamChunk(
    val id: String,
    val model: String,
    val choices: List<DeepSeekStreamChoice>
)

data class DeepSeekStreamChoice(
    val index: Int,
    val delta: DeepSeekDelta,
    val finish_reason: String?
)

data class DeepSeekDelta(
    val role: String?,
    val content: String?,
    val tool_calls: List<DeepSeekToolCall>?
)
```

#### ChromaDB Client (`client/chromadb/`)

**ChromaDBClient.kt** - Interface
```kotlin
interface ChromaDBClient {
    fun query(text: String, nResults: Int = 5): Mono<List<ChromaDBResult>>
    fun add(documents: List<ChromaDBDocument>): Mono<Unit>
    fun getCollectionInfo(): Mono<ChromaDBCollection>
}
```

**ChromaDBClientImpl.kt**
```kotlin
@Component
class ChromaDBClientImpl(
    private val webClient: WebClient,
    private val properties: ChromaDBProperties
) : ChromaDBClient {

    override fun query(text: String, nResults: Int): Mono<List<ChromaDBResult>> {
        val request = ChromaDBQueryRequest(
            queryTexts = listOf(text),
            nResults = nResults
        )

        return webClient.post()
            .uri("${properties.baseUrl}/api/v1/collections/${properties.collectionName}/query")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(ChromaDBQueryResponse::class.java)
            .map { response -> response.toResults() }
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
    }

    // Other methods...
}
```

**ChromaDB DTOs**
```kotlin
data class ChromaDBQueryRequest(
    val queryTexts: List<String>,
    val nResults: Int = 5
)

data class ChromaDBQueryResponse(
    val ids: List<List<String>>,
    val documents: List<List<String>>,
    val distances: List<List<Float>>,
    val metadatas: List<List<Map<String, Any>>>?
) {
    fun toResults(): List<ChromaDBResult> {
        // Flatten the nested structure
        return ids[0].mapIndexed { index, id ->
            ChromaDBResult(
                id = id,
                document = documents[0][index],
                distance = distances[0][index],
                metadata = metadatas?.get(0)?.get(index) ?: emptyMap()
            )
        }
    }
}

data class ChromaDBResult(
    val id: String,
    val document: String,
    val distance: Float,
    val metadata: Map<String, Any>
)

data class ChromaDBDocument(
    val id: String,
    val document: String,
    val metadata: Map<String, Any> = emptyMap()
)

data class ChromaDBCollection(
    val name: String,
    val count: Int
)
```

---

## Repository Layer

### Package: `com.alfredoalpizar.rag.repository`

**ConversationRepository.kt**
```kotlin
interface ConversationRepository : JpaRepository<Conversation, String> {

    fun findByCallerId(callerId: String, pageable: Pageable): Page<Conversation>

    fun findByUserId(userId: String, pageable: Pageable): Page<Conversation>

    fun findByCallerIdAndStatus(
        callerId: String,
        status: ConversationStatus,
        pageable: Pageable
    ): Page<Conversation>

    fun findByCreatedAtBetween(
        startDate: Instant,
        endDate: Instant,
        pageable: Pageable
    ): Page<Conversation>

    @Query("""
        SELECT c FROM Conversation c
        WHERE c.callerId = :callerId
        AND c.status = :status
        AND c.createdAt > :since
        ORDER BY c.updatedAt DESC
    """)
    fun findRecentByCallerIdAndStatus(
        @Param("callerId") callerId: String,
        @Param("status") status: ConversationStatus,
        @Param("since") since: Instant
    ): List<Conversation>
}
```

**ConversationMessageRepository.kt**
```kotlin
interface ConversationMessageRepository : JpaRepository<ConversationMessage, String> {

    fun findByConversationOrderByCreatedAtAsc(conversation: Conversation): List<ConversationMessage>

    fun findByConversation_ConversationIdOrderByCreatedAtAsc(conversationId: String): List<ConversationMessage>

    @Query("""
        SELECT m FROM ConversationMessage m
        WHERE m.conversation.conversationId = :conversationId
        ORDER BY m.createdAt DESC
    """)
    fun findRecentMessages(
        @Param("conversationId") conversationId: String,
        pageable: Pageable
    ): List<ConversationMessage>
}
```

---

## Service Layer

### Package: `com.alfredoalpizar.rag.service`

#### Context Management (`service/context/`)

**ContextManager.kt** - Interface
```kotlin
interface ContextManager {
    suspend fun loadConversation(conversationId: String): ConversationContext
    suspend fun saveConversation(context: ConversationContext)
    suspend fun createConversation(request: CreateConversationRequest): ConversationContext
    suspend fun addMessage(conversationId: String, message: Message): ConversationContext
    suspend fun getRecentConversations(callerId: String, limit: Int): List<Conversation>
}

data class ConversationContext(
    val conversation: Conversation,
    val messages: List<Message>,
    val totalTokens: Int
)
```

**ContextManagerImpl.kt**
```kotlin
@Service
class ContextManagerImpl(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: ConversationMessageRepository,
    private val properties: ConversationProperties
) : ContextManager {

    override suspend fun loadConversation(conversationId: String): ConversationContext = withContext(Dispatchers.IO) {
        val conversation = conversationRepository.findById(conversationId)
            .orElseThrow { RagException("Conversation not found: $conversationId") }

        val messages = if (properties.storageMode == "in-memory") {
            // Load from in-memory cache
            emptyList()
        } else {
            // Load from SQL with rolling window
            val allMessages = messageRepository.findByConversation_ConversationIdOrderByCreatedAtAsc(conversationId)

            // Apply rolling window
            val windowSize = properties.rollingWindowSize
            val recentMessages = if (allMessages.size > windowSize) {
                allMessages.takeLast(windowSize)
            } else {
                allMessages
            }

            recentMessages.map { it.toMessage() }
        }

        val totalTokens = messages.sumOf { estimateTokens(it.content) }

        ConversationContext(
            conversation = conversation,
            messages = messages,
            totalTokens = totalTokens
        )
    }

    override suspend fun createConversation(request: CreateConversationRequest): ConversationContext = withContext(Dispatchers.IO) {
        val conversation = Conversation(
            callerId = request.callerId,
            userId = request.userId,
            accountId = request.accountId,
            metadata = objectMapper.writeValueAsString(request.metadata)
        )

        val saved = conversationRepository.save(conversation)

        ConversationContext(
            conversation = saved,
            messages = emptyList(),
            totalTokens = 0
        )
    }

    override suspend fun addMessage(conversationId: String, message: Message): ConversationContext = withContext(Dispatchers.IO) {
        val conversation = conversationRepository.findById(conversationId)
            .orElseThrow { RagException("Conversation not found: $conversationId") }

        val messageEntity = ConversationMessage(
            conversation = conversation,
            role = message.role,
            content = message.content,
            toolCallId = message.toolCallId,
            tokenCount = estimateTokens(message.content)
        )

        messageRepository.save(messageEntity)

        // Update conversation stats
        conversation.messageCount++
        conversation.totalTokens += messageEntity.tokenCount ?: 0
        conversation.lastMessageAt = Instant.now()
        conversation.updatedAt = Instant.now()
        conversationRepository.save(conversation)

        loadConversation(conversationId)
    }

    private fun estimateTokens(text: String): Int {
        // Simple estimation: ~4 characters per token
        return (text.length / 4).coerceAtLeast(1)
    }

    private fun ConversationMessage.toMessage(): Message {
        return Message(
            role = this.role,
            content = this.content,
            toolCallId = this.toolCallId
        )
    }
}
```

#### Tool System (`service/tool/`)

**Tool.kt** - Base interface
```kotlin
interface Tool {
    val name: String
    val description: String
    val parameters: Map<String, Any>

    suspend fun execute(arguments: Map<String, Any>): ToolResult
}
```

**ToolRegistry.kt**
```kotlin
@Service
class ToolRegistry(
    tools: List<Tool>
) {
    private val toolMap: Map<String, Tool> = tools.associateBy { it.name }

    fun getTool(name: String): Tool? = toolMap[name]

    fun getAllTools(): List<Tool> = toolMap.values.toList()

    fun getToolDefinitions(): List<DeepSeekTool> {
        return toolMap.values.map { tool ->
            DeepSeekTool(
                type = "function",
                function = DeepSeekFunction(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            )
        }
    }

    suspend fun executeTool(toolCall: ToolCall): ToolResult {
        val tool = getTool(toolCall.function.name)
            ?: return ToolResult(
                toolCallId = toolCall.id,
                toolName = toolCall.function.name,
                result = "",
                success = false,
                error = "Tool not found: ${toolCall.function.name}"
            )

        return try {
            val arguments = parseArguments(toolCall.function.arguments)
            tool.execute(arguments).copy(toolCallId = toolCall.id)
        } catch (e: Exception) {
            ToolResult(
                toolCallId = toolCall.id,
                toolName = tool.name,
                result = "",
                success = false,
                error = "Tool execution failed: ${e.message}"
            )
        }
    }

    private fun parseArguments(json: String): Map<String, Any> {
        return objectMapper.readValue(json, object : TypeReference<Map<String, Any>>() {})
    }
}
```

**RAGTool.kt** - RAG retrieval tool
```kotlin
@Component
class RAGTool(
    private val chromaDBClient: ChromaDBClient
) : Tool {

    override val name = "rag_search"
    override val description = "Search the knowledge base for relevant information"
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf(
                "type" to "string",
                "description" to "The search query"
            ),
            "max_results" to mapOf(
                "type" to "integer",
                "description" to "Maximum number of results to return (default: 5)"
            )
        ),
        "required" to listOf("query")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val query = arguments["query"] as? String
            ?: return ToolResult(
                toolCallId = "",
                toolName = name,
                result = "",
                success = false,
                error = "Missing required parameter: query"
            )

        val maxResults = (arguments["max_results"] as? Int) ?: 5

        return try {
            val results = chromaDBClient.query(query, maxResults).awaitSingle()

            val formattedResults = results.joinToString("\n\n") { result ->
                "Document: ${result.document}\n(Relevance: ${1 - result.distance})"
            }

            ToolResult(
                toolCallId = "",
                toolName = name,
                result = formattedResults,
                success = true,
                metadata = mapOf("resultsCount" to results.size)
            )
        } catch (e: Exception) {
            ToolResult(
                toolCallId = "",
                toolName = name,
                result = "",
                success = false,
                error = "RAG search failed: ${e.message}"
            )
        }
    }
}
```

#### Orchestrator (`service/orchestrator/`)

**OrchestratorService.kt** - Main loop coordinator
```kotlin
@Service
class OrchestratorService(
    private val contextManager: ContextManager,
    private val toolRegistry: ToolRegistry,
    private val deepSeekClient: DeepSeekClient,
    private val finalizerStrategy: FinalizerStrategy,
    private val properties: LoopProperties
) {
    private val logger = KotlinLogging.logger {}

    suspend fun processMessageStream(
        conversationId: String,
        userMessage: String
    ): Flow<StreamEvent> = flow {
        try {
            // Load conversation context
            emit(StreamEvent.StatusUpdate(conversationId, "Loading conversation..."))
            var context = contextManager.loadConversation(conversationId)

            // Add user message
            emit(StreamEvent.StatusUpdate(conversationId, "Adding user message..."))
            val userMsg = Message(role = MessageRole.USER, content = userMessage)
            context = contextManager.addMessage(conversationId, userMsg)

            // Initial RAG retrieval
            emit(StreamEvent.StatusUpdate(conversationId, "Performing initial knowledge search..."))
            val ragResults = performRAGSearch(userMessage)

            // Build initial context
            val messages = mutableListOf<Message>()
            messages.addAll(context.messages)
            messages.add(Message(
                role = MessageRole.SYSTEM,
                content = "Knowledge base results:\n$ragResults"
            ))

            // Tool-calling loop
            var iteration = 0
            var totalTokens = 0
            var continueLoop = true

            while (continueLoop && iteration < properties.maxIterations) {
                iteration++
                emit(StreamEvent.StatusUpdate(conversationId, "Iteration $iteration..."))

                // Call DeepSeek API
                val request = buildDeepSeekRequest(messages)
                val response = deepSeekClient.chat(request).awaitSingle()

                val assistantMessage = response.choices[0].message
                totalTokens += response.usage?.total_tokens ?: 0

                // Check for tool calls
                if (assistantMessage.tool_calls != null && assistantMessage.tool_calls.isNotEmpty()) {
                    // Add assistant message with tool calls
                    messages.add(Message(
                        role = MessageRole.ASSISTANT,
                        content = assistantMessage.content ?: "",
                        toolCalls = assistantMessage.tool_calls.map { it.toToolCall() }
                    ))

                    // Execute tools
                    for (toolCall in assistantMessage.tool_calls) {
                        emit(StreamEvent.ToolCallStart(
                            conversationId = conversationId,
                            toolName = toolCall.function.name,
                            toolCallId = toolCall.id,
                            arguments = parseArguments(toolCall.function.arguments)
                        ))

                        val result = toolRegistry.executeTool(toolCall.toToolCall())

                        emit(StreamEvent.ToolCallResult(
                            conversationId = conversationId,
                            toolName = result.toolName,
                            toolCallId = result.toolCallId,
                            result = result.result,
                            success = result.success
                        ))

                        // Add tool result to messages
                        messages.add(Message(
                            role = MessageRole.TOOL,
                            content = result.result,
                            toolCallId = result.toolCallId
                        ))

                        // Update conversation stats
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

                    // Stream response chunks
                    emit(StreamEvent.ResponseChunk(conversationId, finalContent))
                }
            }

            // Update final stats
            context.conversation.totalTokens += totalTokens
            contextManager.saveConversation(context)

            // Emit completion
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

    private suspend fun performRAGSearch(query: String): String {
        val ragTool = toolRegistry.getTool("rag_search") ?: return ""
        val result = ragTool.execute(mapOf("query" to query))
        return if (result.success) result.result else ""
    }

    private fun buildDeepSeekRequest(messages: List<Message>): DeepSeekChatRequest {
        return DeepSeekChatRequest(
            model = if (properties.useReasoningModel) "deepseek-reasoner" else "deepseek-chat",
            messages = messages.map { it.toDeepSeekMessage() },
            temperature = properties.temperature,
            max_tokens = properties.maxTokens,
            tools = toolRegistry.getToolDefinitions()
        )
    }

    private fun Message.toDeepSeekMessage(): DeepSeekMessage {
        return DeepSeekMessage(
            role = this.role.name.lowercase(),
            content = this.content,
            tool_call_id = this.toolCallId,
            tool_calls = this.toolCalls?.map { it.toDeepSeekToolCall() }
        )
    }

    private fun ToolCall.toDeepSeekToolCall(): DeepSeekToolCall {
        return DeepSeekToolCall(
            id = this.id,
            type = this.type,
            function = DeepSeekFunctionCall(
                name = this.function.name,
                arguments = this.function.arguments
            )
        )
    }

    private fun DeepSeekToolCall.toToolCall(): ToolCall {
        return ToolCall(
            id = this.id,
            type = this.type,
            function = FunctionCall(
                name = this.function.name,
                arguments = this.function.arguments
            )
        )
    }

    private fun parseArguments(json: String): Map<String, Any> {
        return objectMapper.readValue(json, object : TypeReference<Map<String, Any>>() {})
    }
}
```

#### Finalizer (`service/finalizer/`)

**FinalizerStrategy.kt**
```kotlin
interface FinalizerStrategy {
    fun format(response: String, metadata: Map<String, Any> = emptyMap()): String
}

@Component
class DirectFinalizerStrategy : FinalizerStrategy {
    override fun format(response: String, metadata: Map<String, Any>): String {
        return response
    }
}

@Component
class StructuredFinalizerStrategy : FinalizerStrategy {
    override fun format(response: String, metadata: Map<String, Any>): String {
        // Add structured formatting (e.g., markdown, citations, etc.)
        return "## Response\n\n$response"
    }
}
```

---

## Controller Layer

### Package: `com.alfredoalpizar.rag.controller`

#### Chat Controller (`controller/chat/`)

**ChatController.kt**
```kotlin
@RestController
@RequestMapping("/api/v1/chat")
class ChatController(
    private val orchestratorService: OrchestratorService,
    private val contextManager: ContextManager
) {
    private val logger = KotlinLogging.logger {}

    @PostMapping("/conversations")
    suspend fun createConversation(
        @Valid @RequestBody request: CreateConversationRequest
    ): ResponseEntity<ConversationResponse> {
        val context = contextManager.createConversation(request)
        return ResponseEntity.ok(context.conversation.toResponse())
    }

    @GetMapping("/conversations/{conversationId}")
    suspend fun getConversation(
        @PathVariable conversationId: String
    ): ResponseEntity<ConversationResponse> {
        val context = contextManager.loadConversation(conversationId)
        return ResponseEntity.ok(context.conversation.toResponse())
    }

    @PostMapping("/conversations/{conversationId}/messages/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun sendMessageStream(
        @PathVariable conversationId: String,
        @Valid @RequestBody request: ChatRequest
    ): Flux<ServerSentEvent<String>> {
        return orchestratorService.processMessageStream(conversationId, request.message)
            .map { event ->
                ServerSentEvent.builder<String>()
                    .event(event.javaClass.simpleName)
                    .data(objectMapper.writeValueAsString(event))
                    .build()
            }
            .asFlux()
    }

    @GetMapping("/conversations")
    suspend fun listConversations(
        @RequestParam callerId: String,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<List<ConversationResponse>> {
        val conversations = contextManager.getRecentConversations(callerId, limit)
        return ResponseEntity.ok(conversations.map { it.toResponse() })
    }

    private fun Conversation.toResponse(): ConversationResponse {
        return ConversationResponse(
            conversationId = this.conversationId,
            callerId = this.callerId,
            userId = this.userId,
            accountId = this.accountId,
            status = this.status,
            messageCount = this.messageCount,
            toolCallsCount = this.toolCallsCount,
            totalTokens = this.totalTokens,
            createdAt = this.createdAt,
            updatedAt = this.updatedAt,
            lastMessageAt = this.lastMessageAt
        )
    }
}
```

#### Agent Controller (`controller/agent/`)

**AgentController.kt**
```kotlin
@RestController
@RequestMapping("/api/v1/agent")
class AgentController(
    private val toolRegistry: ToolRegistry
) {

    @GetMapping("/tools")
    fun listTools(): ResponseEntity<List<ToolInfo>> {
        val tools = toolRegistry.getAllTools().map { tool ->
            ToolInfo(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters
            )
        }
        return ResponseEntity.ok(tools)
    }

    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf(
            "status" to "healthy",
            "service" to "agent-orchestrator"
        ))
    }
}

data class ToolInfo(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)
```

---

## Error Handling

Errors are already handled via `GlobalExceptionHandler.kt`. Add specific handling for:

```kotlin
@ExceptionHandler(ConversationNotFoundException::class)
fun handleConversationNotFound(ex: ConversationNotFoundException): ResponseEntity<ErrorResponse> {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse(
            error = "Conversation not found",
            message = ex.message,
            timestamp = Instant.now()
        ))
}

@ExceptionHandler(ToolExecutionException::class)
fun handleToolExecution(ex: ToolExecutionException): ResponseEntity<ErrorResponse> {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse(
            error = "Tool execution failed",
            message = ex.message,
            timestamp = Instant.now()
        ))
}
```

---

## Implementation Order

Implement in this order to minimize dependencies:

1. **Models** - All DTOs and domain entities first
2. **Clients** - DeepSeek and ChromaDB clients
3. **Repositories** - JPA repositories for persistence
4. **Tools** - Tool interface, registry, and RAG tool
5. **Context Manager** - Conversation history management
6. **Orchestrator** - Main loop coordinator
7. **Finalizer** - Response formatting strategies
8. **Controllers** - Chat and Agent endpoints
9. **Tests** - Unit and integration tests

---

**Next**: See [API_SPEC.md](API_SPEC.md) for detailed API documentation.