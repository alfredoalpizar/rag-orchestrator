import { useState, useEffect, useCallback } from 'react';
import api from '../services/api';
import type { ConversationResponse } from '../types/api.types';

const CALLER_ID = 'web-user'; // Hardcoded for MVP

interface UseChatConversationsResult {
  conversations: ConversationResponse[];
  loading: boolean;
  error: string | null;
  activeConversationId: string | null;
  createConversation: () => Promise<ConversationResponse>;
  loadConversations: () => Promise<void>;
  setActiveConversation: (id: string | null) => void;
}

export function useChatConversations(): UseChatConversationsResult {
  const [conversations, setConversations] = useState<ConversationResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeConversationId, setActiveConversationId] = useState<string | null>(null);

  const loadConversations = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.listConversations(CALLER_ID, 50);
      setConversations(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load conversations');
      console.error('Failed to load conversations:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadConversations();
  }, [loadConversations]);

  const createConversation = async (): Promise<ConversationResponse> => {
    try {
      const newConv = await api.createConversation({ callerId: CALLER_ID });
      await loadConversations(); // Refresh list
      setActiveConversationId(newConv.conversationId);
      return newConv;
    } catch (err) {
      throw new Error(err instanceof Error ? err.message : 'Failed to create conversation');
    }
  };

  return {
    conversations,
    loading,
    error,
    activeConversationId,
    createConversation,
    loadConversations,
    setActiveConversation: setActiveConversationId
  };
}
