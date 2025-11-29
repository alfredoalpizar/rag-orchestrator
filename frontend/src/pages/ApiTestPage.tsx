import { useState, useEffect } from 'react';
import api from '../services/api';
import type { HealthResponse, ConversationResponse, DocumentListResponse, IngestionStatsResponse } from '../types/api.types';

export default function ApiTestPage() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [conversations, setConversations] = useState<ConversationResponse[]>([]);
  const [documents, setDocuments] = useState<DocumentListResponse | null>(null);
  const [ingestionStats, setIngestionStats] = useState<IngestionStatsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    testApis();
  }, []);

  const testApis = async () => {
    setLoading(true);
    setError(null);

    try {
      // Test Health Check
      const healthData = await api.checkHealth();
      setHealth(healthData);
      console.log('Health check:', healthData);

      // Test Document List
      const docsData = await api.listDocuments();
      setDocuments(docsData);
      console.log('Documents:', docsData);

      // Test Ingestion Stats
      const statsData = await api.getIngestionStats();
      setIngestionStats(statsData);
      console.log('Ingestion stats:', statsData);

      // Test Conversation List (will likely be empty)
      const convData = await api.listConversations('test-user', 10);
      setConversations(convData);
      console.log('Conversations:', convData);

    } catch (err) {
      console.error('API test failed:', err);
      setError(err instanceof Error ? err.message : 'Unknown error occurred');
    } finally {
      setLoading(false);
    }
  };

  const createTestConversation = async () => {
    try {
      const conversation = await api.createConversation({ callerId: 'test-user' });
      console.log('Created conversation:', conversation);
      alert(`Created conversation: ${conversation.id}`);
      testApis(); // Refresh data
    } catch (err) {
      console.error('Failed to create conversation:', err);
      alert('Failed to create conversation');
    }
  };

  if (loading) {
    return <div className="p-4">Loading API tests...</div>;
  }

  if (error) {
    return (
      <div className="p-4">
        <h2 className="text-red-600 text-xl font-bold mb-2">Error</h2>
        <p className="text-red-500">{error}</p>
        <button
          onClick={testApis}
          className="mt-4 px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
        >
          Retry
        </button>
      </div>
    );
  }

  return (
    <div className="p-4 max-w-6xl mx-auto">
      <h1 className="text-3xl font-bold mb-6">RAG Orchestrator API Test</h1>

      {/* Health Status */}
      <div className="mb-8 p-4 border rounded-lg">
        <h2 className="text-xl font-semibold mb-2">Health Check</h2>
        <div className={`inline-block px-3 py-1 rounded ${
          health?.status === 'UP' ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
        }`}>
          Status: {health?.status || 'Unknown'}
        </div>
      </div>

      {/* Document Stats */}
      <div className="mb-8 p-4 border rounded-lg">
        <h2 className="text-xl font-semibold mb-2">Documents</h2>
        <p>Total Documents: {documents?.total || 0}</p>
        {documents?.documents.slice(0, 5).map(doc => (
          <div key={doc.docId} className="mt-2 p-2 bg-gray-50 rounded">
            <span className="font-mono text-sm">{doc.docId}</span>
            <span className="ml-2 text-gray-600">({doc.category})</span>
          </div>
        ))}
      </div>

      {/* Ingestion Stats */}
      <div className="mb-8 p-4 border rounded-lg">
        <h2 className="text-xl font-semibold mb-2">Ingestion Statistics</h2>
        <p>Total Documents: {ingestionStats?.totalDocuments || 0}</p>
        <p>Total Chunks: {ingestionStats?.totalChunks || 0}</p>
        {ingestionStats?.lastIngestionAt && (
          <p>Last Ingestion: {new Date(ingestionStats.lastIngestionAt).toLocaleString()}</p>
        )}
      </div>

      {/* Conversations */}
      <div className="mb-8 p-4 border rounded-lg">
        <h2 className="text-xl font-semibold mb-2">Conversations</h2>
        <p>Total: {conversations.length}</p>
        <button
          onClick={createTestConversation}
          className="mt-2 px-4 py-2 bg-green-500 text-white rounded hover:bg-green-600"
        >
          Create Test Conversation
        </button>
        {conversations.map(conv => (
          <div key={conv.id} className="mt-2 p-2 bg-gray-50 rounded">
            <span className="font-mono text-sm">{conv.id}</span>
            <span className="ml-2 text-gray-600">Messages: {conv.messageCount}</span>
          </div>
        ))}
      </div>

      <button
        onClick={testApis}
        className="px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
      >
        Refresh All
      </button>
    </div>
  );
}