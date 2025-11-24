# RAG Ingestion Examples

This directory contains example document schemas for different use cases.

## Available Examples

1. **[simple-faq.yml](./simple-faq.yml)** - Basic FAQ document structure
2. **[api-documentation.yml](./api-documentation.yml)** - API endpoint documentation
3. **[workflow-guide.yml](./workflow-guide.yml)** - Step-by-step procedural guide
4. **[reference-doc.yml](./reference-doc.yml)** - Reference documentation with sections
5. **[tutorial.yml](./tutorial.yml)** - Learning tutorial with lessons

## How to Use These Examples

### 1. Copy and Customize

```bash
# Copy an example
cp docs/examples/simple-faq.yml my-docs/my-first-document.yml

# Edit with your content
vim my-docs/my-first-document.yml
```

### 2. Validate Your Document

```python
import yaml

def validate_document(file_path: str):
    """Basic validation for knowledge base documents."""
    with open(file_path) as f:
        doc = yaml.safe_load(f)

    # Check required fields
    required = ['metadata', 'content']
    assert all(k in doc for k in required), "Missing top-level keys"

    # Check metadata
    meta_required = ['id', 'title', 'category', 'version', 'status']
    assert all(k in doc['metadata'] for k in meta_required), "Missing metadata fields"

    # Check content
    assert 'body' in doc['content'], "Missing content.body"

    print(f"✅ {file_path} is valid")

validate_document('my-docs/my-first-document.yml')
```

### 3. Test Chunking

```python
from chunking import chunk_document

# Load your document
with open('my-docs/my-first-document.yml') as f:
    doc = yaml.safe_load(f)

# Chunk it
chunks = chunk_document(doc)

# Inspect chunks
for chunk in chunks:
    print(f"ID: {chunk['id']}")
    print(f"Type: {chunk['metadata']['chunk_type']}")
    print(f"Preview: {chunk['text'][:100]}...")
    print("---")
```

## Creating Your Own Template

1. Start with the closest example
2. Add your custom metadata fields
3. Adjust the content structure
4. Test chunking behavior
5. Document your schema (see [SCHEMA_CUSTOMIZATION.md](../SCHEMA_CUSTOMIZATION.md))

## Schema Comparison

| Example | Category | Chunking Strategy | Best For |
|---------|----------|-------------------|----------|
| simple-faq | faq | Per Q&A pair | Customer support, help docs |
| api-documentation | api | Per endpoint (H3) | REST APIs, SDK docs |
| workflow-guide | workflow | Per step | SOPs, procedures, guides |
| reference-doc | reference | Per section (H2) | Concept docs, glossaries |
| tutorial | tutorial | Per lesson (H2) | Learning content, courses |
