# Context Management Guide

How conversation context is managed in the RAG Orchestrator.

---

## Overview

Context management maintains coherent multi-turn conversations while staying within token limits.

### Challenges Solved

- **Token limits**: LLMs have maximum context windows
- **Performance**: Larger contexts = slower responses and higher costs
- **Relevance**: Old messages may not be relevant

### Solution: Rolling Window

Keep only the most recent N messages in context, preventing unbounded growth.

---

## Architecture

```
User Message
    ↓
Context Manager
    ↓
1. Load conversation from storage
2. Apply rolling window (last N messages)
3. Return ConversationContext
    ↓
Orchestrator Service
    ↓
1. Add user message
2. Add RAG results
3. Execute tool-calling loop
4. Save new messages
```

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

---

## Data Structures

### ConversationContext

```kotlin
data class ConversationContext(
    val conversation: Conversation,
    val messages: List<Message>,
    val totalTokens: Int
)
```

### Message

```kotlin
data class Message(
    val role: MessageRole,  // USER, ASSISTANT, TOOL, SYSTEM
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null
)
```

---

## Rolling Window

### How It Works

```
Full history in storage (10 messages):
1. User: "Hello"
2. Assistant: "Hi!"
3. User: "What's the weather?"
4. Assistant: "Let me check..."
5. Tool: "Sunny, 72°F"
6. Assistant: "It's sunny"
7. User: "Tomorrow?"
8. Assistant: "Let me check..."
9. Tool: "Rainy, 65°F"
10. Assistant: "It will rain"

Rolling window (size=5) sent to LLM:
6. Assistant: "It's sunny"
7. User: "Tomorrow?"
8. Assistant: "Let me check..."
9. Tool: "Rainy, 65°F"
10. Assistant: "It will rain"
```

### Implementation

```kotlin
val windowSize = properties.rollingWindowSize  // default: 20
val recentMessages = if (allMessages.size > windowSize) {
    allMessages.takeLast(windowSize)
} else {
    allMessages
}
```

### Choosing Window Size

| Size | Pros | Cons |
|------|------|------|
| Small (5-10) | Fast, low cost | Limited context |
| Medium (20-30) | Balanced | Moderate cost |
| Large (50-100) | Maximum context | Slower, expensive |

**Recommended**: Start with 20, adjust based on use case.

---

## Storage Modes

### In-Memory (Default)

```yaml
conversation:
  storage-mode: in-memory
```

- Zero setup required
- Uses `ConcurrentHashMap` internally
- Data lost on restart
- Perfect for development and learning

### Database (Production)

```yaml
conversation:
  storage-mode: database
```

- Requires PostgreSQL
- Persistent storage
- Full audit trail
- Query by caller_id, date range

---

## Configuration

```yaml
conversation:
  storage-mode: in-memory  # or: database
  rolling-window-size: 20
```

---

## Enabling Database Mode

1. Uncomment PostgreSQL driver in `build.gradle.kts`:

```kotlin
runtimeOnly("org.postgresql:postgresql")
```

2. Uncomment datasource config in `application.yml`:

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/rag_orchestrator}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:}
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
```

3. Set storage mode:

```yaml
conversation:
  storage-mode: database
```

4. Run Flyway migrations (automatic on startup).

---

## Database Schema

### conversations

```sql
CREATE TABLE conversations (
    conversation_id VARCHAR(36) PRIMARY KEY,
    caller_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100),
    account_id VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_message_at TIMESTAMP,
    message_count INT DEFAULT 0,
    tool_calls_count INT DEFAULT 0,
    total_tokens INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'active'
);
```

### conversation_messages

```sql
CREATE TABLE conversation_messages (
    message_id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    tool_call_id VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    token_count INT
);
```

---

## Audit Queries (Database Mode)

```sql
-- Recent conversations by caller
SELECT * FROM conversations
WHERE caller_id = 'user@example.com'
AND created_at > NOW() - INTERVAL '7 days'
ORDER BY created_at DESC;

-- Message history
SELECT role, content, created_at
FROM conversation_messages
WHERE conversation_id = 'your-id'
ORDER BY created_at ASC;

-- Usage stats by caller
SELECT caller_id,
       COUNT(*) as conversations,
       SUM(message_count) as messages,
       SUM(total_tokens) as tokens
FROM conversations
GROUP BY caller_id;
```
