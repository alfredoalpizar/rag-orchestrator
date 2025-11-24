# RAG Document Ingestion Guide

**Version:** 1.0.0
**Date:** 2025-11-24
**Purpose:** Generic technical guide for ingesting structured documents into ChromaDB with embeddings

---

## Table of Contents

1. [Overview](#overview)
2. [Schema Design Principles](#schema-design-principles)
3. [Embedding Model Selection](#embedding-model-selection)
4. [Architecture](#architecture)
5. [Chunking Strategies](#chunking-strategies)
6. [ChromaDB Schema Design](#chromadb-schema-design)
7. [Embedding Service Implementation](#embedding-service-implementation)
8. [Ingestion Pipeline Patterns](#ingestion-pipeline-patterns)
9. [Query-Time Retrieval](#query-time-retrieval)
10. [Deployment Patterns](#deployment-patterns)
11. [Testing & Validation](#testing--validation)

---

## Overview

### Goal

Transform structured documents (YAML, JSON, Markdown, etc.) into semantically searchable chunks in ChromaDB using modern embedding models for high-quality retrieval.

### Core Flow

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Documents     │───▶│  Ingestion Svc  │───▶│    ChromaDB     │
│   (Storage)     │    │  + Embeddings   │    │   (Vectors)     │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                                      │
                                                      ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   User Query    │───▶│  RAG Query Svc  │───▶│   LLM Response  │
│   + Context     │    │  + Embeddings   │    │  (Contextualized)│
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

### Key Principles

1. **Same embedding model for ingestion AND queries** (critical for vector compatibility!)
2. **Content-aware chunking** (different strategies for different document types)
3. **Metadata enrichment** for powerful pre-filtering before semantic search
4. **Access control filtering** at query time for role-based access
5. **Version control** for document updates and re-indexing

---

## Schema Design Principles

### Recommended Document Structure

Design your document schema with these key areas:

```yaml
# Generic document template
metadata:
  # Identity (required)
  id: string                    # Unique identifier
  title: string                 # Human-readable title
  category: string              # Document type (workflow, reference, faq, etc.)
  version: string               # Semantic versioning

  # Lifecycle (recommended)
  status: enum                  # draft | review | published | archived
  owner: string                 # Team or individual owner
  lastUpdated: string           # ISO 8601 date

  # Organization (optional but useful)
  tags: string[]                # Searchable tags
  organizationPath: string      # Hierarchical categorization (e.g., "product.feature.subfeature")

  # Access Control (critical for multi-tenant systems)
  accessRoles: string[]         # Who can see this document
  platforms: string[]           # Where this applies

  # Relationships (optional)
  relatedDocuments: object[]    # Links to other docs

content:
  summary: string               # Brief description (always embedded)
  body: string                  # Main content (chunked before embedding)
```

### Critical Schema Rules

**1. Only Index Published/Approved Documents**

```python
# Always filter by status at ingestion time
if document['metadata']['status'] not in ['published', 'approved']:
    logger.info(f"Skipping {doc_id} - status is {status}")
    return  # Do NOT index drafts or archived docs
```

**2. Use Consistent ID Format**

```
Pattern: {category}_{descriptive_name}
Examples:
  - workflow_user_registration
  - reference_api_authentication
  - faq_password_reset
  - troubleshooting_login_errors
```

**3. Design for Hierarchical Filtering**

```
Instead of: category = "API Documentation"
Use: organizationPath = "developer.api.authentication"

Enables queries like:
  - All developer docs: "developer.*"
  - All API docs: "developer.api.*"
  - Specific topic: "developer.api.authentication"
```

---

## Embedding Model Selection

### Popular Embedding Models (2025)

| Model | Parameters | Dimensions | Context Length | Use Case |
|-------|------------|------------|----------------|----------|
| **Qwen3-Embedding-0.6B** | 0.6B | 1024 | 32K tokens | POC / Resource-efficient |
| **Qwen3-Embedding-4B** | 4B | 2560 | 32K tokens | Production balance |
| **Qwen3-Embedding-8B** | 8B | 4096 | 32K tokens | Maximum quality |
| **OpenAI text-embedding-3-small** | - | 1536 | 8K tokens | Cloud API, cost-effective |
| **OpenAI text-embedding-3-large** | - | 3072 | 8K tokens | Cloud API, high quality |
| **Cohere embed-english-v3.0** | - | 1024 | 512 tokens | Specialized for English |

### Selection Criteria

**For POC/Development:**
- Use smaller models (0.6B-1B parameters)
- Can run on CPU or modest GPU
- Fast iteration cycles

**For Production:**
- Balance quality vs. cost vs. latency
- Consider cloud APIs for simplicity (OpenAI, Cohere)
- Self-hosted for data privacy (Qwen3, open-source models)

**Chosen Configuration Example (Qwen3):**
```yaml
embedding:
  model: "Qwen/Qwen3-Embedding-0.6B"
  dimensions: 1024              # Use full dimensions for best quality
  max_context_length: 8192      # Per-chunk limit
  normalize: true               # L2 normalize embeddings for cosine similarity
  instruction_aware: true       # Use task-specific instructions (if supported)
```

### Matryoshka Representation Learning (MRL)

Some models (Qwen3, OpenAI) support **flexible dimensions**:
- Embed at high dimensions (e.g., 1024)
- Truncate to lower dimensions at search time (e.g., 512, 256)
- Tradeoff: Speed vs. Quality

For most use cases: **Use full dimensions**. Optimize later if needed.

---

## Architecture

### Component Diagram

```
┌───────────────────────────────────────────────────────────────────┐
│                      Document Storage                             │
│                (S3, Git, Filesystem, CMS)                         │
│   /workflows/user_onboarding.yml                                  │
│   /reference/api_guide.yml                                        │
│   /faqs/common_questions.yml                                      │
└──────────────────────┬────────────────────────────────────────────┘
                       │
                       │ Trigger: Event / Scheduled / Manual
                       ▼
┌───────────────────────────────────────────────────────────────────┐
│                   Ingestion Service                               │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────────────────┐ │
│  │   Parser    │──▶│   Chunker   │──▶│  Embedding Client       │ │
│  │ + Validator │   │ (by type)   │   │  (OpenAI-compatible)    │ │
│  └─────────────┘   └─────────────┘   └────────────┬────────────┘ │
│                                                    │              │
│  ┌─────────────────────────────────────────────────▼────────────┐ │
│  │                    ChromaDB Client                           │ │
│  │              (Upsert chunks + metadata)                      │ │
│  └──────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────┘
                       │
                       ▼
┌───────────────────────────────────────────────────────────────────┐
│                        ChromaDB                                   │
│  Collection: knowledge_base_v1                                    │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │ id: "workflow_user_onboarding::step_1"                      │  │
│  │ embedding: [0.123, -0.456, ...] (1024 dims)                 │  │
│  │ document: "Step 1: Collect user information..."             │  │
│  │ metadata: {                                                 │  │
│  │   doc_id, category, access_roles, tags, ...                │  │
│  │ }                                                           │  │
│  └─────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────┘
```

### Embedding Service Deployment Options

#### Option A: Ollama (Local, Easiest for POC)

```bash
# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Pull embedding model
ollama pull qwen3-embedding:0.6b

# API available at http://localhost:11434/v1 (OpenAI-compatible)
```

#### Option B: HuggingFace Text Embeddings Inference (Production)

```bash
docker run -p 8080:80 -v hf_cache:/data \
  ghcr.io/huggingface/text-embeddings-inference:1.7.2 \
  --model-id Qwen/Qwen3-Embedding-0.6B \
  --dtype float16

# API available at http://localhost:8080
```

#### Option C: vLLM (High-Performance Production)

```bash
pip install vllm>=0.8.5

vllm serve Qwen/Qwen3-Embedding-0.6B \
  --served-model-name qwen3-embedding

# OpenAI-compatible API at http://localhost:8000/v1
```

#### Option D: Cloud APIs (OpenAI, Cohere, DeepInfra)

```python
from openai import OpenAI

client = OpenAI(api_key="your-api-key")

response = client.embeddings.create(
    model="text-embedding-3-small",
    input="Your text to embed"
)
```

---

## Chunking Strategies

### Critical Principle

**Different document types require different chunking strategies.**

### Strategy Selection Matrix

| Document Type | Chunking Unit | Reasoning | Chunks/Doc |
|---------------|---------------|-----------|------------|
| **Workflows** | Per step | Each step is atomic action | 1 overview + N steps |
| **Reference** | Per section (H2) | Self-contained concepts | 2-10 sections |
| **FAQs** | Per Q&A pair | Atomic question-answer | 1-20 pairs |
| **Troubleshooting** | Per issue | Complete diagnostic path | 1-5 issues |
| **API Docs** | Per endpoint | Each endpoint independent | 1 overview + N endpoints |
| **Tutorials** | Per major step | Learning progression | 3-10 steps |
| **Release Notes** | Per version | Version-specific changes | 1 per version |

### Implementation Pattern

```python
def chunk_document(doc: dict) -> List[Dict]:
    """
    Chunk document based on type/category.
    Returns list of chunks with text and metadata.
    """
    category = doc['metadata']['category']
    base_metadata = extract_base_metadata(doc)

    # Route to appropriate chunking strategy
    if category == 'workflow':
        return chunk_by_steps(doc, base_metadata)
    elif category == 'reference':
        return chunk_by_headings(doc, base_metadata, heading_level=2)
    elif category == 'faq':
        return chunk_by_qa_pairs(doc, base_metadata)
    elif category == 'troubleshooting':
        return chunk_by_issues(doc, base_metadata)
    elif category == 'api':
        return chunk_by_endpoints(doc, base_metadata)
    else:
        # Fallback: semantic or fixed-size chunking
        return chunk_semantic(doc, base_metadata, max_tokens=512)
```

### Example: Workflow Chunking

**Input Document:**
```yaml
metadata:
  id: workflow_user_registration
  title: "User Registration Process"
  category: workflow

content:
  summary: "Step-by-step guide for registering new users"
  body: |
    ## Overview
    This workflow guides you through user registration...

    ### Step 1: Validate Email
    Check that email format is valid...

    ### Step 2: Create Account
    Insert user record into database...

    ### Step 3: Send Confirmation
    Email verification link to user...
```

**Output Chunks:**
```python
[
    {
        'id': 'workflow_user_registration::overview',
        'text': 'User Registration Process\n\nThis workflow guides you...',
        'metadata': {
            'doc_id': 'workflow_user_registration',
            'category': 'workflow',
            'chunk_type': 'workflow_overview',
            'chunk_heading': 'Overview'
        }
    },
    {
        'id': 'workflow_user_registration::step_1',
        'text': 'Step 1: Validate Email\nCheck that email format is valid...',
        'metadata': {
            'doc_id': 'workflow_user_registration',
            'category': 'workflow',
            'chunk_type': 'workflow_step',
            'step_number': 1,
            'chunk_heading': 'Validate Email'
        }
    },
    # ... more steps
]
```

### Example: FAQ Chunking

**Input:**
```yaml
metadata:
  id: faq_password_reset
  category: faq

content:
  body: |
    ## How do I reset my password?
    Click "Forgot Password" on the login page...

    ## What if I don't receive the reset email?
    Check your spam folder. If still missing...
```

**Output:**
```python
[
    {
        'id': 'faq_password_reset::qa_1',
        'text': 'How do I reset my password?\n\nClick "Forgot Password"...',
        'metadata': {
            'doc_id': 'faq_password_reset',
            'category': 'faq',
            'chunk_type': 'faq_qa',
            'question': 'How do I reset my password?'
        }
    },
    # ... more Q&A pairs
]
```

### Fallback: Semantic Chunking

For unstructured or inconsistent documents:

```python
def chunk_semantic(doc: dict, base_metadata: dict, max_tokens: int = 512) -> List[Dict]:
    """
    Semantic chunking using sentence boundaries and token limits.
    """
    from langchain.text_splitter import RecursiveCharacterTextSplitter

    splitter = RecursiveCharacterTextSplitter(
        chunk_size=max_tokens * 4,  # Approximate chars per token
        chunk_overlap=50,            # Overlap for context continuity
        separators=['\n\n', '\n', '. ', ' ', '']
    )

    text = doc['content']['body']
    chunks = splitter.split_text(text)

    return [
        {
            'id': f"{doc['metadata']['id']}::chunk_{i}",
            'text': chunk,
            'metadata': {
                **base_metadata,
                'chunk_type': 'semantic_chunk',
                'chunk_number': i
            }
        }
        for i, chunk in enumerate(chunks)
    ]
```

---

## ChromaDB Schema Design

### Collection Configuration

```python
import chromadb
from chromadb.config import Settings

# Initialize client
client = chromadb.HttpClient(
    host="localhost",
    port=8000,
    settings=Settings(
        chroma_client_auth_provider="chromadb.auth.token.TokenAuthClientProvider",
        chroma_client_auth_credentials="your-api-key"
    )
)

# Create collection with cosine similarity
collection = client.get_or_create_collection(
    name="knowledge_base_v1",
    metadata={
        "hnsw:space": "cosine",         # Cosine similarity (for normalized vectors)
        "hnsw:construction_ef": 128,    # Build-time accuracy
        "hnsw:search_ef": 64            # Search-time accuracy
    }
)
```

### Chunk Document Schema

Each chunk stored in ChromaDB:

```python
{
    # Unique ID: doc_id::chunk_identifier
    "id": "workflow_user_registration::step_1",

    # Embedding vector (dimensions match your model)
    "embedding": [0.123, -0.456, 0.789, ...],  # e.g., 1024 floats

    # Full chunk text (returned in search results)
    "document": "Step 1: Validate Email\nCheck that email format is valid...",

    # Filterable metadata
    "metadata": {
        # Document-level metadata (inherited by all chunks)
        "doc_id": "workflow_user_registration",
        "doc_title": "User Registration Process",
        "category": "workflow",
        "version": "1.0.0",
        "status": "published",
        "last_updated": "2025-11-24",

        # Organization & filtering
        "organization_path": "user_management.registration",
        "tags": "registration,onboarding,email",  # Comma-separated for filtering

        # Access control (CRITICAL for multi-tenant)
        "access_roles": "user,admin,developer",  # Comma-separated
        "platforms": "web,mobile",

        # Chunk-specific metadata
        "chunk_type": "workflow_step",
        "chunk_heading": "Validate Email",
        "step_number": 1  # Integer (if applicable)
    }
}
```

### Metadata Flattening for ChromaDB

**Important:** ChromaDB has limited array support. Flatten arrays to comma-separated strings:

```python
def flatten_for_chromadb(metadata: dict) -> dict:
    """
    Flatten array fields to comma-separated strings for ChromaDB filtering.
    """
    flattened = {}

    for key, value in metadata.items():
        if isinstance(value, list):
            # Convert list to comma-separated string
            flattened[key] = ','.join(str(v) for v in value)
        elif value is None:
            # ChromaDB doesn't like None
            flattened[key] = ''
        else:
            flattened[key] = value

    return flattened


def contains_value(csv_string: str, value: str) -> bool:
    """
    Check if comma-separated string contains a value.
    """
    return value in csv_string.split(',')
```

### Hierarchical Path Expansion

Enable filtering at any level of your organization hierarchy:

```python
def extract_hierarchy_levels(path: str) -> List[str]:
    """
    Expand "product.feature.subfeature" →
    ["product", "product.feature", "product.feature.subfeature"]

    Enables filtering at any hierarchy level.
    """
    if not path:
        return []

    parts = path.split('.')
    levels = []
    for i in range(1, len(parts) + 1):
        levels.append('.'.join(parts[:i]))
    return levels

# Usage in metadata
metadata['organization_path_levels'] = ','.join(
    extract_hierarchy_levels(doc['organizationPath'])
)

# Query: All docs under "product.feature.*"
collection.query(
    query_embeddings=[...],
    where={"organization_path_levels": {"$contains": "product.feature"}}
)
```

---

## Embedding Service Implementation

### OpenAI-Compatible Client

Works with Ollama, vLLM, DeepInfra, Together.ai, OpenAI, etc.

```python
from openai import OpenAI
from typing import List
import numpy as np

class EmbeddingService:
    """
    Generic embedding service using OpenAI-compatible API.
    """

    def __init__(
        self,
        base_url: str = "http://localhost:11434/v1",  # Ollama default
        api_key: str = "ollama",
        model: str = "qwen3-embedding:0.6b",
        dimensions: int = 1024
    ):
        self.client = OpenAI(
            base_url=base_url,
            api_key=api_key
        )
        self.model = model
        self.dimensions = dimensions

    def embed_text(self, text: str, task_instruction: str = None) -> List[float]:
        """
        Generate embedding for a single text.

        Args:
            text: Text to embed
            task_instruction: Optional task-specific instruction
                             (e.g., "Represent this document for retrieval")
        """
        # Some models (Qwen3) support instruction-aware embeddings
        if task_instruction:
            input_text = f"Instruct: {task_instruction}\nQuery: {text}"
        else:
            input_text = text

        response = self.client.embeddings.create(
            model=self.model,
            input=input_text,
            encoding_format="float"
        )

        embedding = response.data[0].embedding

        # Normalize embedding (L2 normalization for cosine similarity)
        embedding = self._normalize(embedding)

        return embedding

    def embed_batch(
        self,
        texts: List[str],
        task_instruction: str = None,
        batch_size: int = 100
    ) -> List[List[float]]:
        """
        Generate embeddings for multiple texts efficiently.
        Processes in batches to avoid API limits.
        """
        all_embeddings = []

        for i in range(0, len(texts), batch_size):
            batch = texts[i:i + batch_size]

            if task_instruction:
                input_texts = [f"Instruct: {task_instruction}\nQuery: {t}" for t in batch]
            else:
                input_texts = batch

            response = self.client.embeddings.create(
                model=self.model,
                input=input_texts,
                encoding_format="float"
            )

            batch_embeddings = [
                self._normalize(data.embedding)
                for data in response.data
            ]
            all_embeddings.extend(batch_embeddings)

        return all_embeddings

    def _normalize(self, embedding: List[float]) -> List[float]:
        """L2 normalize embedding vector for cosine similarity."""
        arr = np.array(embedding)
        norm = np.linalg.norm(arr)
        if norm > 0:
            arr = arr / norm
        return arr.tolist()
```

### Task-Specific Instructions (Optional, 1-5% Quality Boost)

Some embedding models benefit from task-specific instructions:

```python
TASK_INSTRUCTIONS = {
    # For document ingestion (stored chunks)
    'document': "Represent this document for retrieval",

    # For user queries (search time)
    'query': "Given a user question, retrieve relevant documentation",

    # Category-specific instructions
    'workflow': "Represent this workflow guide for step-by-step retrieval",
    'faq': "Represent this FAQ for question answering",
    'troubleshooting': "Represent this troubleshooting guide for issue resolution",
    'reference': "Represent this reference documentation for knowledge retrieval",
    'api': "Represent this API documentation for developer queries"
}

# Usage
embedding = service.embed_text(
    chunk_text,
    task_instruction=TASK_INSTRUCTIONS.get(category, TASK_INSTRUCTIONS['document'])
)
```

---

## Ingestion Pipeline Patterns

### Full Pipeline Implementation

```python
import chromadb
import yaml
from typing import List, Dict
import logging

logger = logging.getLogger(__name__)


class IngestionService:
    """
    Generic ingestion service for RAG document processing.
    """

    def __init__(
        self,
        document_source: str,          # e.g., S3 bucket, filesystem path
        chromadb_host: str = "localhost",
        chromadb_port: int = 8000,
        embedding_base_url: str = "http://localhost:11434/v1",
        collection_name: str = "knowledge_base_v1"
    ):
        # Document source (adapt to your storage)
        self.document_source = document_source

        # ChromaDB client
        self.chroma = chromadb.HttpClient(
            host=chromadb_host,
            port=chromadb_port
        )
        self.collection = self.chroma.get_or_create_collection(
            name=collection_name,
            metadata={"hnsw:space": "cosine"}
        )

        # Embedding service
        self.embedding_service = EmbeddingService(
            base_url=embedding_base_url,
            model="qwen3-embedding:0.6b"
        )

    def ingest_all_documents(self):
        """
        Ingest all documents from source.
        Adapt this method to your document storage.
        """
        # Example: List all YAML files
        document_paths = self._list_documents()

        for doc_path in document_paths:
            try:
                self.ingest_document(doc_path)
            except Exception as e:
                logger.error(f"Failed to ingest {doc_path}: {e}")

    def ingest_document(self, doc_path: str):
        """
        Ingest a single document.
        """
        logger.info(f"Ingesting: {doc_path}")

        # 1. Load document (adapt to your format)
        with open(doc_path, 'r') as f:
            doc = yaml.safe_load(f)

        # 2. Validate: Only ingest published documents
        if doc['metadata'].get('status') != 'published':
            logger.info(f"Skipping {doc_path} - status is {doc['metadata'].get('status')}")
            return

        # 3. Delete existing chunks for this document (re-indexing)
        doc_id = doc['metadata']['id']
        self._delete_document_chunks(doc_id)

        # 4. Chunk the document
        chunks = chunk_document(doc)
        logger.info(f"Generated {len(chunks)} chunks for {doc_id}")

        # 5. Generate embeddings (batch)
        chunk_texts = [c['text'] for c in chunks]
        task_instruction = TASK_INSTRUCTIONS.get(
            doc['metadata']['category'],
            TASK_INSTRUCTIONS['document']
        )
        embeddings = self.embedding_service.embed_batch(
            chunk_texts,
            task_instruction=task_instruction
        )

        # 6. Prepare data for ChromaDB
        ids = [c['id'] for c in chunks]
        documents = chunk_texts
        metadatas = [flatten_for_chromadb(c['metadata']) for c in chunks]

        # 7. Upsert to ChromaDB
        self.collection.upsert(
            ids=ids,
            embeddings=embeddings,
            documents=documents,
            metadatas=metadatas
        )

        logger.info(f"✅ Ingested {len(chunks)} chunks from {doc_id}")

    def _delete_document_chunks(self, doc_id: str):
        """
        Delete all existing chunks for a document (for re-indexing).
        """
        try:
            results = self.collection.get(
                where={"doc_id": doc_id},
                include=[]
            )

            if results['ids']:
                self.collection.delete(ids=results['ids'])
                logger.info(f"Deleted {len(results['ids'])} existing chunks for {doc_id}")
        except Exception as e:
            logger.warning(f"Could not delete existing chunks: {e}")

    def _list_documents(self) -> List[str]:
        """
        List all documents from source.
        Adapt to your storage (S3, filesystem, etc.)
        """
        # Example: Filesystem
        import glob
        return glob.glob(f"{self.document_source}/**/*.yml", recursive=True)
```

### Incremental Updates

For production systems, implement incremental updates:

```python
def ingest_if_changed(self, doc_path: str):
    """
    Only re-ingest if document has changed.
    Uses last_updated timestamp or file hash.
    """
    doc = self._load_document(doc_path)
    doc_id = doc['metadata']['id']

    # Get existing document metadata from ChromaDB
    existing = self.collection.get(
        where={"doc_id": doc_id},
        limit=1,
        include=["metadatas"]
    )

    if existing['metadatas']:
        existing_version = existing['metadatas'][0].get('version')
        new_version = doc['metadata']['version']

        if existing_version == new_version:
            logger.info(f"Skipping {doc_id} - version unchanged")
            return

    # Version changed, re-ingest
    self.ingest_document(doc_path)
```

---

## Query-Time Retrieval

### RAG Query Service Pattern

```python
class RAGQueryService:
    """
    Query service with metadata filtering and context assembly.
    """

    def __init__(
        self,
        chromadb_host: str = "localhost",
        chromadb_port: int = 8000,
        embedding_base_url: str = "http://localhost:11434/v1",
        collection_name: str = "knowledge_base_v1"
    ):
        # ChromaDB
        self.chroma = chromadb.HttpClient(host=chromadb_host, port=chromadb_port)
        self.collection = self.chroma.get_collection(collection_name)

        # Embedding (SAME as ingestion!)
        self.embedding_service = EmbeddingService(
            base_url=embedding_base_url,
            model="qwen3-embedding:0.6b"
        )

    def search(
        self,
        query: str,
        user_context: dict = None,  # e.g., {"role": "admin", "platform": "web"}
        n_results: int = 10,
        category_filter: str = None,
        organization_filter: str = None
    ) -> dict:
        """
        Semantic search with metadata filtering.

        Args:
            query: User's question
            user_context: User roles, platform, etc. for access control
            n_results: Number of chunks to retrieve
            category_filter: Optional category filter
            organization_filter: Optional organization path filter

        Returns:
            {results: list, metadata: dict}
        """
        # 1. Generate query embedding
        query_embedding = self.embedding_service.embed_text(
            query,
            task_instruction=TASK_INSTRUCTIONS['query']
        )

        # 2. Build metadata filter
        where_filter = {"status": "published"}

        if category_filter:
            where_filter["category"] = category_filter

        if organization_filter:
            where_filter["organization_path_levels"] = {"$contains": organization_filter}

        # 3. Query ChromaDB
        results = self.collection.query(
            query_embeddings=[query_embedding],
            n_results=n_results,
            where=where_filter,
            include=["documents", "metadatas", "distances"]
        )

        # 4. Post-filter by access roles (if user context provided)
        if user_context and 'role' in user_context:
            results = self._filter_by_access(results, user_context['role'])

        # 5. Format results
        return {
            'results': [
                {
                    'id': results['ids'][0][i],
                    'text': results['documents'][0][i],
                    'metadata': results['metadatas'][0][i],
                    'distance': results['distances'][0][i]
                }
                for i in range(len(results['ids'][0]))
            ],
            'metadata': {
                'query': query,
                'filters_applied': where_filter,
                'results_count': len(results['ids'][0])
            }
        }

    def _filter_by_access(self, results: dict, user_role: str) -> dict:
        """
        Post-filter results by access roles.
        ChromaDB doesn't support $contains well, so we filter after retrieval.
        """
        filtered_ids = []
        filtered_docs = []
        filtered_metadatas = []
        filtered_distances = []

        for i, metadata in enumerate(results['metadatas'][0]):
            access_roles_str = metadata.get('access_roles', '')
            if user_role in access_roles_str.split(','):
                filtered_ids.append(results['ids'][0][i])
                filtered_docs.append(results['documents'][0][i])
                filtered_metadatas.append(metadata)
                filtered_distances.append(results['distances'][0][i])

        return {
            'ids': [filtered_ids],
            'documents': [filtered_docs],
            'metadatas': [filtered_metadatas],
            'distances': [filtered_distances]
        }
```

### Integration with LLM

```python
def generate_response(
    self,
    query: str,
    user_context: dict = None,
    llm_client = None  # Your LLM client (OpenAI, DeepSeek, etc.)
) -> str:
    """
    Full RAG pipeline: Retrieve + Generate.
    """
    # 1. Retrieve relevant chunks
    search_results = self.search(query, user_context=user_context, n_results=5)

    # 2. Build context from retrieved chunks
    context_parts = []
    for result in search_results['results']:
        doc_id = result['metadata']['doc_id']
        heading = result['metadata'].get('chunk_heading', '')
        text = result['text']
        context_parts.append(f"[{doc_id}] {heading}\n{text}")

    context = "\n\n---\n\n".join(context_parts)

    # 3. Build LLM prompt
    system_prompt = f"""You are a helpful assistant.

When answering:
- Use the knowledge base documents provided below
- Cite document IDs when referencing specific information (e.g., [workflow_user_registration])
- If information is missing, say so clearly

Knowledge Base Context:
{context}
"""

    # 4. Generate response
    response = llm_client.chat.completions.create(
        model="your-model",
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": query}
        ],
        temperature=0.3
    )

    return response.choices[0].message.content
```

---

## Deployment Patterns

### Pattern 1: All-in-One Docker Compose

```yaml
# docker-compose.yml
version: '3.8'

services:
  # Embedding Service
  embedding:
    image: ghcr.io/huggingface/text-embeddings-inference:1.7.2
    ports:
      - "8080:80"
    volumes:
      - hf_cache:/data
    command: --model-id Qwen/Qwen3-Embedding-0.6B --dtype float16
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu]

  # ChromaDB
  chromadb:
    image: chromadb/chroma:latest
    ports:
      - "8000:8000"
    volumes:
      - chroma_data:/chroma/chroma
    environment:
      - CHROMA_SERVER_AUTH_CREDENTIALS=${CHROMA_API_KEY}
      - CHROMA_SERVER_AUTH_PROVIDER=chromadb.auth.token.TokenAuthServerProvider

  # Ingestion Service (your custom implementation)
  ingestion:
    build: ./ingestion
    environment:
      - DOCUMENT_SOURCE=/documents
      - CHROMADB_HOST=chromadb
      - CHROMADB_PORT=8000
      - EMBEDDING_URL=http://embedding:80
    volumes:
      - ./documents:/documents
    depends_on:
      - chromadb
      - embedding

volumes:
  hf_cache:
  chroma_data:
```

### Pattern 2: Serverless (AWS Lambda + S3 Events)

```
S3 Bucket → S3 Event → Lambda → ChromaDB (Cloud)
                                    ↓
                              Embedding API (DeepInfra/OpenAI)
```

**Lambda Function:**
```python
import json
import boto3
from ingestion_service import IngestionService

s3 = boto3.client('s3')
ingestion = IngestionService(
    chromadb_host=os.environ['CHROMADB_HOST'],
    embedding_base_url=os.environ['EMBEDDING_API_URL']
)

def lambda_handler(event, context):
    """
    Triggered on S3 object creation.
    """
    for record in event['Records']:
        bucket = record['s3']['bucket']['name']
        key = record['s3']['object']['key']

        # Download document
        local_path = f"/tmp/{os.path.basename(key)}"
        s3.download_file(bucket, key, local_path)

        # Ingest
        ingestion.ingest_document(local_path)

    return {'statusCode': 200, 'body': json.dumps('Success')}
```

### Pattern 3: Kubernetes CronJob (Scheduled Ingestion)

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: knowledge-base-ingestion
spec:
  schedule: "0 2 * * *"  # Daily at 2am
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: ingestion
            image: your-registry/ingestion:latest
            env:
            - name: DOCUMENT_SOURCE
              value: "s3://your-bucket/docs"
            - name: CHROMADB_HOST
              value: "chromadb-service.default.svc.cluster.local"
            - name: EMBEDDING_URL
              value: "http://embedding-service:8080"
          restartPolicy: OnFailure
```

---

## Testing & Validation

### Unit Tests

```python
import pytest

class TestChunking:
    def test_workflow_chunks_by_step(self):
        doc = {
            'metadata': {'id': 'test_workflow', 'category': 'workflow'},
            'content': {
                'summary': 'Test workflow',
                'body': '## Overview\nTest\n\n### Step 1: First\nDo this\n\n### Step 2: Second\nDo that'
            }
        }

        chunks = chunk_document(doc)

        # Should have overview + 2 steps
        assert len(chunks) == 3
        assert chunks[0]['metadata']['chunk_type'] == 'workflow_overview'
        assert chunks[1]['metadata']['chunk_type'] == 'workflow_step'
        assert chunks[1]['metadata']['step_number'] == 1

    def test_faq_chunks_by_qa(self):
        doc = {
            'metadata': {'id': 'test_faq', 'category': 'faq'},
            'content': {
                'body': '## Question 1?\nAnswer 1\n\n## Question 2?\nAnswer 2'
            }
        }

        chunks = chunk_document(doc)

        assert len(chunks) == 2
        for chunk in chunks:
            assert chunk['metadata']['chunk_type'] == 'faq_qa'
            assert 'question' in chunk['metadata']


class TestEmbeddings:
    def test_embedding_dimensions(self):
        service = EmbeddingService()
        embedding = service.embed_text("Test text")

        assert len(embedding) == 1024  # Qwen3-0.6B dimensions

    def test_embeddings_normalized(self):
        service = EmbeddingService()
        embedding = service.embed_text("Test text")

        # Check L2 norm is 1
        norm = sum(x**2 for x in embedding) ** 0.5
        assert abs(norm - 1.0) < 0.001

    def test_batch_embedding(self):
        service = EmbeddingService()
        texts = ["First text", "Second text", "Third text"]
        embeddings = service.embed_batch(texts)

        assert len(embeddings) == 3
        assert all(len(e) == 1024 for e in embeddings)


class TestRetrieval:
    def test_access_filtering(self):
        service = RAGQueryService()

        # User should only see docs they have access to
        results = service.search(
            query="How to reset password?",
            user_context={"role": "user"}
        )

        for result in results['results']:
            access_roles = result['metadata']['access_roles']
            assert 'user' in access_roles.split(',')

    def test_category_filtering(self):
        service = RAGQueryService()

        results = service.search(
            query="API authentication",
            category_filter="api"
        )

        for result in results['results']:
            assert result['metadata']['category'] == 'api'
```

### Integration Tests

```python
class TestEndToEnd:
    def test_full_ingestion_and_retrieval(self, tmp_path):
        # 1. Create test document
        test_doc = {
            'metadata': {
                'id': 'test_doc_001',
                'title': 'Test Document',
                'category': 'faq',
                'status': 'published',
                'version': '1.0.0'
            },
            'content': {
                'summary': 'Test FAQ',
                'body': '## What is testing?\nTesting is important.'
            }
        }

        doc_path = tmp_path / "test.yml"
        with open(doc_path, 'w') as f:
            yaml.dump(test_doc, f)

        # 2. Ingest
        ingestion_service = IngestionService(
            document_source=str(tmp_path),
            chromadb_host="localhost",
            chromadb_port=8000
        )
        ingestion_service.ingest_document(str(doc_path))

        # 3. Query
        query_service = RAGQueryService(
            chromadb_host="localhost",
            chromadb_port=8000
        )
        results = query_service.search("What is testing?")

        # 4. Verify
        assert len(results['results']) > 0
        assert 'test_doc_001' in results['results'][0]['metadata']['doc_id']
```

### Quality Metrics

```python
def evaluate_retrieval_quality(
    test_queries: List[dict]  # [{query, expected_doc_ids, category}]
) -> dict:
    """
    Evaluate retrieval quality with test queries.

    Returns:
        {
            'accuracy@1': float,  # Top result is correct
            'accuracy@5': float,  # Correct doc in top 5
            'mrr': float          # Mean Reciprocal Rank
        }
    """
    service = RAGQueryService()

    correct_at_1 = 0
    correct_at_5 = 0
    reciprocal_ranks = []

    for test in test_queries:
        results = service.search(
            test['query'],
            category_filter=test.get('category'),
            n_results=10
        )

        retrieved_ids = [r['metadata']['doc_id'] for r in results['results']]

        # Check if any expected doc is retrieved
        for rank, doc_id in enumerate(retrieved_ids, start=1):
            if doc_id in test['expected_doc_ids']:
                if rank == 1:
                    correct_at_1 += 1
                if rank <= 5:
                    correct_at_5 += 1
                reciprocal_ranks.append(1.0 / rank)
                break
        else:
            reciprocal_ranks.append(0.0)

    total = len(test_queries)
    return {
        'accuracy@1': correct_at_1 / total,
        'accuracy@5': correct_at_5 / total,
        'mrr': sum(reciprocal_ranks) / total
    }
```

---

## Best Practices Summary

### ✅ DO

1. **Use the same embedding model** for ingestion and queries
2. **Normalize embeddings** (L2 normalization) for cosine similarity
3. **Filter by status** - only index published/approved documents
4. **Chunk by content structure** - different strategies for different types
5. **Enrich metadata** - more metadata = better filtering
6. **Version your documents** - track changes and re-index when needed
7. **Test retrieval quality** - measure accuracy with real queries
8. **Implement access control** - filter by user roles at query time
9. **Monitor performance** - track embedding latency, search latency
10. **Use batch embeddings** - more efficient than one-by-one

### ❌ DON'T

1. **Don't use different models** for ingestion vs. queries (vectors won't match!)
2. **Don't skip normalization** if using cosine similarity
3. **Don't chunk arbitrarily** - respect document structure
4. **Don't store PII** in embeddings (they're not encrypted)
5. **Don't ignore versioning** - you'll lose track of what's indexed
6. **Don't over-chunk** (too small = lost context) or under-chunk (too large = noise)
7. **Don't forget to delete old chunks** when re-indexing
8. **Don't expose all documents** - implement role-based filtering
9. **Don't ignore token limits** - chunk size must fit model context
10. **Don't skip testing** - bad chunking = bad retrieval

---

## Next Steps

1. **Define your schema** - What fields do your documents have?
2. **Choose an embedding model** - Based on quality, cost, deployment
3. **Implement chunking** - Category-based or semantic?
4. **Set up ChromaDB** - Local or cloud?
5. **Build ingestion pipeline** - Batch or incremental?
6. **Test retrieval** - Does it find the right documents?
7. **Integrate with LLM** - Connect to your RAG orchestrator
8. **Monitor and iterate** - Improve chunking and metadata based on usage

---

**End of RAG Ingestion Guide**
