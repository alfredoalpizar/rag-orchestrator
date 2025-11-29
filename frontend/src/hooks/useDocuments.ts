import { useState, useEffect, useCallback } from 'react';
import api from '../services/api';
import type { Document, DocumentListResponse, DocumentResponse } from '../types/api.types';

// Backend response format from Kotlin
interface BackendDocumentResponse {
  document: Document;
  storage: {
    path: string;
    lastModified: string;
    lastModifiedEpoch: number;
    sizeBytes: number;
  };
}

// Transform backend format to frontend expected format
function transformDocumentResponse(backendResponse: BackendDocumentResponse): DocumentResponse {
  return {
    metadata: backendResponse.document.metadata,
    content: backendResponse.document.content,
    relations: backendResponse.document.relations,
    storageMetadata: {
      path: backendResponse.storage.path,
      sizeBytes: backendResponse.storage.sizeBytes,
      lastModified: backendResponse.storage.lastModified
    }
  };
}

interface UseDocumentsResult {
  documents: DocumentListResponse | null;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
  createDocument: (doc: Document) => Promise<DocumentResponse>;
  updateDocument: (docId: string, doc: Document, etag?: string) => Promise<DocumentResponse>;
  deleteDocument: (docId: string) => Promise<void>;
  getDocument: (docId: string) => Promise<DocumentResponse>;
}

export function useDocuments(): UseDocumentsResult {
  const [documents, setDocuments] = useState<DocumentListResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchDocuments = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.listDocuments(true);
      setDocuments(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch documents');
      console.error('Failed to fetch documents:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchDocuments();
  }, [fetchDocuments]);

  const createDocument = async (doc: Document): Promise<DocumentResponse> => {
    try {
      const backendResponse = await api.createDocument(doc) as unknown as BackendDocumentResponse;
      await fetchDocuments(); // Refresh list
      return transformDocumentResponse(backendResponse);
    } catch (err) {
      throw new Error(err instanceof Error ? err.message : 'Failed to create document');
    }
  };

  const updateDocument = async (docId: string, doc: Document, etag?: string): Promise<DocumentResponse> => {
    try {
      const backendResponse = await api.updateDocument(docId, doc, etag) as unknown as BackendDocumentResponse;
      await fetchDocuments(); // Refresh list
      return transformDocumentResponse(backendResponse);
    } catch (err) {
      throw new Error(err instanceof Error ? err.message : 'Failed to update document');
    }
  };

  const deleteDocument = async (docId: string): Promise<void> => {
    try {
      await api.deleteDocument(docId);
      await fetchDocuments(); // Refresh list
    } catch (err) {
      throw new Error(err instanceof Error ? err.message : 'Failed to delete document');
    }
  };

  const getDocument = async (docId: string): Promise<DocumentResponse> => {
    try {
      const backendResponse = await api.getDocument(docId) as unknown as BackendDocumentResponse;
      return transformDocumentResponse(backendResponse);
    } catch (err) {
      throw new Error(err instanceof Error ? err.message : 'Failed to get document');
    }
  };

  return {
    documents,
    loading,
    error,
    refresh: fetchDocuments,
    createDocument,
    updateDocument,
    deleteDocument,
    getDocument
  };
}