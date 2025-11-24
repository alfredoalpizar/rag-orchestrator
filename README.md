# RAG Orchestrator

An intelligent RAG (Retrieval-Augmented Generation) service with tool-calling loop orchestration, multi-model support, and ChromaDB integration.

## Features

- **Tool-Calling Loop**: Iterative reasoning with automatic tool selection (up to 10 iterations)
- **Multi-Model Support**: Pluggable model strategies for DeepSeek and Qwen models
- **RAG Integration**: Hybrid approach with initial + tool-based retrieval from ChromaDB
- **Conversation Management**: Context-aware multi-turn dialogues with SQL persistence
- **Streaming Support**: Real-time SSE streaming of responses and tool executions
- **Extensible Tools**: Easy to add custom tools via Spring components
- **Audit Built-In**: SQL-based conversation tracking with full message history
- **Rolling Window Context**: Token-efficient context management with configurable window size

## Quick Start

### Prerequisites
- JDK 21
- Gradle 8.10+ (wrapper included)
- Docker (for ChromaDB)

### Setup

```bash
# Clone and enter directory
git clone https://github.com/alfredoalpizar/rag-orchestrator.git
cd rag-orchestrator

# Create .env file
cp .env.example .env
# Edit .env with your API key: DEEPSEEK_API_KEY=sk-your-key

# Start ChromaDB
docker run -d -p 8000:8000 chromadb/chroma

# Build and run
./gradlew build -x test
./gradlew bootRun
```

## Model Strategies

The orchestrator supports multiple LLM providers through a pluggable strategy pattern. Configure via `loop.model-strategy` in `application.yml`:

| Strategy | Config Value | Model | Best For |
|----------|-------------|-------|----------|
| DeepSeek Single | `deepseek_single` | deepseek-chat | General purpose, fast responses |
| Qwen Thinking | `qwen_single_thinking` | qwen-max | Complex reasoning with traces |
| Qwen Instruct | `qwen_single_instruct` | qwen-plus | Fast instruction-following |

```yaml
# application.yml
loop:
  model-strategy: deepseek_single  # or qwen_single_thinking, qwen_single_instruct
```

Each strategy requires its own API key:
- DeepSeek: `DEEPSEEK_API_KEY`
- Qwen: `QWEN_API_KEY`

## API Endpoints

### Health Check

```bash
GET /api/v1/ping
```
```json
{"status": "ok", "service": "rag-orchestrator", "timestamp": "2025-01-15T10:00:00Z"}
```

### Create Conversation

```bash
POST /api/v1/chat/conversations
Content-Type: application/json

{"callerId": "user@example.com", "userId": "user123"}
```
```json
{
  "conversationId": "550e8400-e29b-41d4-a716-446655440000",
  "callerId": "user@example.com",
  "status": "ACTIVE",
  "messageCount": 0,
  "createdAt": "2025-01-15T10:00:00Z"
}
```

### Send Message (SSE Streaming)

```bash
POST /api/v1/chat/conversations/{conversationId}/messages/stream
Content-Type: application/json
Accept: text/event-stream

{"message": "What is RAG?"}
```

**SSE Response Stream:**
```
event: StatusUpdate
data: {"status": "Loading conversation...", "timestamp": "..."}

event: StatusUpdate
data: {"status": "Performing initial knowledge search...", "timestamp": "..."}

event: ToolCallStart
data: {"toolName": "rag_search", "arguments": {"query": "RAG"}, "timestamp": "..."}

event: ToolCallResult
data: {"toolName": "rag_search", "result": "...", "success": true, "timestamp": "..."}

event: ResponseChunk
data: {"content": "RAG stands for Retrieval-Augmented Generation...", "timestamp": "..."}

event: Completed
data: {"iterationsUsed": 2, "tokensUsed": 1523, "timestamp": "..."}
```

### Get Conversation

```bash
GET /api/v1/chat/conversations/{conversationId}
```

### List Conversations

```bash
GET /api/v1/chat/conversations?callerId=user@example.com&limit=10
```

### List Available Tools

```bash
GET /api/v1/agent/tools
```
```json
[
  {"name": "rag_search", "description": "Search the knowledge base", "parameters": {...}}
]
```

## Configuration

Key settings in `application.yml`:

```yaml
loop:
  max-iterations: 10
  model-strategy: deepseek_single
  streaming:
    show-tool-calls: true
    show-reasoning-traces: false

conversation:
  storage-mode: in-memory  # or sql
  rolling-window-size: 20

deepseek:
  api:
    api-key: ${DEEPSEEK_API_KEY}

qwen:
  api:
    api-key: ${QWEN_API_KEY}

chromadb:
  base-url: http://localhost:8000
  collection-name: knowledge_base
```

## Storage Modes

- **In-Memory**: Fast development/testing, data lost on restart
- **SQL**: Production-ready with full audit trail (Sybase ASE)

## Documentation

- [API_SPEC.md](API_SPEC.md) - Complete REST API reference
- [IMPLEMENTATION_SPEC.md](IMPLEMENTATION_SPEC.md) - Technical architecture
- [TOOLS_GUIDE.md](TOOLS_GUIDE.md) - Building custom tools
- [CONTEXT_MANAGEMENT_GUIDE.md](CONTEXT_MANAGEMENT_GUIDE.md) - Conversation context strategies
- [STREAMING_GUIDE.md](STREAMING_GUIDE.md) - SSE streaming details
- [STRATEGY_IMPLEMENTATION_GUIDE.md](STRATEGY_IMPLEMENTATION_GUIDE.md) - Adding new model strategies

## License

MIT License - see [LICENSE](LICENSE) for details.
