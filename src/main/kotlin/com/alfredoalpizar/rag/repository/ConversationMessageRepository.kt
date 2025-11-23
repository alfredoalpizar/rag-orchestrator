package com.alfredoalpizar.rag.repository

import com.alfredoalpizar.rag.model.domain.Conversation
import com.alfredoalpizar.rag.model.domain.ConversationMessage
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ConversationMessageRepository : JpaRepository<ConversationMessage, String> {

    fun findByConversationOrderByCreatedAtAsc(conversation: Conversation): List<ConversationMessage>

    fun findByConversation_ConversationIdOrderByCreatedAtAsc(conversationId: String): List<ConversationMessage>

    @Query("""
        SELECT m FROM ConversationMessage m
        WHERE m.conversation.conversationId = :conversationId
        ORDER BY m.createdAt DESC
    """)
    fun findRecentMessages(
        @Param("conversationId") conversationId: String,
        pageable: Pageable
    ): List<ConversationMessage>

    @Modifying
    @Query("""
        DELETE FROM ConversationMessage m
        WHERE m.conversation.conversationId = :conversationId
    """)
    fun deleteByConversation_ConversationId(@Param("conversationId") conversationId: String)

    fun countByConversation_ConversationId(conversationId: String): Long
}
