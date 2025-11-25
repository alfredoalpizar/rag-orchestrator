package com.alfredoalpizar.rag.service.context

import com.alfredoalpizar.rag.config.ConversationProperties
import com.alfredoalpizar.rag.model.domain.Conversation
import com.alfredoalpizar.rag.model.domain.ConversationMessage
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of ConversationStorage using ConcurrentHashMap.
 *
 * Use this for:
 * - Local development without database setup
 * - Quick prototyping and testing
 * - Learning and exploration
 *
 * Data is lost on application restart.
 */
@Component
@ConditionalOnProperty(name = ["conversation.storage-mode"], havingValue = "in-memory", matchIfMissing = true)
class InMemoryConversationStorage : ConversationStorage {

    private val logger = KotlinLogging.logger {}

    private val conversations = ConcurrentHashMap<String, Conversation>()
    private val messages = ConcurrentHashMap<String, MutableList<ConversationMessage>>()

    init {
        logger.info { "Using in-memory conversation storage (data will not persist across restarts)" }
    }

    override fun saveConversation(conversation: Conversation): Conversation {
        conversations[conversation.conversationId] = conversation
        logger.debug { "Saved conversation: ${conversation.conversationId}" }
        return conversation
    }

    override fun findConversationById(id: String): Conversation? {
        return conversations[id]
    }

    override fun findConversationsByCallerId(callerId: String, limit: Int): List<Conversation> {
        return conversations.values
            .filter { it.callerId == callerId }
            .sortedByDescending { it.updatedAt }
            .take(limit)
    }

    override fun saveMessage(message: ConversationMessage): ConversationMessage {
        val conversationId = message.conversation.conversationId
        messages.computeIfAbsent(conversationId) { mutableListOf() }.add(message)
        logger.debug { "Saved message to conversation: $conversationId, role=${message.role}" }
        return message
    }

    override fun findMessagesByConversationId(conversationId: String): List<ConversationMessage> {
        return messages[conversationId]?.sortedBy { it.createdAt } ?: emptyList()
    }
}
