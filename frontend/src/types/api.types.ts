// API Type Definitions based on your Kotlin models

// Chat Types
export interface ChatRequest {
  message: string;
}

export interface CreateConversationRequest {
  callerId: string;
}

export interface ConversationResponse {
  id: string;
  callerId: string;
  createdAt: string;
  updatedAt: string;
  messageCount: number;
  title?: string;
}

export interface StreamEvent {
  type: 'thinking' | 'content' | 'error' | 'done';
  content?: string;
  error?: string;
}

// Document Types
export interface Document {
  metadata: {
    id: string;
    title: string;
    category: string;
    tags: string[];
    status: 'published' | 'draft' | 'archived';
    createdAt?: string;
    updatedAt?: string;
    version?: number;
  };
  content: {
    summary: string;
    body: string;
  };
  relations?: {
    relatedDocuments?: string[];
    prerequisites?: string[];
  };
}

export interface DocumentRef {
  id: string;
  name: string;
  category: string;
  path: string;
  sizeBytes: number;
  lastModified: string;
  lastModifiedEpoch: number;
}

export interface DocumentResponse extends Document {
  storageMetadata: {
    path: string;
    sizeBytes: number;
    lastModified: string;
  };
}

export interface DocumentListResponse {
  documents: DocumentRef[];
  total: number;
}

// Ingestion Types
export interface IngestDocumentRequest {
  filePath: string;
}

export interface IngestDocumentResponse {
  docId: string;
  status: 'SUCCESS' | 'SKIPPED' | 'FAILED';
  message?: string;
  chunks?: number;
  processingTimeMs?: number;
}

export interface IngestDirectoryRequest {
  recursive?: boolean;
}

export interface IngestDirectoryResponse {
  totalProcessed: number;
  successful: number;
  failed: number;
  skipped: number;
  processingTimeMs: number;
  results: IngestDocumentResponse[];
}

export interface IngestionStatsResponse {
  totalDocuments: number;
  totalChunks: number;
  lastIngestionAt?: string;
}

// Health Check
export interface HealthResponse {
  status: 'UP' | 'DOWN';
  components?: {
    [key: string]: {
      status: 'UP' | 'DOWN';
      details?: any;
    };
  };
}

// Search Types
export interface SearchResponse {
  results: SearchResult[];
  total: number;
  processing_time_ms: number;
  query: string;
}

export interface SearchResult {
  chunk_id: string;
  chunk_text: string;
  score: number;
  document: SearchResultDocument;
  chunk: SearchResultChunk;
}

export interface SearchResultDocument {
  doc_id: string;
  title: string;
  category: string;
  status: string;
  tags: string[];
}

export interface SearchResultChunk {
  chunk_type: string;
  chunk_heading?: string;
  step_number?: number;
  question?: string;
}

export interface SearchStatusResponse {
  status: 'UP' | 'DOWN';
  collection_name?: string;
  total_chunks?: number;
  timestamp: number;
  error?: string;
}