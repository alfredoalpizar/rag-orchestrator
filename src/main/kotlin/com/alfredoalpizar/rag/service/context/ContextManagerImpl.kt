package com.alfredoalpizar.rag.service.context

import com.alfredoalpizar.rag.config.ConversationProperties
import com.alfredoalpizar.rag.exception.RagException
import com.alfredoalpizar.rag.model.domain.*
import com.alfredoalpizar.rag.model.request.CreateConversationRequest
import com.alfredoalpizar.rag.repository.ConversationMessageRepository
import com.alfredoalpizar.rag.repository.ConversationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ContextManagerImpl(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: ConversationMessageRepository,
    private val properties: ConversationProperties,
    private val objectMapper: ObjectMapper
) : ContextManager {

    private val logger = KotlinLogging.logger {}

    override suspend fun loadConversation(conversationId: String): ConversationContext = withContext(Dispatchers.IO) {
        logger.debug { "Loading conversation: $conversationId" }

        val conversation = conversationRepository.findById(conversationId)
            .orElseThrow { RagException("Conversation not found: $conversationId") }

        // Load messages from database
        val allMessages = messageRepository.findByConversation_ConversationIdOrderByCreatedAtAsc(conversationId)

        // Apply rolling window
        val windowSize = properties.rollingWindowSize
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

    override suspend fun saveConversation(context: ConversationContext) = withContext(Dispatchers.IO) {
        logger.debug { "Saving conversation: ${context.conversation.conversationId}" }

        context.conversation.updatedAt = Instant.now()
        conversationRepository.save(context.conversation)

        logger.debug { "Conversation saved: ${context.conversation.conversationId}" }
    }

    override suspend fun createConversation(request: CreateConversationRequest): ConversationContext = withContext(Dispatchers.IO) {
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

        val saved = conversationRepository.save(conversation)

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

    override suspend fun addMessage(conversationId: String, message: Message): ConversationContext = withContext(Dispatchers.IO) {
        logger.debug { "Adding message to conversation: $conversationId, role=${message.role}" }

        val conversation = conversationRepository.findById(conversationId)
            .orElseThrow { RagException("Conversation not found: $conversationId") }

        // Create message entity
        val messageEntity = ConversationMessage(
            conversation = conversation,
            role = message.role,
            content = message.content,
            toolCallId = message.toolCallId,
            tokenCount = estimateTokens(message.content)
        )

        messageRepository.save(messageEntity)

        // Update conversation stats
        conversation.messageCount++
        conversation.totalTokens += messageEntity.tokenCount ?: 0
        conversation.lastMessageAt = Instant.now()
        conversation.updatedAt = Instant.now()
        conversationRepository.save(conversation)

        logger.debug { "Message added to conversation: $conversationId, total messages=${conversation.messageCount}" }

        // Reload and return updated context
        loadConversation(conversationId)
    }

    override suspend fun getRecentConversations(callerId: String, limit: Int): List<Conversation> = withContext(Dispatchers.IO) {
        logger.debug { "Getting recent conversations for caller: $callerId, limit=$limit" }

        val pageable = PageRequest.of(0, limit)
        val conversations = conversationRepository.findRecentByCallerId(callerId, pageable)

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
