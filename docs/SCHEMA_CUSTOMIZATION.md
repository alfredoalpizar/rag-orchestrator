# Schema Customization Guide

**Purpose:** How to adapt the RAG ingestion system to your specific document schema and business requirements

---

## Overview

The RAG ingestion system is designed to be **schema-agnostic**. This guide shows how to:
1. Define your custom document schema
2. Map your fields to chunking logic
3. Configure metadata for filtering
4. Implement custom chunking strategies

---

## Step 1: Define Your Document Schema

### Start with the Template

```yaml
# template.yml - Generic template
metadata:
  # Required fields
  id: string                      # Unique identifier
  title: string                   # Human-readable title
  category: string                # Document type
  version: string                 # Semantic version
  status: enum                    # Lifecycle state

  # Optional but recommended
  owner: string                   # Owner/team
  lastUpdated: string             # ISO 8601 date
  tags: string[]                  # Search tags
  organizationPath: string        # Hierarchical category

  # Access control (if multi-tenant)
  accessRoles: string[]           # Who can access
  platforms: string[]             # Where it applies

  # Relationships (if needed)
  relatedDocuments: object[]      # Related docs

content:
  summary: string                 # Brief description
  body: string                    # Main content (Markdown)
```

### Customize for Your Domain

**Example 1: Technical Documentation**
```yaml
metadata:
  id: "api_auth_oauth2"
  title: "OAuth 2.0 Authentication"
  category: "api"
  version: "2.1.0"
  status: "published"

  # Custom fields for your domain
  owner: "platform-team"
  lastUpdated: "2025-11-24"
  organizationPath: "developer.api.authentication"
  tags: ["oauth", "authentication", "security"]

  # Access control
  accessRoles: ["developer", "architect", "admin"]
  platforms: ["web", "mobile", "backend"]

  # API-specific metadata
  apiVersion: "v2"
  deprecated: false
  securityLevel: "high"

content:
  summary: "Learn how to implement OAuth 2.0 authentication"
  body: |
    ## Overview
    OAuth 2.0 is an authorization framework...

    ### Endpoints
    #### POST /oauth/token
    Request an access token...
```

**Example 2: Customer Support Knowledge Base**
```yaml
metadata:
  id: "kb_password_reset"
  title: "How to Reset Your Password"
  category: "faq"
  version: "1.0.0"
  status: "published"

  # Support-specific metadata
  owner: "support-team"
  lastUpdated: "2025-11-20"
  organizationPath: "support.account.password"
  tags: ["password", "reset", "account", "login"]

  # Support-specific fields
  difficulty: "easy"
  estimatedTime: "2 minutes"
  commonIssue: true

  # Multi-language support
  language: "en"
  translations: ["es", "fr", "de"]

  # Access control
  accessRoles: ["customer", "support_agent", "admin"]
  platforms: ["web", "mobile"]

content:
  summary: "Step-by-step guide to reset your password"
  body: |
    ## How do I reset my password?
    1. Click "Forgot Password" on login page
    2. Enter your email address
    3. Check your email for reset link
    ...
```

**Example 3: Internal Procedures/Workflows**
```yaml
metadata:
  id: "proc_employee_onboarding"
  title: "Employee Onboarding Procedure"
  category: "workflow"
  version: "3.2.1"
  status: "published"

  # Procedural metadata
  owner: "hr-team"
  lastUpdated: "2025-10-15"
  organizationPath: "hr.onboarding.new_hire"
  tags: ["onboarding", "hr", "new employee"]

  # Workflow-specific fields
  estimatedDuration: "2 weeks"
  requiredApprovals: ["hr_manager", "it_admin"]
  complianceRequired: true

  # Access control
  accessRoles: ["hr_staff", "manager", "admin"]
  departments: ["hr", "it", "facilities"]

  # Related procedures
  relatedDocuments:
    - id: "proc_it_setup"
      relationship: "prerequisite"
    - id: "proc_benefits_enrollment"
      relationship: "followup"

content:
  summary: "Complete onboarding procedure for new employees"
  body: |
    ## Overview
    This procedure covers the full onboarding process...

    ### Step 1: Pre-boarding (Week before start)
    - Send welcome email
    - Create accounts
    ...
```

---

## Step 2: Map Fields to Chunking Logic

### Define Your Chunking Strategy

Create a mapping from your categories to chunking strategies:

```python
# chunking_config.py

CHUNKING_STRATEGIES = {
    # API documentation: chunk by endpoint
    'api': {
        'strategy': 'by_headings',
        'heading_level': 3,           # H3 = ### Endpoint Name
        'include_overview': True,
        'overview_heading': '## Overview'
    },

    # FAQs: chunk by Q&A pair
    'faq': {
        'strategy': 'by_qa_pairs',
        'question_heading_level': 2   # H2 = ## Question?
    },

    # Workflows: chunk by step
    'workflow': {
        'strategy': 'by_steps',
        'step_heading_pattern': r'###?\s*Step\s*\d+',
        'include_overview': True
    },

    # Reference docs: chunk by major section
    'reference': {
        'strategy': 'by_headings',
        'heading_level': 2            # H2 = ## Section Name
    },

    # Tutorials: chunk by lesson/step
    'tutorial': {
        'strategy': 'by_headings',
        'heading_level': 2,
        'max_tokens': 1000            # Longer chunks for tutorials
    },

    # Release notes: chunk by version
    'release_notes': {
        'strategy': 'by_version',
        'version_heading_pattern': r'##\s*Version\s*[\d.]+'
    },

    # Fallback for unknown categories
    'default': {
        'strategy': 'semantic',
        'max_tokens': 512,
        'overlap': 50
    }
}
```

### Implement Custom Chunking Logic

```python
# custom_chunker.py

def chunk_by_category(doc: dict, config: dict) -> List[Dict]:
    """
    Chunk document based on category-specific configuration.
    """
    category = doc['metadata']['category']
    strategy_config = config.get(category, config['default'])

    if strategy_config['strategy'] == 'by_steps':
        return chunk_by_steps(doc, strategy_config)
    elif strategy_config['strategy'] == 'by_qa_pairs':
        return chunk_by_qa_pairs(doc, strategy_config)
    elif strategy_config['strategy'] == 'by_headings':
        return chunk_by_headings(doc, strategy_config)
    elif strategy_config['strategy'] == 'by_version':
        return chunk_by_version(doc, strategy_config)
    else:
        return chunk_semantic(doc, strategy_config)


# Example: Custom chunker for release notes
def chunk_by_version(doc: dict, config: dict) -> List[Dict]:
    """
    Chunk release notes by version.
    """
    import re

    body = doc['content']['body']
    base_metadata = extract_base_metadata(doc)

    # Split by version headings
    version_pattern = config['version_heading_pattern']
    sections = re.split(f'(?={version_pattern})', body)

    chunks = []
    for section in sections:
        if not section.strip():
            continue

        # Extract version number
        version_match = re.match(version_pattern, section.strip())
        if version_match:
            version = version_match.group(0).replace('##', '').strip()

            chunks.append({
                'id': f"{doc['metadata']['id']}::{version.replace(' ', '_').lower()}",
                'text': section.strip(),
                'metadata': {
                    **base_metadata,
                    'chunk_type': 'release_version',
                    'release_version': version
                }
            })

    return chunks
```

---

## Step 3: Configure Metadata Mapping

### Define What to Index

Not all fields need to be in ChromaDB metadata. Choose fields that are:
1. **Filterable** - Used in queries (category, tags, access roles)
2. **Searchable** - Enhance retrieval (organization path, keywords)
3. **Displayable** - Shown in results (title, version)

```python
# metadata_config.py

METADATA_MAPPING = {
    # Always include these (core fields)
    'required': [
        'doc_id',
        'doc_title',
        'category',
        'version',
        'status'
    ],

    # Include if present in source document
    'optional': [
        'owner',
        'last_updated',
        'organization_path',
        'tags',
        'access_roles',
        'platforms'
    ],

    # Custom fields specific to your domain
    'custom': {
        'api': ['api_version', 'deprecated', 'security_level'],
        'faq': ['difficulty', 'estimated_time', 'language'],
        'workflow': ['estimated_duration', 'compliance_required'],
        'release_notes': ['release_version', 'release_date']
    },

    # Fields to exclude (sensitive or unnecessary)
    'exclude': [
        'internal_notes',
        'edit_history',
        'draft_content'
    ]
}


def extract_metadata(doc: dict, chunk_metadata: dict) -> dict:
    """
    Extract and flatten metadata for ChromaDB.
    """
    meta = doc['metadata']
    category = meta['category']

    # Start with required fields
    result = {
        'doc_id': meta['id'],
        'doc_title': meta['title'],
        'category': category,
        'version': meta['version'],
        'status': meta['status']
    }

    # Add optional fields if present
    for field in METADATA_MAPPING['optional']:
        if field in meta:
            value = meta[field]
            # Flatten arrays to comma-separated strings
            if isinstance(value, list):
                result[field] = ','.join(str(v) for v in value)
            else:
                result[field] = str(value)

    # Add category-specific custom fields
    custom_fields = METADATA_MAPPING['custom'].get(category, [])
    for field in custom_fields:
        if field in meta:
            result[field] = str(meta[field])

    # Add chunk-specific metadata
    result.update(chunk_metadata)

    return result
```

---

## Step 4: Implement Access Control

### Pattern 1: Role-Based Access Control (RBAC)

```python
# access_control.py

def filter_by_user_access(results: dict, user_context: dict) -> dict:
    """
    Filter search results based on user's roles.

    Args:
        results: ChromaDB query results
        user_context: {
            'roles': ['role1', 'role2'],
            'platform': 'web',
            'department': 'engineering'
        }
    """
    user_roles = set(user_context.get('roles', []))
    user_platform = user_context.get('platform')
    user_dept = user_context.get('department')

    filtered = {
        'ids': [[]],
        'documents': [[]],
        'metadatas': [[]],
        'distances': [[]]
    }

    for i, metadata in enumerate(results['metadatas'][0]):
        # Check role access
        doc_roles = set(metadata.get('access_roles', '').split(','))
        if not user_roles.intersection(doc_roles):
            continue  # User doesn't have required role

        # Check platform (if specified)
        if user_platform:
            doc_platforms = metadata.get('platforms', '').split(',')
            if doc_platforms and user_platform not in doc_platforms:
                continue

        # Check department (if specified)
        if user_dept:
            doc_depts = metadata.get('departments', '').split(',')
            if doc_depts and user_dept not in doc_depts:
                continue

        # User has access - include in results
        filtered['ids'][0].append(results['ids'][0][i])
        filtered['documents'][0].append(results['documents'][0][i])
        filtered['metadatas'][0].append(metadata)
        filtered['distances'][0].append(results['distances'][0][i])

    return filtered
```

### Pattern 2: Attribute-Based Access Control (ABAC)

```python
def evaluate_access_policy(doc_metadata: dict, user_context: dict) -> bool:
    """
    Evaluate complex access policies.

    Example policy:
    - Public docs: anyone can access
    - Internal docs: employees only
    - Confidential: specific roles + department match
    """
    security_level = doc_metadata.get('security_level', 'public')

    # Public documents
    if security_level == 'public':
        return True

    # Internal documents - require employee role
    if security_level == 'internal':
        return 'employee' in user_context.get('roles', [])

    # Confidential - require role AND department match
    if security_level == 'confidential':
        required_roles = doc_metadata.get('access_roles', '').split(',')
        user_roles = user_context.get('roles', [])

        if not any(role in required_roles for role in user_roles):
            return False

        # Also check department
        doc_depts = doc_metadata.get('departments', '').split(',')
        user_dept = user_context.get('department')

        return user_dept in doc_depts

    return False
```

---

## Step 5: Test Your Configuration

### Create Test Documents

```yaml
# test_docs/test_api.yml
metadata:
  id: "test_api_endpoint"
  title: "Test API Endpoint"
  category: "api"
  version: "1.0.0"
  status: "published"
  owner: "platform-team"
  accessRoles: ["developer"]

content:
  summary: "Test API endpoint documentation"
  body: |
    ## Overview
    This is a test API.

    ### GET /api/test
    Returns test data.

    ### POST /api/test
    Creates test data.
```

### Test Chunking

```python
# test_chunking.py
import pytest
from custom_chunker import chunk_by_category, CHUNKING_STRATEGIES

def test_api_chunking():
    """Test that API docs chunk correctly by endpoint."""
    doc = load_yaml('test_docs/test_api.yml')
    chunks = chunk_by_category(doc, CHUNKING_STRATEGIES)

    # Should have: 1 overview + 2 endpoints = 3 chunks
    assert len(chunks) == 3

    # Check chunk types
    assert chunks[0]['metadata']['chunk_type'] == 'api_overview'
    assert chunks[1]['metadata']['chunk_heading'] == 'GET /api/test'
    assert chunks[2]['metadata']['chunk_heading'] == 'POST /api/test'


def test_metadata_extraction():
    """Test that metadata is extracted correctly."""
    doc = load_yaml('test_docs/test_api.yml')
    chunks = chunk_by_category(doc, CHUNKING_STRATEGIES)

    metadata = chunks[0]['metadata']

    # Required fields
    assert metadata['doc_id'] == 'test_api_endpoint'
    assert metadata['category'] == 'api'
    assert metadata['status'] == 'published'

    # Access control
    assert 'developer' in metadata['access_roles']


def test_access_control():
    """Test that access control filtering works."""
    # Mock ChromaDB results
    results = {
        'metadatas': [[
            {'access_roles': 'developer,admin'},
            {'access_roles': 'admin'},
            {'access_roles': 'developer,user'}
        ]]
    }

    # User with developer role
    filtered = filter_by_user_access(results, {'roles': ['developer']})

    # Should get docs 1 and 3 (not doc 2 which is admin-only)
    assert len(filtered['metadatas'][0]) == 2
```

---

## Step 6: Document Your Schema

Create a reference document for your team:

```markdown
# Our Knowledge Base Schema

## Document Categories

- **api**: API endpoint documentation
- **faq**: Frequently asked questions
- **workflow**: Step-by-step procedures
- **reference**: General reference documentation
- **tutorial**: Learning guides
- **release_notes**: Version release information

## Required Fields

All documents MUST have:
- `id`: Unique identifier (pattern: `{category}_{descriptive_name}`)
- `title`: Human-readable title
- `category`: One of the categories above
- `version`: Semantic version (e.g., "1.2.3")
- `status`: "draft" | "published" | "archived"

## Access Roles

- `public`: Anyone (no login required)
- `user`: Logged-in users
- `developer`: Engineering team
- `support`: Support team
- `admin`: Administrators

## Organization Paths

Use dot notation for hierarchical categorization:
- `developer.api.authentication`
- `support.account.password`
- `hr.onboarding.benefits`

This enables filtering at any level (e.g., all `developer.*` docs).

## Example Document

[Link to your template.yml]
```

---

## Example: Complete Custom Implementation

```python
# my_custom_ingestion.py

from typing import List, Dict
import yaml
import re


# 1. Define your chunking strategies
CHUNKING_CONFIG = {
    'api': {
        'strategy': 'by_headings',
        'heading_level': 3,
        'include_overview': True
    },
    'faq': {
        'strategy': 'by_qa_pairs',
        'question_heading_level': 2
    },
    'workflow': {
        'strategy': 'by_steps',
        'step_pattern': r'###?\s*Step\s*\d+',
        'include_overview': True
    }
}


# 2. Define metadata mapping
METADATA_CONFIG = {
    'required': ['doc_id', 'doc_title', 'category', 'version', 'status'],
    'optional': ['owner', 'last_updated', 'organization_path', 'tags', 'access_roles'],
    'custom': {
        'api': ['api_version', 'deprecated'],
        'faq': ['difficulty', 'language']
    }
}


# 3. Implement chunking logic
def chunk_document(doc: dict) -> List[Dict]:
    category = doc['metadata']['category']
    config = CHUNKING_CONFIG.get(category, {'strategy': 'semantic'})

    if config['strategy'] == 'by_steps':
        return chunk_workflow(doc, config)
    elif config['strategy'] == 'by_qa_pairs':
        return chunk_faq(doc, config)
    elif config['strategy'] == 'by_headings':
        return chunk_by_headings(doc, config)
    else:
        return chunk_semantic(doc, config)


def chunk_workflow(doc: dict, config: dict) -> List[Dict]:
    """Your custom workflow chunking logic."""
    chunks = []
    body = doc['content']['body']
    doc_id = doc['metadata']['id']

    # Overview chunk
    overview_text = body.split('##')[0] if '##' in body else body[:500]
    chunks.append({
        'id': f"{doc_id}::overview",
        'text': f"{doc['metadata']['title']}\n\n{overview_text}",
        'metadata': extract_metadata(doc, {
            'chunk_type': 'workflow_overview',
            'chunk_heading': 'Overview'
        })
    })

    # Step chunks
    step_pattern = config['step_pattern']
    step_sections = re.split(f'(?={step_pattern})', body)

    for i, section in enumerate(step_sections[1:], start=1):  # Skip first (overview)
        chunks.append({
            'id': f"{doc_id}::step_{i}",
            'text': section.strip(),
            'metadata': extract_metadata(doc, {
                'chunk_type': 'workflow_step',
                'step_number': i,
                'chunk_heading': f"Step {i}"
            })
        })

    return chunks


# 4. Implement metadata extraction
def extract_metadata(doc: dict, chunk_meta: dict) -> dict:
    """Extract metadata according to your configuration."""
    meta = doc['metadata']
    result = {}

    # Required fields
    for field in METADATA_CONFIG['required']:
        source_field = field.replace('doc_', '') if field.startswith('doc_') else field
        result[field] = meta.get(source_field, '')

    # Optional fields
    for field in METADATA_CONFIG['optional']:
        if field in meta:
            value = meta[field]
            result[field] = ','.join(value) if isinstance(value, list) else str(value)

    # Custom fields for category
    category = meta['category']
    for field in METADATA_CONFIG['custom'].get(category, []):
        if field in meta:
            result[field] = str(meta[field])

    # Add chunk metadata
    result.update(chunk_meta)

    return result


# 5. Use with ingestion service
if __name__ == '__main__':
    from ingestion_service import IngestionService

    service = IngestionService(
        document_source='./my_docs',
        chromadb_host='localhost',
        chromadb_port=8000
    )

    # Override with custom chunking
    service.chunk_document = chunk_document

    # Run ingestion
    service.ingest_all_documents()
```

---

## Summary

### Customization Checklist

- [ ] Define your document schema (fields, categories)
- [ ] Configure chunking strategies per category
- [ ] Map metadata fields to ChromaDB
- [ ] Implement access control logic
- [ ] Create test documents
- [ ] Test chunking and metadata extraction
- [ ] Document your schema for your team
- [ ] Integrate with ingestion service

### Key Principles

1. **Schema-first design** - Define your schema before implementation
2. **Category-based chunking** - Different types need different strategies
3. **Metadata is critical** - More metadata = better filtering
4. **Test with real data** - Ensure chunking works as expected
5. **Document everything** - Future you (and your team) will thank you

---

**Next:** See [RAG_INGESTION_GUIDE.md](./RAG_INGESTION_GUIDE.md) for implementation details
