# Sample Documents

This directory contains example documents demonstrating the simplified document schema for RAG ingestion.

## Document Schema

### Required Fields

```yaml
metadata:
  id: string          # Unique identifier (e.g., "user_registration_workflow")
  title: string       # Human-readable title
  category: string    # workflow | faq | reference | troubleshooting
  status: string      # published | draft | archived

content:
  body: string        # Main content in Markdown
```

### Optional Fields

```yaml
metadata:
  lastUpdated: string # ISO date (e.g., "2025-11-26")
  tags: string[]      # Search tags

content:
  summary: string     # Brief description
```

## Sample Documents

### `workflow_user_registration.yml`
Demonstrates workflow chunking (by steps). Each step becomes a separate chunk.

### `faq_password_reset.yml`
Demonstrates FAQ chunking (by Q&A pairs). Each question-answer pair becomes a chunk.

### `reference_api_authentication.yml`
Demonstrates reference chunking (by H2 sections). Each section becomes a chunk.

### `troubleshooting_login_errors.yml`
Demonstrates troubleshooting chunking (by issues). Each issue becomes a chunk.

## Ingesting Documents

### Via API

```bash
# Ingest all documents
curl -X POST http://localhost:8080/api/v1/ingest/documents

# Ingest single document
curl -X POST http://localhost:8080/api/v1/ingest/document \
  -H "Content-Type: application/json" \
  -d '{"filePath": "./docs/samples/workflow_user_registration.yml"}'

# Get ingestion status
curl http://localhost:8080/api/v1/ingest/status
```

### Via Configuration

Set `ingestion.auto-ingest-on-startup: true` in application.yml to automatically ingest on startup.

## Creating Your Own Documents

1. Copy one of the sample files as a template
2. Update the required fields (id, title, category, status)
3. Set `status: published` (only published documents are indexed)
4. Write your content using Markdown
5. Save as `.yml`, `.yaml`, or `.json`
6. Place in this directory (or subdirectories if using recursive mode)
7. Ingest via API or restart with auto-ingest enabled

## Chunking Strategies

- **workflow**: Chunks by `### Step N: Title` headings
- **faq**: Chunks by `## Question?` headings (questions ending with ?)
- **reference**: Chunks by `## Section` headings
- **troubleshooting**: Chunks by `## Issue` headings

## Best Practices

- Use descriptive IDs (e.g., `user_registration_workflow` not `doc1`)
- Keep titles concise but informative
- Use appropriate category for optimal chunking
- Add relevant tags for better searchability
- Keep chunks focused (use appropriate headings)
- Test retrieval quality after ingestion
