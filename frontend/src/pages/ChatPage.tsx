import { useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useChatConversations } from '../hooks/useChatConversations';
import { useChatMessages } from '../hooks/useChatMessages';
import { ConversationSidebar } from '../components/chat/ConversationSidebar';
import { ChatContainer } from '../components/chat/ChatContainer';

export default function ChatPage() {
  const { conversationId } = useParams<{ conversationId?: string }>();
  const navigate = useNavigate();
  const pendingMessageRef = useRef<string | null>(null);

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
    currentIteration,
    sendMessage,
    clearMessages,
  } = useChatMessages(conversationId || null);

  // Sync active conversation with URL
  useEffect(() => {
    if (conversationId && conversationId !== activeConversationId) {
      setActiveConversation(conversationId);
      // Messages are loaded automatically by useChatMessages hook
    } else if (!conversationId) {
      setActiveConversation(null);
    }
  }, [conversationId, activeConversationId, setActiveConversation]);

  // Send pending message after conversation is created
  useEffect(() => {
    if (conversationId && pendingMessageRef.current) {
      const messageToSend = pendingMessageRef.current;
      pendingMessageRef.current = null;
      sendMessage(messageToSend);
    }
  }, [conversationId, sendMessage]);

  const handleNewConversation = async () => {
    try {
      const newConv = await createConversation();
      navigate(`/chat/${newConv.conversationId}`);
    } catch (err) {
      console.error('Failed to create conversation:', err);
    }
  };

  const handleSendMessage = async (message: string) => {
    // If no conversation exists, create one first and store pending message
    if (!conversationId) {
      try {
        pendingMessageRef.current = message;
        const newConv = await createConversation();
        // Navigate to the new conversation (message will be sent via useEffect)
        navigate(`/chat/${newConv.conversationId}`, { replace: true });
      } catch (err) {
        console.error('Failed to create conversation:', err);
        pendingMessageRef.current = null;
      }
      return;
    }

    // Send the message to existing conversation
    await sendMessage(message);
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
        currentIteration={currentIteration}
        error={error}
        conversationId={conversationId || null}
      />
    </div>
  );
}
