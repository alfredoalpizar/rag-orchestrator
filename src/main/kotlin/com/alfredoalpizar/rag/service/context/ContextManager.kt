package com.alfredoalpizar.rag.service.context

import com.alfredoalpizar.rag.model.domain.Conversation
import com.alfredoalpizar.rag.model.domain.ConversationContext
import com.alfredoalpizar.rag.model.domain.ConversationMessage
import com.alfredoalpizar.rag.model.domain.Message
import com.alfredoalpizar.rag.model.request.CreateConversationRequest

interface ContextManager {
    suspend fun loadConversation(conversationId: String): ConversationContext
    suspend fun saveConversation(context: ConversationContext)
    suspend fun createConversation(request: CreateConversationRequest): ConversationContext
    suspend fun addMessage(conversationId: String, message: Message): ConversationContext
    suspend fun addMessageWithMetadata(conversationId: String, message: Message, metadataJson: String?): ConversationContext
    suspend fun getRecentConversations(callerId: String, limit: Int): List<Conversation>
    suspend fun getMessagesWithMetadata(conversationId: String): List<ConversationMessage>
}
