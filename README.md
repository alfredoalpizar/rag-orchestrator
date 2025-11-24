# RAG Orchestrator

A reference implementation of an agentic RAG (Retrieval-Augmented Generation) loop with tool-calling orchestration. Use this as a learning resource and starting point for building your own AI-powered applications with domain-specific knowledge bases.

## What This Demonstrates

- **Agentic Loop Pattern**: How to implement iterative LLM reasoning with tool selection
- **RAG Integration**: Connecting vector search (ChromaDB) to augment LLM responses
- **Multi-Model Strategies**: Pluggable architecture for different LLM providers
- **Streaming Architecture**: Real-time SSE event streaming for tool calls and responses
- **Context Management**: Rolling window approach to handle conversation history efficiently
- **Tool Registry**: Pattern for adding custom tools the agent can invoke

## Quick Start

### Prerequisites
- JDK 21
- Docker (for ChromaDB)

### Setup

```bash
git clone https://github.com/alfredoalpizar/rag-orchestrator.git
cd rag-orchestrator

cp .env.example .env
# Edit .env: DEEPSEEK_API_KEY=sk-your-key

docker run -d -p 8000:8000 chromadb/chroma

./gradlew build -x test
./gradlew bootRun
```

## Model Strategies

Pluggable strategy pattern for different LLM providers. Configure via `loop.model-strategy`:

| Strategy | Config Value | Model |
|----------|-------------|-------|
| DeepSeek Single | `deepseek_single` | deepseek-chat |
| Qwen Thinking | `qwen_single_thinking` | qwen-max |
| Qwen Instruct | `qwen_single_instruct` | qwen-plus |

See [STRATEGY_IMPLEMENTATION_GUIDE.md](STRATEGY_IMPLEMENTATION_GUIDE.md) to add your own.

## API Endpoints

### Create Conversation

```http
POST /api/v1/chat/conversations
{"callerId": "user@example.com"}
```

### Send Message (SSE Streaming)

```http
POST /api/v1/chat/conversations/{id}/messages/stream
{"message": "What is RAG?"}
```

```
event: StatusUpdate
data: {"status": "Performing initial knowledge search..."}

event: ToolCallStart
data: {"toolName": "rag_search", "arguments": {"query": "RAG"}}

event: ToolCallResult
data: {"toolName": "rag_search", "result": "...", "success": true}

event: ResponseChunk
data: {"content": "RAG stands for Retrieval-Augmented Generation..."}

event: Completed
data: {"iterationsUsed": 2, "tokensUsed": 1523}
```

### Other Endpoints

- `GET /api/v1/ping` - Health check
- `GET /api/v1/chat/conversations/{id}` - Get conversation
- `GET /api/v1/chat/conversations?callerId=...` - List conversations
- `GET /api/v1/agent/tools` - List available tools

## Adapting for Your Project

1. **Add your knowledge base**: Populate ChromaDB with your domain documents
2. **Create custom tools**: See [TOOLS_GUIDE.md](TOOLS_GUIDE.md) for the tool interface pattern
3. **Choose/add model strategy**: Swap providers or add new ones via the strategy pattern
4. **Configure storage**: Use SQL mode for production with audit trails

## Documentation

- [IMPLEMENTATION_SPEC.md](IMPLEMENTATION_SPEC.md) - Technical architecture
- [API_SPEC.md](API_SPEC.md) - Complete API reference
- [TOOLS_GUIDE.md](TOOLS_GUIDE.md) - Building custom tools
- [STREAMING_GUIDE.md](STREAMING_GUIDE.md) - SSE implementation details
- [STRATEGY_IMPLEMENTATION_GUIDE.md](STRATEGY_IMPLEMENTATION_GUIDE.md) - Adding model strategies
- [CONTEXT_MANAGEMENT_GUIDE.md](CONTEXT_MANAGEMENT_GUIDE.md) - Conversation context handling

## License

MIT
