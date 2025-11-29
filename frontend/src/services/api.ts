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

  // Stream chat messages using Server-Sent Events
  async streamMessage(
    conversationId: string,
    message: string,
    onEvent: (event: StreamEvent) => void,
    onError?: (error: Error) => void
  ): Promise<() => void> {
    try {
      // 1. POST message to initiate streaming
      const response = await this.api.post(
        `/chat/conversations/${conversationId}/messages/stream`,
        { message }
      );

      if (response.status !== 200 && response.status !== 201) {
        throw new Error(`Failed to send message: ${response.statusText}`);
      }

      // 2. Connect EventSource for receiving events
      // Note: The backend returns SSE response directly from the POST, so we may need to adjust this
      const eventSource = new EventSource(
        `/api/v1/chat/conversations/${conversationId}/messages/stream?lastEventId=${Date.now()}`
      );

      // 3. Handle events
      eventSource.addEventListener('message', (e) => {
        try {
          const event = JSON.parse(e.data) as StreamEvent;
          onEvent(event);

          // Auto-close on completion or error
          if (event.type === 'Completed' || event.type === 'Error') {
            eventSource.close();
          }
        } catch (error) {
          console.error('Failed to parse SSE event:', error);
        }
      });

      eventSource.addEventListener('error', (e) => {
        console.error('SSE connection error:', e);
        const error = new Error('Connection lost');
        onError?.(error);
        onEvent({ type: 'Error', error: 'Connection lost' });
        eventSource.close();
      });

      // 4. Return cleanup function
      return () => {
        eventSource.close();
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

export default new ApiService();