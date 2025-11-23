# Context Management Guide

**Project**: RAG Orchestrator Service
**Version**: 0.0.1-SNAPSHOT
**Last Updated**: 2025-11-23

---

## Table of Contents

1. [Overview](#overview)
2. [Context Architecture](#context-architecture)
3. [Storage Strategies](#storage-strategies)
4. [Rolling Window](#rolling-window)
5. [Token Management](#token-management)
6. [Context Compression](#context-compression)
7. [Multi-Turn Conversations](#multi-turn-conversations)
8. [Best Practices](#best-practices)
9. [Configuration](#configuration)
10. [Examples](#examples)

---

## Overview

Context management is critical for maintaining coherent, multi-turn conversations while staying within token limits and optimizing performance.

### Key Challenges

1. **Token Limits**: LLMs have maximum context windows (e.g., 4K, 8K, 100K tokens)
2. **Performance**: Larger contexts = slower responses and higher costs
3. **Memory**: Storing full conversation history can be expensive
4. **Relevance**: Old messages may not be relevant to current conversation
5. **Consistency**: Context must be consistent across requests

### Our Solution

The RAG Orchestrator uses a **phased storage approach** with **rolling window context**:

- **Phase 1 (POC)**: SQL storage with rolling window
- **Phase 2 (Scale)**: Redis cache for hot data
- **Phase 3 (Archive)**: S3 for cold storage

---

## Context Architecture

### Context Flow

```
User Message
    ↓
┌─────────────────────────────────────┐
│      Context Manager                │
│                                     │
│  1. Load conversation               │
│  2. Apply rolling window            │
│  3. Estimate tokens                 │
│  4. Check compression threshold     │
│  5. Return ConversationContext      │
└─────────────────────────────────────┘
    ↓
┌─────────────────────────────────────┐
│      Orchestrator                   │
│                                     │
│  1. Add user message                │
│  2. Add RAG results                 │
│  3. Build LLM request               │
│  4. Execute tool-calling loop       │
│  5. Add assistant response          │
└─────────────────────────────────────┘
    ↓
┌─────────────────────────────────────┐
│      Context Manager                │
│                                     │
│  1. Save new messages               │
│  2. Update conversation stats       │
│  3. Update timestamps               │
└─────────────────────────────────────┘
```

### Data Structures

**ConversationContext** - In-memory representation
```kotlin
data class ConversationContext(
    val conversation: Conversation,  // Metadata
    val messages: List<Message>,     // Message history (rolling window)
    val totalTokens: Int             // Estimated token count
)
```

**Conversation** - Database entity
```kotlin
@Entity
data class Conversation(
    val conversationId: String,
    val callerId: String,
    val userId: String?,
    val accountId: String?,

    // Timestamps
    val createdAt: Instant,
    var updatedAt: Instant,
    var lastMessageAt: Instant?,

    // Statistics
    var messageCount: Int,
    var toolCallsCount: Int,
    var totalTokens: Int,

    // Status
    var status: ConversationStatus,

    // Relationships
    val messages: MutableList<ConversationMessage>
)
```

**Message** - In-memory message
```kotlin
data class Message(
    val role: MessageRole,        // USER, ASSISTANT, TOOL, SYSTEM
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null
)
```

---

## Storage Strategies

### Phase 1: SQL Only (Current)

**Use case**: POC, audit trails, compliance

**Architecture**:
```
User Request
    ↓
Context Manager
    ↓
JPA Repository
    ↓
Sybase ASE Database
    ↓
conversations table
conversation_messages table
```

**Pros**:
- Simple, proven technology
- Great for audits and compliance
- Rich query capabilities (by caller_id, date range, etc.)
- No new infrastructure

**Cons**:
- Latency: 10-50ms per query
- Not ideal for high-frequency access
- Limited scalability for millions of conversations

**Configuration**:
```yaml
conversation:
  storage-mode: sql
  rolling-window-size: 20
```

---

### Phase 2: Redis Cache (Future)

**Use case**: Production at scale

**Architecture**:
```
User Request
    ↓
Context Manager
    ↓
    ├─ Check Redis Cache (< 1 hour old)
    │   ├─ Hit: Return immediately (1-2ms)
    │   └─ Miss: Fall back to SQL
    └─ SQL Database (backup)
```

**Cache Strategy**:
- **Hot data**: Active conversations (< 1 hour) in Redis
- **Warm data**: Recent conversations (< 30 days) in SQL
- **Cold data**: Old conversations (> 90 days) in S3

**Redis Structure**:
```
Key: "conversation:{conversationId}"
Value: JSON-serialized ConversationContext
TTL: 1 hour (auto-expire)

Key: "user_conversations:{callerId}"
Value: List of conversation IDs
TTL: 24 hours
```

**Configuration**:
```yaml
conversation:
  storage-mode: redis
  cache:
    ttl-minutes: 60
    max-size: 10000
  fallback-to-sql: true
```

---

### Phase 3: S3 Archive (Future)

**Use case**: Long-term retention, cost optimization

**Architecture**:
```
Background Job (runs daily)
    ↓
Find conversations > 90 days old
    ↓
Export to S3 as JSON
    ↓
Mark as ARCHIVED in SQL
    ↓
Delete message rows (keep metadata)
```

**S3 Structure**:
```
s3://conversations-archive/
  ├── 2025/
  │   ├── 01/
  │   │   ├── conversation-uuid1.json.gz
  │   │   └── conversation-uuid2.json.gz
  │   └── 02/
  └── 2024/
```

**Retrieval**:
```kotlin
// Check SQL first
val conversation = conversationRepository.findById(id)

if (conversation.status == ConversationStatus.ARCHIVED && conversation.s3Key != null) {
    // Fetch from S3
    val archived = s3Client.getObject(conversation.s3Key)
    return parseArchivedConversation(archived)
} else {
    // Load from SQL
    return loadFromDatabase(id)
}
```

**Configuration**:
```yaml
conversation:
  archive:
    enabled: true
    s3-bucket: conversations-archive
    archive-after-days: 90
    cleanup-after-archive: true
```

---

## Rolling Window

### Concept

The **rolling window** keeps only the most recent N messages in context, preventing unbounded growth.

### How It Works

```
Full conversation history (in database):
1. User: "Hello"
2. Assistant: "Hi there!"
3. User: "What's the weather?"
4. Assistant: "Let me check..."
5. Tool: "Sunny, 72°F"
6. Assistant: "It's sunny and 72°F"
7. User: "What about tomorrow?"
8. Assistant: "Let me check..."
9. Tool: "Rainy, 65°F"
10. Assistant: "It will be rainy and 65°F"

Rolling window (size=5, sent to LLM):
6. Assistant: "It's sunny and 72°F"
7. User: "What about tomorrow?"
8. Assistant: "Let me check..."
9. Tool: "Rainy, 65°F"
10. Assistant: "It will be rainy and 65°F"
```

### Implementation

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

        // Load all messages from database
        val allMessages = messageRepository.findByConversation_ConversationIdOrderByCreatedAtAsc(conversationId)

        // Apply rolling window
        val windowSize = properties.rollingWindowSize
        val recentMessages = if (allMessages.size > windowSize) {
            allMessages.takeLast(windowSize)
        } else {
            allMessages
        }

        // Convert to in-memory format
        val messages = recentMessages.map { it.toMessage() }

        // Estimate tokens
        val totalTokens = messages.sumOf { estimateTokens(it.content) }

        ConversationContext(
            conversation = conversation,
            messages = messages,
            totalTokens = totalTokens
        )
    }

    private fun estimateTokens(text: String): Int {
        // Simple heuristic: ~4 characters per token
        return (text.length / 4).coerceAtLeast(1)
    }
}
```

### Choosing Window Size

**Small window (5-10 messages)**:
- ✅ Fast responses
- ✅ Low cost
- ✅ Good for single-topic conversations
- ❌ Limited context
- ❌ May forget earlier information

**Medium window (20-30 messages)**:
- ✅ Balanced performance/context
- ✅ Good for most use cases
- ✅ Retains recent context
- ⚠️ Moderate cost

**Large window (50-100 messages)**:
- ✅ Maximum context retention
- ✅ Good for complex, long conversations
- ❌ Slower responses
- ❌ Higher cost
- ❌ May hit token limits

**Recommended**: Start with 20, adjust based on your use case.

---

## Token Management

### Token Estimation

We use a simple heuristic:

```kotlin
fun estimateTokens(text: String): Int {
    // ~4 characters per token (English text)
    return (text.length / 4).coerceAtLeast(1)
}
```

**More accurate** (optional):
```kotlin
// Use tiktoken library for accurate counting
fun countTokens(text: String, model: String): Int {
    val encoding = Encodings.newDefaultEncodingRegistry().getEncoding(model)
    return encoding.countTokens(text)
}
```

### Token Budgets

Configure limits to prevent exceeding LLM context windows:

```yaml
conversation:
  max-context-tokens: 100000      # Hard limit
  compression-threshold: 80000    # Start compression at 80%
```

### Token Tracking

Track tokens per conversation:

```kotlin
@Column(name = "total_tokens")
var totalTokens: Int = 0

// Update after each message
conversation.totalTokens += estimateTokens(newMessage.content)
```

### Token-Based Pruning

Prune context when approaching limits:

```kotlin
fun pruneContextIfNeeded(messages: List<Message>, maxTokens: Int): List<Message> {
    var totalTokens = messages.sumOf { estimateTokens(it.content) }

    if (totalTokens <= maxTokens) {
        return messages
    }

    // Remove oldest messages until under limit
    val pruned = mutableListOf<Message>()
    for (message in messages.reversed()) {
        val tokens = estimateTokens(message.content)
        if (totalTokens - tokens < maxTokens) {
            pruned.add(0, message)
        }
        totalTokens -= tokens
    }

    return pruned
}
```

---

## Context Compression

### When to Compress

Compress context when:
- Token count exceeds threshold (e.g., 80% of max)
- Conversation has many old messages
- Performance degrades

### Compression Strategies

**1. Summarization**

Replace old messages with a summary:

```
Original (15 messages, 5000 tokens):
1. User: "Tell me about your return policy"
2. Assistant: "We have a 30-day return policy..."
3. User: "What about exchanges?"
...
15. Assistant: "Anything else I can help with?"

Compressed (3 messages, 1000 tokens):
1. System: "Summary: User asked about return policy and exchanges.
   We explained 30-day returns and free exchanges for defective items."
2. User: "Anything else I can help with?"
3. Assistant: "No, that's all. Thank you!"
```

**Implementation**:
```kotlin
suspend fun summarizeOldMessages(messages: List<Message>): Message {
    val oldMessages = messages.dropLast(5)  // Keep last 5 untouched

    // Use LLM to generate summary
    val summary = deepSeekClient.chat(DeepSeekChatRequest(
        model = "deepseek-chat",
        messages = listOf(
            DeepSeekMessage(
                role = "system",
                content = "Summarize this conversation in 2-3 sentences"
            ),
            DeepSeekMessage(
                role = "user",
                content = oldMessages.joinToString("\n") { "${it.role}: ${it.content}" }
            )
        )
    )).awaitSingle()

    return Message(
        role = MessageRole.SYSTEM,
        content = "Previous conversation summary: ${summary.choices[0].message.content}"
    )
}
```

**2. Message Filtering**

Remove less important messages:

```kotlin
fun filterImportantMessages(messages: List<Message>): List<Message> {
    return messages.filter { message ->
        when (message.role) {
            MessageRole.USER -> true          // Always keep user messages
            MessageRole.ASSISTANT -> true     // Always keep assistant responses
            MessageRole.TOOL -> false         // Remove tool results (can be verbose)
            MessageRole.SYSTEM -> true        // Keep system prompts
        }
    }
}
```

**3. Token-Based Pruning**

Keep high-value messages, remove low-value:

```kotlin
data class ScoredMessage(
    val message: Message,
    val score: Double
)

fun pruneByScore(messages: List<Message>, targetTokens: Int): List<Message> {
    // Score messages by importance
    val scored = messages.map { message ->
        val score = when {
            message.role == MessageRole.USER -> 1.0
            message.role == MessageRole.ASSISTANT -> 0.9
            message.content.length > 1000 -> 0.5  // Long messages less important
            else -> 0.7
        }
        ScoredMessage(message, score)
    }

    // Sort by score (descending)
    val sorted = scored.sortedByDescending { it.score }

    // Take messages until token limit
    val result = mutableListOf<Message>()
    var tokens = 0

    for (item in sorted) {
        val messageTokens = estimateTokens(item.message.content)
        if (tokens + messageTokens <= targetTokens) {
            result.add(item.message)
            tokens += messageTokens
        }
    }

    // Return in original order
    return result.sortedBy { messages.indexOf(it) }
}
```

---

## Multi-Turn Conversations

### Conversation State

Track conversation state over multiple turns:

```kotlin
enum class ConversationStatus {
    ACTIVE,      // Ongoing conversation
    ARCHIVED,    // Completed, moved to cold storage
    DELETED      // User requested deletion
}
```

### Conversation Lifecycle

```
1. Create
   └─> Status: ACTIVE
       Messages: 0

2. Active Use
   └─> Status: ACTIVE
       Messages: Growing
       Last Message: < 1 hour ago

3. Inactive
   └─> Status: ACTIVE
       Messages: Static
       Last Message: > 24 hours ago

4. Archive
   └─> Status: ARCHIVED
       Messages: Exported to S3
       Last Message: > 90 days ago

5. Delete (optional)
   └─> Status: DELETED
       Messages: Soft deleted
```

### Resuming Conversations

Load and resume old conversations:

```kotlin
suspend fun resumeConversation(conversationId: String, newMessage: String): Flow<StreamEvent> {
    // Load conversation (may be from cache, SQL, or S3)
    val context = contextManager.loadConversation(conversationId)

    // Check if conversation is too old
    val daysSinceLastMessage = Duration.between(
        context.conversation.lastMessageAt ?: Instant.now(),
        Instant.now()
    ).toDays()

    if (daysSinceLastMessage > 7) {
        // Add context note for old conversations
        val note = Message(
            role = MessageRole.SYSTEM,
            content = "Note: This conversation was last active $daysSinceLastMessage days ago."
        )
        // Add to context
    }

    // Process new message
    return orchestratorService.processMessageStream(conversationId, newMessage)
}
```

---

## Best Practices

### 1. Always Use Rolling Window

Don't load entire conversation history:

✅ **Good**:
```kotlin
val messages = allMessages.takeLast(20)  // Rolling window
```

❌ **Bad**:
```kotlin
val messages = allMessages  // Entire history
```

### 2. Estimate Tokens Regularly

Track token usage:

```kotlin
val totalTokens = messages.sumOf { estimateTokens(it.content) }

if (totalTokens > properties.compressionThreshold) {
    // Trigger compression
}
```

### 3. Prune System Messages

Keep user/assistant messages, prune verbose system messages:

```kotlin
val pruned = messages.filter {
    it.role != MessageRole.SYSTEM || it.content.length < 200
}
```

### 4. Index by Caller ID

For fast lookup of user conversations:

```sql
CREATE INDEX idx_conversations_caller_id ON conversations(caller_id);
CREATE INDEX idx_conversations_caller_created ON conversations(caller_id, created_at);
```

### 5. Cleanup Old Conversations

Schedule periodic cleanup:

```kotlin
@Scheduled(cron = "0 0 2 * * *")  // 2 AM daily
suspend fun archiveOldConversations() {
    val cutoffDate = Instant.now().minus(90, ChronoUnit.DAYS)

    val oldConversations = conversationRepository.findByCreatedAtBefore(cutoffDate)

    for (conversation in oldConversations) {
        // Export to S3
        exportToS3(conversation)

        // Mark as archived
        conversation.status = ConversationStatus.ARCHIVED
        conversationRepository.save(conversation)

        // Optionally delete messages
        messageRepository.deleteByConversation(conversation)
    }
}
```

### 6. Handle Edge Cases

**Empty conversations**:
```kotlin
if (messages.isEmpty()) {
    return ConversationContext(
        conversation = conversation,
        messages = listOf(
            Message(
                role = MessageRole.SYSTEM,
                content = "You are a helpful AI assistant."
            )
        ),
        totalTokens = 10
    )
}
```

**Very long messages**:
```kotlin
fun truncateLongMessages(messages: List<Message>, maxLength: Int = 5000): List<Message> {
    return messages.map { message ->
        if (message.content.length > maxLength) {
            message.copy(
                content = message.content.take(maxLength) + "... [truncated]"
            )
        } else {
            message
        }
    }
}
```

### 7. Monitor Performance

Track metrics:

```kotlin
@Timed("context.load")
override suspend fun loadConversation(conversationId: String): ConversationContext {
    // Implementation
}

@Timed("context.save")
override suspend fun saveConversation(context: ConversationContext) {
    // Implementation
}
```

---

## Configuration

### application.yml

```yaml
conversation:
  # Storage mode: in-memory, sql, redis
  storage-mode: sql

  # Rolling window size (number of messages)
  rolling-window-size: 20

  # Token limits
  max-context-tokens: 100000
  compression-threshold: 80000

  # Conversation retention
  ttl-hours: 720  # 30 days
  cleanup-interval-minutes: 60

  # Redis cache (Phase 2)
  cache:
    enabled: false
    ttl-minutes: 60
    max-size: 10000

  # S3 archive (Phase 3)
  archive:
    enabled: false
    s3-bucket: conversations-archive
    archive-after-days: 90
    cleanup-after-archive: true
```

### Environment Variables

```bash
# Storage
CONVERSATION_STORAGE_MODE=sql
CONVERSATION_ROLLING_WINDOW_SIZE=20

# Token limits
CONVERSATION_MAX_CONTEXT_TOKENS=100000
CONVERSATION_COMPRESSION_THRESHOLD=80000

# Retention
CONVERSATION_TTL_HOURS=720

# Archive (Phase 3)
ARCHIVE_S3_BUCKET=conversations-archive
AWS_ACCESS_KEY_ID=your-access-key
AWS_SECRET_ACCESS_KEY=your-secret-key
AWS_REGION=us-east-1
```

---

## Examples

### Example 1: Load Conversation with Rolling Window

```kotlin
val context = contextManager.loadConversation("conversation-123")

println("Conversation ID: ${context.conversation.conversationId}")
println("Message count (total): ${context.conversation.messageCount}")
println("Messages in context (window): ${context.messages.size}")
println("Estimated tokens: ${context.totalTokens}")

context.messages.forEach { message ->
    println("${message.role}: ${message.content.take(50)}...")
}
```

### Example 2: Add Message and Check Token Limit

```kotlin
val newMessage = Message(
    role = MessageRole.USER,
    content = "Tell me more about context management"
)

val updatedContext = contextManager.addMessage("conversation-123", newMessage)

if (updatedContext.totalTokens > properties.compressionThreshold) {
    logger.warn("Approaching token limit: ${updatedContext.totalTokens} tokens")
    // Trigger compression
}
```

### Example 3: Archive Old Conversations

```kotlin
@Scheduled(cron = "0 0 2 * * *")
suspend fun archiveOldConversations() {
    val cutoffDate = Instant.now().minus(90, ChronoUnit.DAYS)

    val conversations = conversationRepository
        .findByStatusAndLastMessageAtBefore(
            ConversationStatus.ACTIVE,
            cutoffDate
        )

    logger.info("Archiving ${conversations.size} old conversations")

    conversations.forEach { conversation ->
        // Export to S3
        val json = exportConversationToJson(conversation)
        val s3Key = "archive/${conversation.conversationId}.json.gz"
        s3Client.putObject(s3Key, compress(json))

        // Update status
        conversation.status = ConversationStatus.ARCHIVED
        conversation.s3Key = s3Key
        conversationRepository.save(conversation)

        // Delete messages (keep metadata)
        messageRepository.deleteByConversation_ConversationId(conversation.conversationId)
    }

    logger.info("Archive complete")
}
```

### Example 4: Retrieve Archived Conversation

```kotlin
suspend fun loadConversation(conversationId: String): ConversationContext {
    val conversation = conversationRepository.findById(conversationId)
        .orElseThrow { RagException("Conversation not found") }

    return when (conversation.status) {
        ConversationStatus.ACTIVE -> {
            // Load from database
            loadActiveConversation(conversation)
        }
        ConversationStatus.ARCHIVED -> {
            // Restore from S3
            if (conversation.s3Key != null) {
                loadArchivedConversation(conversation)
            } else {
                throw RagException("Archived conversation missing S3 key")
            }
        }
        ConversationStatus.DELETED -> {
            throw RagException("Conversation was deleted")
        }
    }
}

private suspend fun loadArchivedConversation(conversation: Conversation): ConversationContext {
    val s3Object = s3Client.getObject(conversation.s3Key!!)
    val json = decompress(s3Object.content)
    val archived = objectMapper.readValue<ArchivedConversation>(json)

    return ConversationContext(
        conversation = conversation,
        messages = archived.messages,
        totalTokens = archived.totalTokens
    )
}
```

---

## Debugging

### Check Context Size

```kotlin
@GetMapping("/conversations/{id}/debug")
suspend fun debugContext(@PathVariable id: String): Map<String, Any> {
    val context = contextManager.loadConversation(id)

    return mapOf(
        "conversationId" to context.conversation.conversationId,
        "totalMessages" to context.conversation.messageCount,
        "messagesInContext" to context.messages.size,
        "totalTokens" to context.totalTokens,
        "compressionNeeded" to (context.totalTokens > properties.compressionThreshold)
    )
}
```

### Query Database

```sql
-- Check message distribution
SELECT
    conversation_id,
    COUNT(*) as message_count,
    MIN(created_at) as first_message,
    MAX(created_at) as last_message,
    DATEDIFF(day, MIN(created_at), MAX(created_at)) as conversation_days
FROM conversation_messages
GROUP BY conversation_id
ORDER BY message_count DESC;

-- Find large conversations
SELECT
    c.conversation_id,
    c.message_count,
    c.total_tokens,
    c.last_message_at
FROM conversations c
WHERE c.total_tokens > 50000
ORDER BY c.total_tokens DESC;
```

---

**Next**: See [STREAMING_GUIDE.md](STREAMING_GUIDE.md) for SSE streaming implementation.
