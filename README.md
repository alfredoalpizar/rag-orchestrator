# RAG Orchestrator

An intelligent RAG (Retrieval-Augmented Generation) service with tool-calling loop orchestration, powered by DeepSeek and ChromaDB.

**Status**: Core implementation complete (v0.0.1-SNAPSHOT) - Ready for testing and deployment

## Features

- **Tool-Calling Loop**: Iterative reasoning with automatic tool selection (up to 10 iterations)
- **RAG Integration**: Hybrid approach with initial + tool-based retrieval from ChromaDB
- **Conversation Management**: Context-aware multi-turn dialogues with SQL persistence
- **Streaming Support**: Real-time SSE streaming of responses and tool executions
- **Extensible Tools**: Easy to add custom tools/functions via Spring components
- **Multiple Strategies**: Different output formats via finalizer strategies
- **Audit Ready**: SQL-based conversation tracking for compliance with full history
- **Rolling Window Context**: Token-efficient context management with configurable window size

## Architecture

### Storage Strategy (Phased Approach)

**Phase 1 (Current - POC)**:
- SQL (Sybase ASE) for conversation metadata and messages
- In-memory mode available for testing
- Fast queries by caller_id, user_id, timestamps
- Perfect for audits and compliance

**Phase 2 (Future - Scale)**:
- Add Redis cache for active conversations (< 1 hour old)
- Reduces latency to sub-millisecond for hot data

**Phase 3 (Future - Archive)**:
- S3 for long-term storage (> 90 days old)
- Lifecycle policies for cost optimization

## Quick Start

### Prerequisites
- **JDK 21** (tested with OpenJDK 21)
- **Gradle 8.10+** (wrapper included)
- **Docker** (for ChromaDB)
- **Database** (optional): Sybase ASE or use in-memory mode for testing

### Setup

1. **Clone the repository**
```bash
git clone https://github.com/alfredoalpizar/rag-orchestrator.git
cd rag-orchestrator
```

2. **Create `.env` file**
```bash
cp .env.example .env
```

Edit `.env` with your configuration:
```bash
# Required
DEEPSEEK_API_KEY=sk-your-api-key-here

# Optional (use in-memory mode for quick testing)
CONVERSATION_STORAGE_MODE=in-memory

# For production (SQL mode)
# DB_URL=jdbc:sybase:Tds:localhost:5000/your_database
# DB_USERNAME=your_username
# DB_PASSWORD=your_password
```

3. **Start ChromaDB**
```bash
docker run -d -p 8000:8000 chromadb/chroma
```

4. **Run database migrations** (skip if using in-memory mode)
```bash
./gradlew flywayMigrate
```

5. **Build the project**
```bash
./gradlew build -x test
```

6. **Run the service**
```bash
./gradlew bootRun
```

7. **Test it**
```bash
# Health check
curl http://localhost:8080/api/v1/ping

# Create a conversation
curl -X POST http://localhost:8080/api/v1/chat/conversations \
  -H "Content-Type: application/json" \
  -d '{
    "callerId": "demo@example.com",
    "initialMessage": "Hello!"
  }'
```

**Expected response:**
```json
{
  "conversationId": "123e4567-e89b-12d3-a456-426614174000",
  "callerId": "demo@example.com",
  "status": "ACTIVE",
  "messageCount": 0,
  "toolCallsCount": 0,
  "totalTokens": 0,
  "createdAt": "2025-11-23T10:00:00Z",
  "updatedAt": "2025-11-23T10:00:00Z"
}
```

## Storage Modes

### SQL Mode (Recommended for Production)
- Best for: Production, audit trails, compliance
- Queries by caller_id, user_id, timestamps
- Automatic cleanup of old conversations
- Integration with existing BI tools
- Full conversation history retention

### In-Memory Mode (Quick Testing)
- Best for: Development, testing, demos
- Fast and simple
- No database setup required
- Data lost on restart

## Configuration

Edit `application.yml` or use environment variables:

```yaml
conversation:
  storage-mode: sql  # or 'in-memory'
  rolling-window-size: 20  # Keep last N messages in context
  ttl-hours: 720  # 30 days before archival

loop:
  max-iterations: 10  # Maximum tool-calling iterations
  use-reasoning-model: false  # Use deepseek-reasoner vs deepseek-chat

deepseek:
  api-key: ${DEEPSEEK_API_KEY}

chromadb:
  base-url: http://localhost:8000
  collection-name: knowledge_base
```

## API Examples

### Complete Conversation Flow

```bash
# 1. Create a conversation
CONV_ID=$(curl -s -X POST http://localhost:8080/api/v1/chat/conversations \
  -H "Content-Type: application/json" \
  -d '{
    "callerId": "demo@example.com",
    "userId": "user123"
  }' | jq -r '.conversationId')

echo "Created conversation: $CONV_ID"

# 2. Send a message with streaming
curl -N -X POST http://localhost:8080/api/v1/chat/conversations/$CONV_ID/messages/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "What is Retrieval-Augmented Generation?"}' \
  --no-buffer

# 3. Get conversation details
curl http://localhost:8080/api/v1/chat/conversations/$CONV_ID | jq

# 4. List all conversations for a caller
curl "http://localhost:8080/api/v1/chat/conversations?callerId=demo@example.com&limit=10" | jq
```

### Streaming Events

When you send a message, you'll receive Server-Sent Events (SSE) like:

```
event: StatusUpdate
data: {"conversationId":"...","status":"Loading conversation...","timestamp":"..."}

event: StatusUpdate
data: {"conversationId":"...","status":"Performing initial knowledge search...","timestamp":"..."}

event: ToolCallStart
data: {"conversationId":"...","toolName":"rag_search","toolCallId":"call_123","arguments":{...},"timestamp":"..."}

event: ToolCallResult
data: {"conversationId":"...","toolName":"rag_search","result":"Document content...","success":true,"timestamp":"..."}

event: ResponseChunk
data: {"conversationId":"...","content":"Based on the knowledge base, RAG is...","timestamp":"..."}

event: Completed
data: {"conversationId":"...","iterationsUsed":2,"tokensUsed":1523,"timestamp":"..."}
```

### List Available Tools

```bash
curl http://localhost:8080/api/v1/agent/tools | jq
```

### Audit Queries (SQL Mode)

```sql
-- Find all conversations by caller in last 7 days
SELECT * FROM conversations
WHERE caller_id = 'john.doe@company.com'
AND created_at > DATEADD(day, -7, GETDATE())
ORDER BY created_at DESC;

-- Get conversation message history
SELECT role, content, created_at
FROM conversation_messages
WHERE conversation_id = 'your-conversation-id'
ORDER BY created_at ASC;

-- Statistics by user
SELECT
    caller_id,
    COUNT(*) as conversation_count,
    SUM(message_count) as total_messages,
    SUM(tool_calls_count) as total_tool_calls,
    SUM(total_tokens) as total_tokens
FROM conversations
WHERE created_at > DATEADD(day, -30, GETDATE())
GROUP BY caller_id
ORDER BY conversation_count DESC;
```

## Documentation

### Getting Started
- [PROJECT_BOOTSTRAP.md](PROJECT_BOOTSTRAP.md) - Complete project setup and initialization guide
- [CLAUDE.md](CLAUDE.md) - AI assistant guidance for development

### Implementation Guides
- [IMPLEMENTATION_SPEC.md](IMPLEMENTATION_SPEC.md) - Technical architecture and complete implementation specification
- [API_SPEC.md](API_SPEC.md) - REST API reference with examples and use cases
- [TOOLS_GUIDE.md](TOOLS_GUIDE.md) - Building custom tools for the orchestrator
- [CONTEXT_MANAGEMENT_GUIDE.md](CONTEXT_MANAGEMENT_GUIDE.md) - Conversation context and storage strategies
- [STREAMING_GUIDE.md](STREAMING_GUIDE.md) - SSE streaming implementation details

## Project Status

### Completed (v0.0.1-SNAPSHOT)

**Core Architecture**
- Spring Boot 3.2.1 + Kotlin + Gradle setup
- Reactive WebFlux for non-blocking I/O
- JPA repositories with Flyway migrations
- Configuration properties with environment variable support

**External Integrations**
- DeepSeek client (chat API with retry logic)
- ChromaDB client (vector search for RAG)
- Sybase ASE database support

**Service Layer**
- Tool-calling loop orchestrator (max 10 iterations)
- Context manager with rolling window (configurable)
- RAG tool for knowledge base retrieval
- Tool registry with auto-discovery
- Finalizer strategies (direct, structured)

**API Layer**
- REST endpoints for conversation management
- Server-Sent Events (SSE) streaming
- Error handling and validation
- Health check endpoints

**Data Management**
- SQL-based conversation persistence
- In-memory mode for testing
- Rolling window context (prevents unbounded growth)
- Token estimation and tracking

### In Progress / Known Issues

- Test suite needs enhancement (H2 + Flyway compatibility issue)
- Word-by-word streaming (currently sends complete responses)
- Context compression/summarization strategies
- Additional custom tools

### Roadmap

**Phase 2 (Performance)**
- Redis cache for active conversations
- Improved token counting (tiktoken integration)
- Context compression strategies
- More custom tools (weather, calculator, etc.)

**Phase 3 (Scale)**
- S3 archival for old conversations
- Background cleanup jobs
- Rate limiting and quotas
- Multi-model support

**Phase 4 (Production)**
- Authentication and authorization
- Comprehensive test suite
- Monitoring and metrics (Prometheus)
- API documentation (Swagger/OpenAPI)
- Docker compose for local development

## Troubleshooting

### Build Issues
```bash
# Clean build
./gradlew clean build --refresh-dependencies

# Skip tests if H2 compatibility issue persists
./gradlew build -x test
```

### Port Already in Use
```bash
# Change port via environment variable
export SERVER_PORT=8081
./gradlew bootRun
```

### ChromaDB Connection Failed
```bash
# Check if ChromaDB is running
curl http://localhost:8000/api/v1/heartbeat

# Start ChromaDB if needed
docker run -d -p 8000:8000 chromadb/chroma
```

### Database Issues (SQL Mode)
```bash
# Use in-memory mode for testing
export CONVERSATION_STORAGE_MODE=in-memory
./gradlew bootRun

# Or check Flyway migration status
./gradlew flywayInfo

# Repair migration state if needed
./gradlew flywayRepair
```

### DeepSeek API Issues
```bash
# Test API key
curl https://api.deepseek.com/v1/models \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY"

# Check logs for detailed error messages
./gradlew bootRun | grep ERROR
```

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

MIT License - see [LICENSE](LICENSE) for details.

## Support

For questions or issues:
- Open an issue on GitHub
- Check the documentation in the project root
- Review the guides: `IMPLEMENTATION_SPEC.md`, `API_SPEC.md`, etc.
