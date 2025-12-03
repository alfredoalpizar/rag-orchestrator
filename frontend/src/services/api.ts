import axios from 'axios';
import type { AxiosInstance } from 'axios';
import type {
  ConversationResponse,
  CreateConversationRequest,
  Document,
  DocumentListResponse,
  DocumentResponse,
  HealthResponse,
  IngestDirectoryResponse,
  IngestDocumentResponse,
  IngestionStatsResponse,
  SearchResponse,
  SearchStatusResponse,
  StreamEvent
} from '../types/api.types';

class ApiService {
  private api: AxiosInstance;

  constructor() {
    this.api = axios.create({
      baseURL: '/api/v1',
      headers: {
        'Content-Type': 'application/json',
      },
    });
  }

  // Health Check
  async checkHealth(): Promise<HealthResponse> {
    const { data } = await this.api.get<HealthResponse>('/health');
    return data;
  }

  // Chat API
  async createConversation(request: CreateConversationRequest): Promise<ConversationResponse> {
    const { data } = await this.api.post<ConversationResponse>('/chat/conversations', request);
    return data;
  }

  async getConversation(conversationId: string): Promise<ConversationResponse> {
    const { data } = await this.api.get<ConversationResponse>(`/chat/conversations/${conversationId}`);
    return data;
  }

  async listConversations(callerId: string, limit = 10): Promise<ConversationResponse[]> {
    const { data } = await this.api.get<ConversationResponse[]>('/chat/conversations', {
      params: { callerId, limit }
    });
    return data;
  }

  async getConversationMessages(conversationId: string): Promise<Array<MessageWithMetadata>> {
    const { data } = await this.api.get<Array<MessageWithMetadata>>(
      `/chat/conversations/${conversationId}/messages`
    );
    return data;
  }

  // Stream chat messages using Server-Sent Events
  // Uses fetch API with manual SSE parsing since backend uses POST for streaming
  async streamMessage(
    conversationId: string,
    message: string,
    onEvent: (event: StreamEvent) => void,
    onError?: (error: Error) => void
  ): Promise<() => void> {
    const controller = new AbortController();

    try {
      // POST request that streams SSE response
      const response = await fetch(
        `/api/v1/chat/conversations/${conversationId}/messages/stream`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Accept': 'text/event-stream',
          },
          body: JSON.stringify({ message }),
          signal: controller.signal,
        }
      );

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const reader = response.body?.getReader();
      if (!reader) {
        throw new Error('Response body is not readable');
      }

      // Parse SSE stream in background
      (async () => {
        const decoder = new TextDecoder();
        let buffer = '';
        let currentEventType: string | null = null;

        try {
          while (true) {
            const { done, value } = await reader.read();

            if (done) break;

            // Accumulate chunks and split by newlines
            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');

            // Keep last incomplete line in buffer
            buffer = lines.pop() || '';

            // Process complete lines
            for (const line of lines) {
              // Parse SSE event type (handle both 'event:' and 'event: ' per SSE spec)
              if (line.startsWith('event:')) {
                const value = line.slice(6);
                currentEventType = value.startsWith(' ') ? value.slice(1).trim() : value.trim();
              }

              // Parse SSE data (handle both 'data:' and 'data: ' per SSE spec)
              else if (line.startsWith('data:')) {
                const rawData = line.slice(5);
                const data = (rawData.startsWith(' ') ? rawData.slice(1) : rawData).trim();

                // Skip [DONE] marker
                if (data === '[DONE]') continue;

                try {
                  const eventData = JSON.parse(data);

                  // Combine SSE event type with parsed data
                  const event = {
                    ...eventData,
                    type: currentEventType || 'Unknown'
                  } as StreamEvent;

                  // Reset event type for next event
                  currentEventType = null;

                  // Send event to handler BEFORE aborting
                  onEvent(event);

                  // Auto-abort on completion or error (after event is processed)
                  if (event.type === 'Completed' || event.type === 'Error') {
                    controller.abort();
                    return;
                  }
                } catch (err) {
                  console.error('Failed to parse SSE event:', data, err);
                }
              }
            }
          }
        } catch (error) {
          // Ignore AbortError (expected when user cancels)
          if (error instanceof Error && error.name !== 'AbortError') {
            console.error('Stream reading error:', error);
            onError?.(error);
            onEvent({ type: 'Error', error: error.message });
          }
        }
      })();

      // Return abort function for cleanup
      return () => {
        controller.abort();
      };

    } catch (error) {
      const err = error instanceof Error ? error : new Error('Unknown error');
      onError?.(err);
      throw err;
    }
  }

  // Document Management API
  async listDocuments(recursive = true): Promise<DocumentListResponse> {
    const { data } = await this.api.get<DocumentListResponse>('/documents', {
      params: { recursive }
    });
    return data;
  }

  async getDocument(docId: string): Promise<DocumentResponse> {
    const { data } = await this.api.get<DocumentResponse>(`/documents/${docId}`);
    return data;
  }

  async createDocument(document: Document): Promise<DocumentResponse> {
    const { data } = await this.api.post<DocumentResponse>('/documents', document);
    return data;
  }

  async updateDocument(docId: string, document: Document, etag?: string): Promise<DocumentResponse> {
    const headers: any = {};
    if (etag) {
      headers['If-Match'] = etag;
    }
    const { data } = await this.api.put<DocumentResponse>(`/documents/${docId}`, document, { headers });
    return data;
  }

  async deleteDocument(docId: string): Promise<void> {
    await this.api.delete(`/documents/${docId}`);
  }

  // Ingestion API
  async ingestDocument(filePath: string): Promise<IngestDocumentResponse> {
    const { data } = await this.api.post<IngestDocumentResponse>('/ingest/document', {
      filePath
    });
    return data;
  }

  async ingestDirectory(recursive = false): Promise<IngestDirectoryResponse> {
    const { data } = await this.api.post<IngestDirectoryResponse>('/ingest/documents', {
      recursive
    });
    return data;
  }

  async reindex(recursive = false): Promise<IngestDirectoryResponse> {
    const { data } = await this.api.post<IngestDirectoryResponse>('/ingest/reindex', {
      recursive
    });
    return data;
  }

  async getIngestionStats(): Promise<IngestionStatsResponse> {
    const { data } = await this.api.get<IngestionStatsResponse>('/ingest/status');
    return data;
  }

  // Search API
  async searchDocuments(query: string, limit = 10): Promise<SearchResponse> {
    const { data } = await this.api.get<SearchResponse>('/search', {
      params: { q: query, limit }
    });
    return data;
  }

  async getSearchStatus(): Promise<SearchStatusResponse> {
    const { data } = await this.api.get<SearchStatusResponse>('/search/_status');
    return data;
  }
}

// Response type for loaded messages (matches backend MessageResponse)
export interface MessageWithMetadata {
  role: string;
  content: string;
  metadata?: {
    toolCalls?: Array<{
      id: string;
      name: string;
      arguments?: Record<string, any>;
      result?: any;
      success?: boolean;
      iteration?: number;
    }>;
    reasoning?: string;
    iterationData?: Array<{
      iteration: number;
      reasoning?: string;
      toolCalls?: Array<{
        id: string;
        name: string;
        arguments?: Record<string, any>;
        result?: any;
        success?: boolean;
        iteration?: number;
      }>;
    }>;
    metrics?: {
      iterations?: number;
      totalTokens?: number;
    };
  };
}

export default new ApiService();