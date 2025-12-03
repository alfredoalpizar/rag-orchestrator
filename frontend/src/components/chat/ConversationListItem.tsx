import { useNavigate } from 'react-router-dom';
import type { ConversationResponse } from '../../types/api.types';

interface ConversationListItemProps {
  conversation: ConversationResponse;
  isActive: boolean;
}

export function ConversationListItem({ conversation, isActive }: ConversationListItemProps) {
  const navigate = useNavigate();

  const getRelativeTime = (timestamp: string) => {
    const date = new Date(timestamp);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMins / 60);
    const diffDays = Math.floor(diffHours / 24);

    if (diffMins < 1) return 'Just now';
    if (diffMins < 60) return `${diffMins}m ago`;
    if (diffHours < 24) return `${diffHours}h ago`;
    return `${diffDays}d ago`;
  };

  const handleClick = () => {
    navigate(`/chat/${conversation.conversationId}`);
  };

  return (
    <button
      onClick={handleClick}
      className={`w-full text-left p-3 mb-2 rounded-lg border transition-colors ${
        isActive
          ? 'bg-blue-50 border-l-4 border-l-blue-600 shadow-sm border-blue-200'
          : 'bg-white border-gray-200 hover:bg-gray-50'
      }`}
    >
      <div className="font-semibold text-sm truncate mb-1">
        {conversation.title || `Conversation ${conversation.conversationId?.slice(0, 8) || 'New'}`}
      </div>
      <div className="text-xs text-gray-400 flex items-center gap-2">
        <span>{conversation.messageCount} messages</span>
        <span>•</span>
        <span>{getRelativeTime(conversation.updatedAt)}</span>
      </div>
    </button>
  );
}
