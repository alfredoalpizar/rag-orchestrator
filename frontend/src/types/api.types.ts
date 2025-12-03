// API Type Definitions based on your Kotlin models

// Chat Types
export interface ChatRequest {
  message: string;
}

export interface CreateConversationRequest {
  callerId: string;
}

export interface ConversationResponse {
  conversationId: string;
  callerId: string;
  status?: string;
  createdAt: string;
  updatedAt: string;
  messageCount: number;
  toolCallsCount?: number;
  totalTokens?: number;
  title?: string;
  lastMessageAt?: string | null;
}

// Comprehensive SSE Event Types
export type StreamEvent =
  | StatusUpdateEvent
  | ToolCallStartEvent
  | ToolCallResultEvent
  | ResponseChunkEvent
  | ReasoningTraceEvent
  | ExecutionPlanEvent
  | StageTransitionEvent
  | CompletedEvent
  | ErrorEvent;

export interface StatusUpdateEvent {
  type: 'StatusUpdate';
  status: string;
  iteration?: number;  // Which agentic loop iteration this belongs to
  timestamp?: string;
}

export interface ToolCallStartEvent {
  type: 'ToolCallStart';
  toolName: string;
  arguments: Record<string, any>;
  toolCallId?: string;
  iteration?: number;  // Which agentic loop iteration this belongs to
}

export interface ToolCallResultEvent {
  type: 'ToolCallResult';
  toolCallId?: string;
  result: any;
  success: boolean;
  error?: string;
  iteration?: number;  // Which agentic loop iteration this belongs to
}

export interface ResponseChunkEvent {
  type: 'ResponseChunk';
  content: string;
  delta?: string;
  iteration?: number;  // Which agentic loop iteration this belongs to
  isFinalAnswer?: boolean;  // True when streaming from finalize_answer
}

export interface ReasoningTraceEvent {
  type: 'ReasoningTrace';
  conversationId: string;
  content: string;  // The reasoning content from backend
  stage: 'PLANNING' | 'SYNTHESIS';
  iteration?: number;  // Which agentic loop iteration this belongs to
  timestamp: string;
}

export interface ExecutionPlanEvent {
  type: 'ExecutionPlan';
  plan: string;
  steps?: string[];
}

export interface StageTransitionEvent {
  type: 'StageTransition';
  fromStage: string;
  toStage: string;
}

export interface CompletedEvent {
  type: 'Completed';
  conversationId: string;
  iterationsUsed: number;
  tokensUsed: number;
  timestamp: string;
}

export interface ErrorEvent {
  type: 'Error';
  error: string;
  code?: string;
}

// Message types for UI display
export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
  metadata?: {
    reasoning?: string[];
    reasoningContent?: string;  // Streaming thinking content
    toolCalls?: ToolCall[];
    executionPlan?: string;
    metrics?: {
      iterations?: number;
      totalTokens?: number;
    };
    // Iteration-based organization for multi-iteration agentic loops
    iterationData?: IterationData[];
  };
}

// Data for a single iteration in the agentic loop
export interface IterationData {
  iteration: number;
  reasoning?: string;  // Thinking content for this iteration
  toolCalls?: ToolCall[];  // Tool calls made in this iteration
  intermediateContent?: string;  // Any content output during this iteration (not final answer)
}

export interface ToolCall {
  id: string;
  name: string;
  arguments: Record<string, any>;
  result?: any;
  success?: boolean;
  error?: string;
  timestamp?: string;
  iteration?: number;  // Which agentic loop iteration this belongs to
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