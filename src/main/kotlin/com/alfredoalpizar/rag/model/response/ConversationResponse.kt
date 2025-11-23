package com.alfredoalpizar.rag.model.response

import com.alfredoalpizar.rag.model.domain.Conversation
import com.alfredoalpizar.rag.model.domain.ConversationStatus
import java.time.Instant

data class ConversationResponse(
    val conversationId: String,
    val callerId: String,
    val userId: String?,
    val accountId: String?,
    val status: ConversationStatus,
    val messageCount: Int,
    val toolCallsCount: Int,
    val totalTokens: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastMessageAt: Instant?
)

fun Conversation.toResponse(): ConversationResponse {
    return ConversationResponse(
        conversationId = this.conversationId,
        callerId = this.callerId,
        userId = this.userId,
        accountId = this.accountId,
        status = this.status,
        messageCount = this.messageCount,
        toolCallsCount = this.toolCallsCount,
        totalTokens = this.totalTokens,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
        lastMessageAt = this.lastMessageAt
    )
}
