import { useNavigate } from 'react-router-dom';
import type { ConversationResponse } from '../../types/api.types';
import { ConversationListItem } from './ConversationListItem';

interface ConversationSidebarProps {
  conversations: ConversationResponse[];
  activeConversationId: string | null;
  onNewConversation: () => void;
  loading?: boolean;
}

export function ConversationSidebar({
  conversations,
  activeConversationId,
  onNewConversation,
  loading = false
}: ConversationSidebarProps) {
  const navigate = useNavigate();

  const handleNewConversation = () => {
    onNewConversation();
    navigate('/chat');
  };

  return (
    <div className="w-[280px] bg-gray-50 border-r border-gray-200 flex flex-col">
      {/* Header */}
      <div className="p-4 border-b border-gray-200">
        <button
          onClick={handleNewConversation}
          className="w-full bg-blue-600 text-white rounded-lg px-4 py-2 hover:bg-blue-700 transition-colors font-medium text-sm"
        >
          + New Conversation
        </button>
      </div>

      {/* Conversation List */}
      <div className="flex-1 overflow-y-auto p-4">
        {loading ? (
          <div className="text-center text-gray-500 py-8">
            <div className="animate-spin h-6 w-6 border-2 border-gray-400 border-t-transparent rounded-full mx-auto mb-2" />
            <p className="text-sm">Loading conversations...</p>
          </div>
        ) : conversations.length === 0 ? (
          <div className="text-center text-gray-500 py-8">
            <div className="text-3xl mb-2">💬</div>
            <p className="text-sm">No conversations yet</p>
            <p className="text-xs mt-1">Start a new conversation</p>
          </div>
        ) : (
          <div>
            {conversations.map((conversation) => (
              <ConversationListItem
                key={conversation.conversationId}
                conversation={conversation}
                isActive={conversation.conversationId === activeConversationId}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
