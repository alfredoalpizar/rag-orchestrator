import { useState, useCallback, useRef, useEffect } from 'react';
import api from '../services/api';
import type { ChatMessage, StreamEvent, ToolCall, IterationData } from '../types/api.types';

interface UseChatMessagesResult {
  messages: ChatMessage[];
  error: string | null;
  isStreaming: boolean;
  currentStatus: string | null;
  currentIteration: number | null;  // Track current iteration for UI
  sendMessage: (content: string) => Promise<void>;
  clearMessages: () => void;
}

export function useChatMessages(conversationId: string | null): UseChatMessagesResult {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [isStreaming, setIsStreaming] = useState(false);
  const [currentStatus, setCurrentStatus] = useState<string | null>(null);
  const [currentIteration, setCurrentIteration] = useState<number | null>(null);

  const streamingMessageIdRef = useRef<string | null>(null);
  const cleanupFnRef = useRef<(() => void) | null>(null);
  const toolCallsMapRef = useRef<Map<string, ToolCall>>(new Map());
  // Track iteration data during streaming
  const iterationDataRef = useRef<Map<number, IterationData>>(new Map());

  // Load existing messages when conversation changes
  useEffect(() => {
    if (!conversationId) {
      setMessages([]);
      return;
    }

    // Load conversation history
    const loadHistory = async () => {
      try {
        const history = await api.getConversationMessages(conversationId);
        const loadedMessages: ChatMessage[] = history.map((msg, index) => ({
          id: `loaded-${index}-${Date.now()}`,
          role: msg.role as 'user' | 'assistant',
          content: msg.content,
          timestamp: new Date().toISOString()
        }));
        setMessages(loadedMessages);
      } catch (err) {
        console.error('Failed to load conversation history:', err);
        // Don't set error state - just start with empty messages
        setMessages([]);
      }
    };

    loadHistory();

    // Cleanup on unmount
    return () => {
      cleanupFnRef.current?.();
    };
  }, [conversationId]);

  // Counter ref for generating unique IDs without Date.now() during render
  const idCounterRef = useRef(0);
  const generateId = (prefix: string) => {
    idCounterRef.current += 1;
    return `${prefix}-${idCounterRef.current}`;
  };

  // Helper to get or create iteration data
  const getOrCreateIterationData = (iteration: number): IterationData => {
    let data = iterationDataRef.current.get(iteration);
    if (!data) {
      data = { iteration, toolCalls: [] };
      iterationDataRef.current.set(iteration, data);
    }
    return data;
  };

  // Helper to update message with current iteration data
  const updateMessageIterationData = (messageId: string) => {
    const iterationDataArray = Array.from(iterationDataRef.current.values())
      .sort((a, b) => a.iteration - b.iteration);

    setMessages(prev => prev.map(msg =>
      msg.id === messageId
        ? {
            ...msg,
            metadata: {
              ...msg.metadata,
              iterationData: iterationDataArray
            }
          }
        : msg
    ));
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

  const handleStreamEvent = (event: StreamEvent, messageId: string) => {
    switch (event.type) {
      case 'ResponseChunk': {
        const iteration = event.iteration;
        const isFinalAnswer = event.isFinalAnswer ?? false;

        if (isFinalAnswer) {
          // Final answer - append to main content
          setMessages(prev => prev.map(msg =>
            msg.id === messageId
              ? { ...msg, content: (msg.content || '') + event.content }
              : msg
          ));
        } else if (iteration) {
          // Intermediate content for this iteration
          const iterData = getOrCreateIterationData(iteration);
          iterData.intermediateContent = (iterData.intermediateContent || '') + event.content;
          updateMessageIterationData(messageId);
        } else {
          // Legacy behavior - append to main content
          setMessages(prev => prev.map(msg =>
            msg.id === messageId
              ? { ...msg, content: (msg.content || '') + event.content }
              : msg
          ));
        }
        break;
      }

      case 'ReasoningTrace': {
        const iteration = event.iteration;

        if (iteration) {
          // Add reasoning to specific iteration
          const iterData = getOrCreateIterationData(iteration);
          iterData.reasoning = (iterData.reasoning || '') + event.content;
          updateMessageIterationData(messageId);
        }

        // Also update legacy reasoningContent for backward compatibility
        setMessages(prev => prev.map(msg =>
          msg.id === messageId
            ? {
                ...msg,
                metadata: {
                  ...msg.metadata,
                  reasoningContent: (msg.metadata?.reasoningContent || '') + event.content
                }
              }
            : msg
        ));
        break;
      }

      case 'ToolCallStart': {
        const iteration = event.iteration;
        const toolCall: ToolCall = {
          id: event.toolCallId || generateId('tool'),
          name: event.toolName,
          arguments: event.arguments,
          timestamp: new Date().toISOString(),
          iteration: iteration
        };

        toolCallsMapRef.current.set(toolCall.id, toolCall);

        // Add to iteration data if we have an iteration
        if (iteration) {
          const iterData = getOrCreateIterationData(iteration);
          iterData.toolCalls = iterData.toolCalls || [];
          iterData.toolCalls.push(toolCall);
          updateMessageIterationData(messageId);
        }

        updateMessageToolCalls(messageId);
        break;
      }

      case 'ToolCallResult': {
        const callId = event.toolCallId || '';
        const iteration = event.iteration;
        const existingCall = toolCallsMapRef.current.get(callId);

        if (existingCall) {
          const updatedCall = {
            ...existingCall,
            result: event.result,
            success: event.success,
            error: event.error
          };
          toolCallsMapRef.current.set(callId, updatedCall);

          // Update in iteration data if we have an iteration
          if (iteration) {
            const iterData = getOrCreateIterationData(iteration);
            if (iterData.toolCalls) {
              const idx = iterData.toolCalls.findIndex(tc => tc.id === callId);
              if (idx >= 0) {
                iterData.toolCalls[idx] = updatedCall;
                updateMessageIterationData(messageId);
              }
            }
          }

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
                  executionPlan: {
                    reasoning: event.reasoning,
                    plannedTools: event.plannedTools
                  }
                }
              }
            : msg
        ));
        break;

      case 'StatusUpdate':
        setCurrentStatus(event.status);
        // Track current iteration for UI display
        if (event.iteration) {
          setCurrentIteration(event.iteration);
        }
        break;

      case 'StageTransition':
        // Stage transitions are tracked but not logged
        break;

      case 'Completed':
        setMessages(prev => prev.map(msg =>
          msg.id === messageId
            ? {
                ...msg,
                metadata: {
                  ...msg.metadata,
                  metrics: {
                    iterations: event.iterationsUsed,
                    totalTokens: event.tokensUsed
                  },
                  // Finalize iteration data
                  iterationData: Array.from(iterationDataRef.current.values())
                    .sort((a, b) => a.iteration - b.iteration)
                }
              }
            : msg
        ));
        setIsStreaming(false);
        setCurrentStatus(null);
        setCurrentIteration(null);
        streamingMessageIdRef.current = null;
        break;

      case 'Error':
        setError(event.error);
        setIsStreaming(false);
        setCurrentStatus(null);
        setCurrentIteration(null);
        streamingMessageIdRef.current = null;
        break;
    }
  };

  const sendMessage = useCallback(async (content: string) => {
    if (!conversationId || !content.trim()) return;

    // Clean up previous stream
    cleanupFnRef.current?.();

    try {
      // 1. Add user message
      const userMessage: ChatMessage = {
        id: generateId('user'),
        role: 'user',
        content: content.trim(),
        timestamp: new Date().toISOString()
      };
      setMessages(prev => [...prev, userMessage]);

      // 2. Create placeholder assistant message
      const assistantMessageId = generateId('assistant');
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
      setCurrentIteration(null);
      toolCallsMapRef.current.clear();
      iterationDataRef.current.clear();

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
    currentIteration,
    sendMessage,
    clearMessages
  };
}
