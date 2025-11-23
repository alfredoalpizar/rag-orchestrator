package com.alfredoalpizar.rag.repository

import com.alfredoalpizar.rag.model.domain.Conversation
import com.alfredoalpizar.rag.model.domain.ConversationStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ConversationRepository : JpaRepository<Conversation, String> {

    fun findByCallerId(callerId: String, pageable: Pageable): Page<Conversation>

    fun findByUserId(userId: String, pageable: Pageable): Page<Conversation>

    fun findByCallerIdAndStatus(
        callerId: String,
        status: ConversationStatus,
        pageable: Pageable
    ): Page<Conversation>

    fun findByCreatedAtBetween(
        startDate: Instant,
        endDate: Instant,
        pageable: Pageable
    ): Page<Conversation>

    fun findByStatusAndLastMessageAtBefore(
        status: ConversationStatus,
        lastMessageAt: Instant
    ): List<Conversation>

    fun findByCreatedAtBefore(createdAt: Instant): List<Conversation>

    @Query("""
        SELECT c FROM Conversation c
        WHERE c.callerId = :callerId
        AND c.status = :status
        AND c.createdAt > :since
        ORDER BY c.updatedAt DESC
    """)
    fun findRecentByCallerIdAndStatus(
        @Param("callerId") callerId: String,
        @Param("status") status: ConversationStatus,
        @Param("since") since: Instant
    ): List<Conversation>

    @Query("""
        SELECT c FROM Conversation c
        WHERE c.callerId = :callerId
        ORDER BY c.updatedAt DESC
    """)
    fun findRecentByCallerId(
        @Param("callerId") callerId: String,
        pageable: Pageable
    ): List<Conversation>
}
