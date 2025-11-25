package com.alfredoalpizar.rag.service.context

import com.alfredoalpizar.rag.model.domain.Conversation
import com.alfredoalpizar.rag.model.domain.ConversationMessage
import com.alfredoalpizar.rag.model.domain.ConversationStatus

/**
 * Abstraction for conversation storage.
 * Implementations can be in-memory (for development) or database-backed (for production).
 */
interface ConversationStorage {
    fun saveConversation(conversation: Conversation): Conversation
    fun findConversationById(id: String): Conversation?
    fun findConversationsByCallerId(callerId: String, limit: Int): List<Conversation>

    fun saveMessage(message: ConversationMessage): ConversationMessage
    fun findMessagesByConversationId(conversationId: String): List<ConversationMessage>
}
