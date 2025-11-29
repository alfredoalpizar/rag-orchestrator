import { useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useChatConversations } from '../hooks/useChatConversations';
import { useChatMessages } from '../hooks/useChatMessages';
import { ConversationSidebar } from '../components/chat/ConversationSidebar';
import { ChatContainer } from '../components/chat/ChatContainer';

export default function ChatPage() {
  const { conversationId } = useParams<{ conversationId?: string }>();
  const navigate = useNavigate();

  const {
    conversations,
    loading: conversationsLoading,
    activeConversationId,
    createConversation,
    setActiveConversation,
  } = useChatConversations();

  const {
    messages,
    error,
    isStreaming,
    currentStatus,
    sendMessage,
    clearMessages,
  } = useChatMessages(conversationId || null);

  // Sync active conversation with URL
  useEffect(() => {
    if (conversationId && conversationId !== activeConversationId) {
      setActiveConversation(conversationId);
      clearMessages(); // Clear messages when switching conversations
    } else if (!conversationId) {
      setActiveConversation(null);
      clearMessages();
    }
  }, [conversationId, activeConversationId, setActiveConversation, clearMessages]);

  const handleNewConversation = async () => {
    try {
      const newConv = await createConversation();
      navigate(`/chat/${newConv.id}`);
    } catch (err) {
      console.error('Failed to create conversation:', err);
    }
  };

  const handleSendMessage = async (message: string) => {
    let currentConversationId = conversationId;

    // If no conversation exists, create one first
    if (!currentConversationId) {
      try {
        const newConv = await createConversation();
        currentConversationId = newConv.id;
        navigate(`/chat/${newConv.id}`);
        // Wait a bit for navigation and state updates
        await new Promise(resolve => setTimeout(resolve, 100));
      } catch (err) {
        console.error('Failed to create conversation:', err);
        return;
      }
    }

    // Send the message
    if (currentConversationId) {
      await sendMessage(message);
    }
  };

  return (
    <div className="flex h-[calc(100vh-64px)]">
      <ConversationSidebar
        conversations={conversations}
        activeConversationId={activeConversationId}
        onNewConversation={handleNewConversation}
        loading={conversationsLoading}
      />
      <ChatContainer
        messages={messages}
        onSendMessage={handleSendMessage}
        isStreaming={isStreaming}
        currentStatus={currentStatus}
        error={error}
        conversationId={conversationId || null}
      />
    </div>
  );
}
