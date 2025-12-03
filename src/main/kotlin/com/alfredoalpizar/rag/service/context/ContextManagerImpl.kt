package com.alfredoalpizar.rag.service.context

import com.alfredoalpizar.rag.config.Environment
import com.alfredoalpizar.rag.coroutine.currentMDCContext
import com.alfredoalpizar.rag.exception.RagException
import com.alfredoalpizar.rag.model.domain.*
import com.alfredoalpizar.rag.model.request.CreateConversationRequest
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ContextManagerImpl(
    private val storage: ConversationStorage,
    private val objectMapper: ObjectMapper
) : ContextManager {

    private val logger = KotlinLogging.logger {}

    override suspend fun loadConversation(conversationId: String): ConversationContext = withContext(Dispatchers.IO + currentMDCContext()) {
        logger.debug { "Loading conversation: $conversationId" }

        val conversation = storage.findConversationById(conversationId)
            ?: throw RagException("Conversation not found: $conversationId")

        // Load messages from storage
        val allMessages = storage.findMessagesByConversationId(conversationId)

        // Apply rolling window
        val windowSize = Environment.CONVERSATION_ROLLING_WINDOW_SIZE
        val recentMessages = if (allMessages.size > windowSize) {
            logger.debug { "Applying rolling window: ${allMessages.size} messages -> $windowSize messages" }
            allMessages.takeLast(windowSize)
        } else {
            allMessages
        }

        // Convert to in-memory format
        val messages = recentMessages.map { it.toMessage() }

        // Estimate tokens
        val totalTokens = messages.sumOf { estimateTokens(it.content) }

        logger.debug { "Loaded conversation: id=$conversationId, messages=${messages.size}, tokens=$totalTokens" }

        ConversationContext(
            conversation = conversation,
            messages = messages,
            totalTokens = totalTokens
        )
    }

    override suspend fun saveConversation(context: ConversationContext) = withContext(Dispatchers.IO + currentMDCContext()) {
        logger.debug { "Saving conversation: ${context.conversation.conversationId}" }

        context.conversation.updatedAt = Instant.now()
        storage.saveConversation(context.conversation)

        logger.debug { "Conversation saved: ${context.conversation.conversationId}" }
    }

    override suspend fun createConversation(request: CreateConversationRequest): ConversationContext = withContext(Dispatchers.IO + currentMDCContext()) {
        logger.info { "Creating new conversation for caller: ${request.callerId}" }

        val conversation = Conversation(
            callerId = request.callerId,
            userId = request.userId,
            accountId = request.accountId,
            metadata = if (request.metadata.isNotEmpty()) {
                objectMapper.writeValueAsString(request.metadata)
            } else {
                null
            }
        )

        val saved = storage.saveConversation(conversation)

        logger.info { "Created conversation: ${saved.conversationId}" }

        // If there's an initial message, add it
        if (!request.initialMessage.isNullOrBlank()) {
            val initialMessage = Message(
                role = MessageRole.USER,
                content = request.initialMessage
            )
            return@withContext addMessage(saved.conversationId, initialMessage)
        }

        ConversationContext(
            conversation = saved,
            messages = emptyList(),
            totalTokens = 0
        )
    }

    override suspend fun addMessage(conversationId: String, message: Message): ConversationContext =
        addMessageWithMetadata(conversationId, message, null)

    override suspend fun addMessageWithMetadata(
        conversationId: String,
        message: Message,
        metadataJson: String?
    ): ConversationContext = withContext(Dispatchers.IO + currentMDCContext()) {
        logger.debug { "Adding message to conversation: $conversationId, role=${message.role}, hasMetadata=${metadataJson != null}" }

        val conversation = storage.findConversationById(conversationId)
            ?: throw RagException("Conversation not found: $conversationId")

        // Create message entity with optional metadata
        val messageEntity = ConversationMessage(
            conversation = conversation,
            role = message.role,
            content = message.content,
            toolCallId = message.toolCallId,
            tokenCount = estimateTokens(message.content),
            metadata = metadataJson
        )

        storage.saveMessage(messageEntity)

        // Update conversation stats
        conversation.messageCount++
        conversation.totalTokens += messageEntity.tokenCount ?: 0
        conversation.lastMessageAt = Instant.now()
        conversation.updatedAt = Instant.now()
        storage.saveConversation(conversation)

        logger.debug { "Message added to conversation: $conversationId, total messages=${conversation.messageCount}" }

        // Reload and return updated context
        loadConversation(conversationId)
    }

    override suspend fun getMessagesWithMetadata(conversationId: String): List<ConversationMessage> = withContext(Dispatchers.IO + currentMDCContext()) {
        logger.debug { "Getting messages with metadata for conversation: $conversationId" }

        // Verify conversation exists
        storage.findConversationById(conversationId)
            ?: throw RagException("Conversation not found: $conversationId")

        // Return raw messages with metadata
        storage.findMessagesByConversationId(conversationId)
    }

    override suspend fun getRecentConversations(callerId: String, limit: Int): List<Conversation> = withContext(Dispatchers.IO + currentMDCContext()) {
        logger.debug { "Getting recent conversations for caller: $callerId, limit=$limit" }

        val conversations = storage.findConversationsByCallerId(callerId, limit)

        logger.debug { "Found ${conversations.size} conversations for caller: $callerId" }

        conversations
    }

    /**
     * Estimate token count using simple heuristic: ~4 characters per token
     */
    private fun estimateTokens(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }
}
