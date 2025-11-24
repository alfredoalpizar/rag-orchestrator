# API Specification

REST API reference for the RAG Orchestrator service.

**Base URL**: `http://localhost:8080/api/v1`

---

## Endpoints Summary

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/chat/conversations` | Create conversation |
| `GET` | `/chat/conversations/{id}` | Get conversation |
| `POST` | `/chat/conversations/{id}/messages/stream` | Send message (SSE) |
| `GET` | `/chat/conversations` | List conversations |
| `GET` | `/agent/tools` | List available tools |
| `GET` | `/agent/health` | Agent health check |
| `GET` | `/ping` | Service health check |

---

## Chat Endpoints

### Create Conversation

```http
POST /chat/conversations
Content-Type: application/json
```

**Request:**
```json
{
  "callerId": "user@example.com",
  "userId": "user123",
  "accountId": "acc456",
  "initialMessage": "Hello!",
  "metadata": {"key": "value"}
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| callerId | string | Yes | Identifier for the caller (for audit) |
| userId | string | No | User identifier |
| accountId | string | No | Account identifier |
| initialMessage | string | No | Optional first message |
| metadata | object | No | Custom metadata |

**Response:** `200 OK`
```json
{
  "conversationId": "550e8400-e29b-41d4-a716-446655440000",
  "callerId": "user@example.com",
  "userId": "user123",
  "accountId": "acc456",
  "status": "ACTIVE",
  "messageCount": 0,
  "toolCallsCount": 0,
  "totalTokens": 0,
  "createdAt": "2025-01-15T10:00:00Z",
  "updatedAt": "2025-01-15T10:00:00Z",
  "lastMessageAt": null
}
```

---

### Get Conversation

```http
GET /chat/conversations/{conversationId}
```

**Response:** `200 OK` - Same format as Create Conversation response

**Errors:**
- `404 Not Found` - Conversation does not exist

---

### Send Message (SSE Streaming)

```http
POST /chat/conversations/{conversationId}/messages/stream
Content-Type: application/json
Accept: text/event-stream
```

**Request:**
```json
{
  "message": "What is RAG?"
}
```

**Response:** Server-Sent Events stream

#### Event Types

**StatusUpdate** - Progress notifications
```
event: StatusUpdate
data: {"conversationId":"...","status":"Loading conversation...","details":null,"timestamp":"..."}
```

**ToolCallStart** - Tool invocation started
```
event: ToolCallStart
data: {"conversationId":"...","toolName":"rag_search","toolCallId":"call_123","arguments":{"query":"RAG"},"timestamp":"..."}
```

**ToolCallResult** - Tool execution completed
```
event: ToolCallResult
data: {"conversationId":"...","toolName":"rag_search","toolCallId":"call_123","result":"...","success":true,"timestamp":"..."}
```

**ResponseChunk** - Content from the model
```
event: ResponseChunk
data: {"conversationId":"...","content":"RAG stands for...","timestamp":"..."}
```

**ReasoningTrace** - Thinking model reasoning (Qwen only)
```
event: ReasoningTrace
data: {"conversationId":"...","content":"Let me think about this...","stage":"PLANNING","timestamp":"..."}
```

**Completed** - Stream finished
```
event: Completed
data: {"conversationId":"...","iterationsUsed":2,"tokensUsed":1523,"timestamp":"..."}
```

**Error** - Error occurred
```
event: Error
data: {"conversationId":"...","error":"Tool execution failed","details":"...","timestamp":"..."}
```

#### Example: curl

```bash
curl -N -X POST http://localhost:8080/api/v1/chat/conversations/{id}/messages/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "What is RAG?"}' \
  --no-buffer
```

#### Example: JavaScript

```javascript
const response = await fetch(
  `http://localhost:8080/api/v1/chat/conversations/${conversationId}/messages/stream`,
  {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: 'What is RAG?' })
  }
);

const reader = response.body.getReader();
const decoder = new TextDecoder();

while (true) {
  const { done, value } = await reader.read();
  if (done) break;

  const text = decoder.decode(value);
  // Parse SSE events from text
  console.log(text);
}
```

---

### List Conversations

```http
GET /chat/conversations?callerId={callerId}&limit={limit}
```

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| callerId | string | Yes | - | Filter by caller |
| limit | integer | No | 10 | Max results (1-100) |

**Response:** `200 OK`
```json
[
  {
    "conversationId": "...",
    "callerId": "user@example.com",
    "status": "ACTIVE",
    "messageCount": 15,
    "toolCallsCount": 3,
    "totalTokens": 4523,
    "createdAt": "2025-01-15T10:00:00Z",
    "updatedAt": "2025-01-15T10:15:00Z",
    "lastMessageAt": "2025-01-15T10:15:00Z"
  }
]
```

---

## Agent Endpoints

### List Available Tools

```http
GET /agent/tools
```

**Response:** `200 OK`
```json
[
  {
    "name": "rag_search",
    "description": "Search the knowledge base for relevant information",
    "parameters": {
      "type": "object",
      "properties": {
        "query": {
          "type": "string",
          "description": "The search query"
        },
        "max_results": {
          "type": "integer",
          "description": "Maximum number of results (default: 5)"
        }
      },
      "required": ["query"]
    }
  }
]
```

---

### Agent Health

```http
GET /agent/health
```

**Response:** `200 OK`
```json
{
  "status": "healthy",
  "service": "agent-orchestrator",
  "timestamp": "2025-01-15T10:00:00Z",
  "tools": 1
}
```

---

## Health Endpoints

### Service Ping

```http
GET /ping
```

**Response:** `200 OK`
```json
{
  "status": "ok",
  "service": "rag-orchestrator",
  "timestamp": "2025-01-15T10:00:00Z"
}
```

---

### Actuator Health

```http
GET /actuator/health
```

**Response:** `200 OK`
```json
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "diskSpace": {"status": "UP"}
  }
}
```

Other actuator endpoints: `/actuator/metrics`, `/actuator/flyway`

---

## Error Responses

All errors return:

```json
{
  "error": "Error type",
  "message": "Detailed message",
  "timestamp": "2025-01-15T10:00:00Z",
  "path": "/api/v1/..."
}
```

| Status | Description |
|--------|-------------|
| `400 Bad Request` | Invalid request body or parameters |
| `404 Not Found` | Resource not found |
| `500 Internal Server Error` | Server error |
| `503 Service Unavailable` | External service unavailable |

---

## Complete Example

```bash
# 1. Create conversation
CONV_ID=$(curl -s -X POST http://localhost:8080/api/v1/chat/conversations \
  -H "Content-Type: application/json" \
  -d '{"callerId": "demo@example.com"}' | jq -r '.conversationId')

echo "Created: $CONV_ID"

# 2. Send message with streaming
curl -N -X POST "http://localhost:8080/api/v1/chat/conversations/$CONV_ID/messages/stream" \
  -H "Content-Type: application/json" \
  -d '{"message": "What is RAG?"}' \
  --no-buffer

# 3. Check conversation stats
curl "http://localhost:8080/api/v1/chat/conversations/$CONV_ID" | jq

# 4. List all conversations
curl "http://localhost:8080/api/v1/chat/conversations?callerId=demo@example.com" | jq
```

---

## Notes

- **No authentication** in current version
- **No rate limits** in current version
- SSE streaming requires `Accept: text/event-stream` header
- Conversation IDs are UUIDs
- Timestamps are ISO 8601 format (UTC)
