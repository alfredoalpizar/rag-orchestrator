import { MessageList } from './MessageList';
import { MessageInput } from './MessageInput';
import { StatusIndicator } from './StatusIndicator';
import type { ChatMessage } from '../../types/api.types';

interface ChatContainerProps {
  messages: ChatMessage[];
  onSendMessage: (message: string) => void;
  isStreaming: boolean;
  currentStatus: string | null;
  currentIteration: number | null;  // For highlighting active iteration
  error: string | null;
  conversationId: string | null;
}

export function ChatContainer({
  messages,
  onSendMessage,
  isStreaming,
  currentStatus,
  currentIteration,
  error,
  conversationId
}: ChatContainerProps) {
  return (
    <div className="flex-1 flex flex-col bg-gray-50">
      {/* Header */}
      <div className="bg-white border-b border-gray-200 px-6 py-4">
        <h2 className="text-lg font-semibold text-gray-900">
          {conversationId ? 'Chat Conversation' : 'Start a New Conversation'}
        </h2>
        {conversationId && (
          <p className="text-sm text-gray-500">
            {messages.length} {messages.length === 1 ? 'message' : 'messages'}
          </p>
        )}
      </div>

      {/* Error Banner */}
      {error && (
        <div className="bg-red-50 border-b border-red-200 px-6 py-3">
          <div className="flex items-center gap-2">
            <span className="text-red-600">⚠️</span>
            <p className="text-sm text-red-800">{error}</p>
          </div>
        </div>
      )}

      {/* Messages */}
      <MessageList messages={messages} currentIteration={currentIteration} />

      {/* Status Indicator */}
      {isStreaming && currentStatus && <StatusIndicator status={currentStatus} />}

      {/* Input */}
      <MessageInput
        onSend={onSendMessage}
        disabled={isStreaming}
        placeholder={conversationId ? 'Type your message...' : 'Start a conversation...'}
      />
    </div>
  );
}
