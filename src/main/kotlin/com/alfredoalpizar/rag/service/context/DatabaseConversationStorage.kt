package com.alfredoalpizar.rag.service.context

import com.alfredoalpizar.rag.model.domain.Conversation
import com.alfredoalpizar.rag.model.domain.ConversationMessage
import com.alfredoalpizar.rag.repository.ConversationMessageRepository
import com.alfredoalpizar.rag.repository.ConversationRepository
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

/**
 * Database-backed implementation of ConversationStorage using JPA repositories.
 *
 * Use this for:
 * - Production deployments requiring persistence
 * - Audit trail requirements
 * - Multi-instance deployments (shared state)
 *
 * Requires database configuration (PostgreSQL recommended).
 */
@Component
@ConditionalOnProperty(name = ["conversation.storage-mode"], havingValue = "database")
class DatabaseConversationStorage(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: ConversationMessageRepository
) : ConversationStorage {

    private val logger = KotlinLogging.logger {}

    init {
        logger.info { "Using database conversation storage" }
    }

    override fun saveConversation(conversation: Conversation): Conversation {
        return conversationRepository.save(conversation)
    }

    override fun findConversationById(id: String): Conversation? {
        return conversationRepository.findById(id).orElse(null)
    }

    override fun findConversationsByCallerId(callerId: String, limit: Int): List<Conversation> {
        val pageable = PageRequest.of(0, limit)
        return conversationRepository.findRecentByCallerId(callerId, pageable)
    }

    override fun saveMessage(message: ConversationMessage): ConversationMessage {
        return messageRepository.save(message)
    }

    override fun findMessagesByConversationId(conversationId: String): List<ConversationMessage> {
        return messageRepository.findByConversation_ConversationIdOrderByCreatedAtAsc(conversationId)
    }
}
