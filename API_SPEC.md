# API Specification

**Project**: RAG Orchestrator Service
**Version**: 0.0.1-SNAPSHOT
**Base URL**: `http://localhost:8080/api/v1`
**Last Updated**: 2025-11-23

---

## Table of Contents

1. [Overview](#overview)
2. [Authentication](#authentication)
3. [Chat Endpoints](#chat-endpoints)
4. [Agent Endpoints](#agent-endpoints)
5. [Health & Monitoring](#health--monitoring)
6. [Error Handling](#error-handling)
7. [Examples](#examples)

---

## Overview

The RAG Orchestrator provides a RESTful API with Server-Sent Events (SSE) streaming support for real-time conversational AI with tool-calling capabilities.

### Key Features

- **Conversation Management**: Create and manage multi-turn conversations
- **Streaming Responses**: Real-time SSE streaming of AI responses and tool executions
- **Tool Orchestration**: Automatic RAG retrieval and custom tool execution
- **Context Awareness**: Rolling window conversation history
- **Persistence**: SQL-based conversation storage with audit trails

---

## Authentication

**Current Version**: No authentication (v0.0.1-SNAPSHOT)

**Future Versions**: Bearer token authentication will be added:
```http
Authorization: Bearer <token>
```

---

## Chat Endpoints

### Create Conversation

Create a new conversation session.

**Endpoint**: `POST /chat/conversations`

**Request Body**:
```json
{
  "callerId": "string (required)",
  "userId": "string (optional)",
  "accountId": "string (optional)",
  "initialMessage": "string (optional)",
  "metadata": {
    "key": "value"
  }
}
```

**Response**: `200 OK`
```json
{
  "conversationId": "uuid",
  "callerId": "john.doe@company.com",
  "userId": "user123",
  "accountId": "acc456",
  "status": "ACTIVE",
  "messageCount": 0,
  "toolCallsCount": 0,
  "totalTokens": 0,
  "createdAt": "2025-11-23T10:00:00Z",
  "updatedAt": "2025-11-23T10:00:00Z",
  "lastMessageAt": null
}
```

**Example**:
```bash
curl -X POST http://localhost:8080/api/v1/chat/conversations \
  -H "Content-Type: application/json" \
  -d '{
    "callerId": "john.doe@company.com",
    "userId": "user123",
    "initialMessage": "Hello, I need help with RAG systems"
  }'
```

---

### Get Conversation

Retrieve conversation metadata.

**Endpoint**: `GET /chat/conversations/{conversationId}`

**Path Parameters**:
- `conversationId` (string, required): UUID of the conversation

**Response**: `200 OK`
```json
{
  "conversationId": "uuid",
  "callerId": "john.doe@company.com",
  "userId": "user123",
  "accountId": "acc456",
  "status": "ACTIVE",
  "messageCount": 15,
  "toolCallsCount": 3,
  "totalTokens": 4523,
  "createdAt": "2025-11-23T10:00:00Z",
  "updatedAt": "2025-11-23T10:15:00Z",
  "lastMessageAt": "2025-11-23T10:15:00Z"
}
```

**Error Responses**:
- `404 Not Found`: Conversation not found

**Example**:
```bash
curl http://localhost:8080/api/v1/chat/conversations/123e4567-e89b-12d3-a456-426614174000
```

---

### Send Message (Streaming)

Send a message and receive streaming responses via Server-Sent Events.

**Endpoint**: `POST /chat/conversations/{conversationId}/messages/stream`

**Path Parameters**:
- `conversationId` (string, required): UUID of the conversation

**Request Body**:
```json
{
  "message": "string (required)"
}
```

**Response**: `200 OK` (Content-Type: `text/event-stream`)

Streams multiple event types:

#### Event Types

**1. StatusUpdate**
```
event: StatusUpdate
data: {
  "conversationId": "uuid",
  "status": "Loading conversation...",
  "details": null,
  "timestamp": "2025-11-23T10:00:00Z"
}
```

**2. ToolCallStart**
```
event: ToolCallStart
data: {
  "conversationId": "uuid",
  "toolName": "rag_search",
  "toolCallId": "call_abc123",
  "arguments": {
    "query": "What is RAG?",
    "max_results": 5
  },
  "timestamp": "2025-11-23T10:00:01Z"
}
```

**3. ToolCallResult**
```
event: ToolCallResult
data: {
  "conversationId": "uuid",
  "toolName": "rag_search",
  "toolCallId": "call_abc123",
  "result": "Document: RAG stands for Retrieval-Augmented Generation...\n(Relevance: 0.95)",
  "success": true,
  "timestamp": "2025-11-23T10:00:02Z"
}
```

**4. ResponseChunk**
```
event: ResponseChunk
data: {
  "conversationId": "uuid",
  "content": "Based on the knowledge base, RAG (Retrieval-Augmented Generation) is...",
  "timestamp": "2025-11-23T10:00:03Z"
}
```

**5. Completed**
```
event: Completed
data: {
  "conversationId": "uuid",
  "iterationsUsed": 2,
  "tokensUsed": 1523,
  "timestamp": "2025-11-23T10:00:05Z"
}
```

**6. Error**
```
event: Error
data: {
  "conversationId": "uuid",
  "error": "Tool execution failed",
  "details": "ChromaDB connection timeout",
  "timestamp": "2025-11-23T10:00:03Z"
}
```

**Example**:
```bash
curl -X POST http://localhost:8080/api/v1/chat/conversations/123e4567-e89b-12d3-a456-426614174000/messages/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "What is RAG and how does it work?"}' \
  --no-buffer
```

**JavaScript Example** (EventSource):
```javascript
const eventSource = new EventSource(
  'http://localhost:8080/api/v1/chat/conversations/123e4567-e89b-12d3-a456-426614174000/messages/stream'
);

eventSource.addEventListener('StatusUpdate', (e) => {
  const data = JSON.parse(e.data);
  console.log('Status:', data.status);
});

eventSource.addEventListener('ResponseChunk', (e) => {
  const data = JSON.parse(e.data);
  console.log('Response:', data.content);
});

eventSource.addEventListener('Completed', (e) => {
  const data = JSON.parse(e.data);
  console.log('Done! Iterations:', data.iterationsUsed);
  eventSource.close();
});

eventSource.addEventListener('Error', (e) => {
  const data = JSON.parse(e.data);
  console.error('Error:', data.error);
  eventSource.close();
});
```

---

### List Conversations

Retrieve recent conversations for a caller.

**Endpoint**: `GET /chat/conversations`

**Query Parameters**:
- `callerId` (string, required): Identifier of the caller
- `limit` (integer, optional, default: 10): Maximum number of conversations to return

**Response**: `200 OK`
```json
[
  {
    "conversationId": "uuid1",
    "callerId": "john.doe@company.com",
    "userId": "user123",
    "accountId": "acc456",
    "status": "ACTIVE",
    "messageCount": 15,
    "toolCallsCount": 3,
    "totalTokens": 4523,
    "createdAt": "2025-11-23T10:00:00Z",
    "updatedAt": "2025-11-23T10:15:00Z",
    "lastMessageAt": "2025-11-23T10:15:00Z"
  },
  {
    "conversationId": "uuid2",
    "callerId": "john.doe@company.com",
    "userId": "user123",
    "accountId": "acc456",
    "status": "ACTIVE",
    "messageCount": 8,
    "toolCallsCount": 1,
    "totalTokens": 2145,
    "createdAt": "2025-11-22T14:30:00Z",
    "updatedAt": "2025-11-22T14:45:00Z",
    "lastMessageAt": "2025-11-22T14:45:00Z"
  }
]
```

**Example**:
```bash
curl "http://localhost:8080/api/v1/chat/conversations?callerId=john.doe@company.com&limit=5"
```

---

## Agent Endpoints

### List Available Tools

Get all available tools that can be called during conversations.

**Endpoint**: `GET /agent/tools`

**Response**: `200 OK`
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
          "description": "Maximum number of results to return (default: 5)"
        }
      },
      "required": ["query"]
    }
  }
]
```

**Example**:
```bash
curl http://localhost:8080/api/v1/agent/tools
```

---

### Agent Health Check

Check agent orchestrator health status.

**Endpoint**: `GET /agent/health`

**Response**: `200 OK`
```json
{
  "status": "healthy",
  "service": "agent-orchestrator"
}
```

**Example**:
```bash
curl http://localhost:8080/api/v1/agent/health
```

---

## Health & Monitoring

### Service Ping

Basic service health check.

**Endpoint**: `GET /ping`

**Response**: `200 OK`
```json
{
  "status": "ok",
  "service": "rag-orchestrator",
  "timestamp": "2025-11-23T10:00:00Z"
}
```

**Example**:
```bash
curl http://localhost:8080/api/v1/ping
```

---

### Actuator Endpoints

Spring Boot Actuator endpoints for monitoring:

**Health**: `GET /actuator/health`
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "Sybase",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP"
    }
  }
}
```

**Metrics**: `GET /actuator/metrics`

**Flyway**: `GET /actuator/flyway`

---

## Error Handling

### Standard Error Response

All errors return a consistent format:

```json
{
  "error": "Error type",
  "message": "Detailed error message",
  "timestamp": "2025-11-23T10:00:00Z",
  "path": "/api/v1/chat/conversations/invalid-id"
}
```

### HTTP Status Codes

| Status Code | Description |
|-------------|-------------|
| `200 OK` | Request succeeded |
| `201 Created` | Resource created successfully |
| `400 Bad Request` | Invalid request body or parameters |
| `404 Not Found` | Resource not found (conversation, tool, etc.) |
| `500 Internal Server Error` | Server-side error (tool execution, database, etc.) |
| `503 Service Unavailable` | External service unavailable (DeepSeek, ChromaDB) |

### Common Error Scenarios

**1. Conversation Not Found**
```json
{
  "error": "Conversation not found",
  "message": "No conversation exists with ID: 123e4567-e89b-12d3-a456-426614174000",
  "timestamp": "2025-11-23T10:00:00Z"
}
```
HTTP Status: `404 Not Found`

**2. Invalid Request Body**
```json
{
  "error": "Validation failed",
  "message": "message: must not be blank",
  "timestamp": "2025-11-23T10:00:00Z"
}
```
HTTP Status: `400 Bad Request`

**3. Tool Execution Failed**
```json
{
  "error": "Tool execution failed",
  "message": "ChromaDB query timeout after 30 seconds",
  "timestamp": "2025-11-23T10:00:00Z"
}
```
HTTP Status: `500 Internal Server Error`

**4. DeepSeek API Error**
```json
{
  "error": "External API error",
  "message": "DeepSeek API returned 429: Rate limit exceeded",
  "timestamp": "2025-11-23T10:00:00Z"
}
```
HTTP Status: `503 Service Unavailable`

---

## Examples

### Complete Conversation Flow

**Step 1: Create Conversation**
```bash
CONVERSATION_ID=$(curl -s -X POST http://localhost:8080/api/v1/chat/conversations \
  -H "Content-Type: application/json" \
  -d '{
    "callerId": "demo@example.com",
    "initialMessage": "Hello!"
  }' | jq -r '.conversationId')

echo "Conversation ID: $CONVERSATION_ID"
```

**Step 2: Send Messages with Streaming**
```bash
curl -X POST http://localhost:8080/api/v1/chat/conversations/$CONVERSATION_ID/messages/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "What is Retrieval-Augmented Generation?"}' \
  --no-buffer
```

Expected output (SSE stream):
```
event: StatusUpdate
data: {"conversationId":"...","status":"Loading conversation...","timestamp":"..."}

event: StatusUpdate
data: {"conversationId":"...","status":"Performing initial knowledge search...","timestamp":"..."}

event: StatusUpdate
data: {"conversationId":"...","status":"Iteration 1...","timestamp":"..."}

event: ResponseChunk
data: {"conversationId":"...","content":"Retrieval-Augmented Generation (RAG) is...","timestamp":"..."}

event: Completed
data: {"conversationId":"...","iterationsUsed":1,"tokensUsed":523,"timestamp":"..."}
```

**Step 3: Get Conversation Summary**
```bash
curl http://localhost:8080/api/v1/chat/conversations/$CONVERSATION_ID
```

**Step 4: List All Conversations**
```bash
curl "http://localhost:8080/api/v1/chat/conversations?callerId=demo@example.com"
```

---

### Advanced: Multi-Turn Conversation with Tool Calls

```bash
# Create conversation
CONV_ID=$(curl -s -X POST http://localhost:8080/api/v1/chat/conversations \
  -H "Content-Type: application/json" \
  -d '{"callerId": "advanced@example.com"}' \
  | jq -r '.conversationId')

# Message 1: Simple greeting
curl -X POST http://localhost:8080/api/v1/chat/conversations/$CONV_ID/messages/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Hi there!"}' \
  --no-buffer

# Message 2: Trigger RAG search
curl -X POST http://localhost:8080/api/v1/chat/conversations/$CONV_ID/messages/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Tell me about vector databases"}' \
  --no-buffer

# Message 3: Follow-up question (uses context)
curl -X POST http://localhost:8080/api/v1/chat/conversations/$CONV_ID/messages/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "How do they compare to traditional databases?"}' \
  --no-buffer

# Check final stats
curl http://localhost:8080/api/v1/chat/conversations/$CONV_ID | jq
```

---

### Testing with Python

```python
import requests
import json
import sseclient

BASE_URL = "http://localhost:8080/api/v1"

# Create conversation
response = requests.post(f"{BASE_URL}/chat/conversations", json={
    "callerId": "python-test@example.com",
    "initialMessage": "Hello from Python!"
})
conversation_id = response.json()["conversationId"]
print(f"Created conversation: {conversation_id}")

# Send message with streaming
url = f"{BASE_URL}/chat/conversations/{conversation_id}/messages/stream"
response = requests.post(url, json={
    "message": "What is machine learning?"
}, stream=True)

client = sseclient.SSEClient(response)
for event in client.events():
    data = json.loads(event.data)
    print(f"Event: {event.event}")
    print(f"Data: {data}")

    if event.event == "Completed":
        print(f"Done! Used {data['iterationsUsed']} iterations")
        break
```

---

### Monitoring and Debugging

**Check service health**:
```bash
curl http://localhost:8080/actuator/health | jq
```

**Monitor database migrations**:
```bash
curl http://localhost:8080/actuator/flyway | jq
```

**List available tools**:
```bash
curl http://localhost:8080/api/v1/agent/tools | jq
```

**Query database directly** (for debugging):
```sql
-- Find recent conversations
SELECT conversation_id, caller_id, message_count, tool_calls_count, created_at
FROM conversations
WHERE created_at > DATEADD(hour, -1, GETDATE())
ORDER BY created_at DESC;

-- Get message history for a conversation
SELECT role, LEFT(content, 100) as content_preview, created_at
FROM conversation_messages
WHERE conversation_id = 'YOUR_CONVERSATION_ID'
ORDER BY created_at ASC;
```

---

## Rate Limits & Quotas

**Current Version**: No rate limits (v0.0.1-SNAPSHOT)

**Future Versions**: Rate limiting will be implemented:
- 100 requests per minute per caller_id
- 10 concurrent streaming connections per caller_id
- Max 10,000 tokens per message

---

## Versioning

API versioning is handled via URL path:
- Current: `/api/v1/...`
- Future: `/api/v2/...` (when breaking changes are introduced)

The service follows semantic versioning:
- Major: Breaking API changes
- Minor: Backward-compatible features
- Patch: Bug fixes

---

## Support & Troubleshooting

**Logs**: Check application logs for detailed error information:
```bash
./gradlew bootRun | grep ERROR
```

**Database Issues**: Verify connection:
```bash
curl http://localhost:8080/actuator/health | jq '.components.db'
```

**External Services**: Check DeepSeek and ChromaDB connectivity:
```bash
# ChromaDB
curl http://localhost:8000/api/v1/heartbeat

# DeepSeek (requires API key)
curl https://api.deepseek.com/v1/models \
  -H "Authorization: Bearer YOUR_API_KEY"
```

---

**Next**: See [TOOLS_GUIDE.md](TOOLS_GUIDE.md) for building custom tools.