import { useState, useCallback, useRef, useEffect } from 'react';
import api from '../services/api';
import type { ChatMessage, StreamEvent, ToolCall } from '../types/api.types';

interface UseChatMessagesResult {
  messages: ChatMessage[];
  error: string | null;
  isStreaming: boolean;
  currentStatus: string | null;
  sendMessage: (content: string) => Promise<void>;
  clearMessages: () => void;
}

export function useChatMessages(conversationId: string | null): UseChatMessagesResult {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [isStreaming, setIsStreaming] = useState(false);
  const [currentStatus, setCurrentStatus] = useState<string | null>(null);

  const streamingMessageIdRef = useRef<string | null>(null);
  const cleanupFnRef = useRef<(() => void) | null>(null);
  const toolCallsMapRef = useRef<Map<string, ToolCall>>(new Map());

  // Cleanup on unmount or conversation change
  useEffect(() => {
    return () => {
      cleanupFnRef.current?.();
    };
  }, [conversationId]);

  const sendMessage = useCallback(async (content: string) => {
    if (!conversationId || !content.trim()) return;

    // Clean up previous stream
    cleanupFnRef.current?.();

    try {
      // 1. Add user message
      const userMessage: ChatMessage = {
        id: `user-${Date.now()}`,
        role: 'user',
        content: content.trim(),
        timestamp: new Date().toISOString()
      };
      setMessages(prev => [...prev, userMessage]);

      // 2. Create placeholder assistant message
      const assistantMessageId = `assistant-${Date.now()}`;
      streamingMessageIdRef.current = assistantMessageId;

      const assistantMessage: ChatMessage = {
        id: assistantMessageId,
        role: 'assistant',
        content: '',
        timestamp: new Date().toISOString(),
        metadata: {
          reasoning: [],
          toolCalls: [],
        }
      };
      setMessages(prev => [...prev, assistantMessage]);

      setIsStreaming(true);
      setError(null);
      toolCallsMapRef.current.clear();

      // 3. Start streaming
      const cleanup = await api.streamMessage(
        conversationId,
        content.trim(),
        (event: StreamEvent) => {
          handleStreamEvent(event, assistantMessageId);
        },
        (err: Error) => {
          setError(err.message);
          setIsStreaming(false);
        }
      );

      cleanupFnRef.current = cleanup;

    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to send message');
      setIsStreaming(false);
    }
  }, [conversationId]);

  const handleStreamEvent = (event: StreamEvent, messageId: string) => {
    switch (event.type) {
      case 'ResponseChunk':
        setMessages(prev => prev.map(msg =>
          msg.id === messageId
            ? { ...msg, content: event.content }
            : msg
        ));
        break;

      case 'ReasoningTrace':
        setMessages(prev => prev.map(msg =>
          msg.id === messageId
            ? {
                ...msg,
                metadata: {
                  ...msg.metadata,
                  reasoning: [...(msg.metadata?.reasoning || []), event.reasoning]
                }
              }
            : msg
        ));
        break;

      case 'ToolCallStart': {
        const toolCall: ToolCall = {
          id: event.callId || `tool-${Date.now()}`,
          name: event.toolName,
          arguments: event.arguments,
          timestamp: new Date().toISOString()
        };
        toolCallsMapRef.current.set(toolCall.id, toolCall);
        updateMessageToolCalls(messageId);
        break;
      }

      case 'ToolCallResult': {
        const callId = event.callId || '';
        const existingCall = toolCallsMapRef.current.get(callId);
        if (existingCall) {
          toolCallsMapRef.current.set(callId, {
            ...existingCall,
            result: event.result,
            success: event.success,
            error: event.error
          });
          updateMessageToolCalls(messageId);
        }
        break;
      }

      case 'ExecutionPlan':
        setMessages(prev => prev.map(msg =>
          msg.id === messageId
            ? {
                ...msg,
                metadata: {
                  ...msg.metadata,
                  executionPlan: event.plan
                }
              }
            : msg
        ));
        break;

      case 'StatusUpdate':
        setCurrentStatus(event.status);
        break;

      case 'StageTransition':
        // Could display or log
        console.log(`Stage: ${event.fromStage} → ${event.toStage}`);
        break;

      case 'Completed':
        setMessages(prev => prev.map(msg =>
          msg.id === messageId
            ? {
                ...msg,
                metadata: {
                  ...msg.metadata,
                  metrics: event.metrics
                }
              }
            : msg
        ));
        setIsStreaming(false);
        setCurrentStatus(null);
        streamingMessageIdRef.current = null;
        break;

      case 'Error':
        setError(event.error);
        setIsStreaming(false);
        setCurrentStatus(null);
        streamingMessageIdRef.current = null;
        break;
    }
  };

  const updateMessageToolCalls = (messageId: string) => {
    const toolCallsArray = Array.from(toolCallsMapRef.current.values());
    setMessages(prev => prev.map(msg =>
      msg.id === messageId
        ? {
            ...msg,
            metadata: {
              ...msg.metadata,
              toolCalls: toolCallsArray
            }
          }
        : msg
    ));
  };

  const clearMessages = useCallback(() => {
    setMessages([]);
    setError(null);
    setCurrentStatus(null);
    setIsStreaming(false);
  }, []);

  return {
    messages,
    error,
    isStreaming,
    currentStatus,
    sendMessage,
    clearMessages
  };
}
