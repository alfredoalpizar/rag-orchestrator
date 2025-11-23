package com.alfredoalpizar.rag.model.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "conversations")
data class Conversation(
    @Id
    @Column(name = "conversation_id", length = 36)
    val conversationId: String = UUID.randomUUID().toString(),

    @Column(name = "caller_id", length = 100, nullable = false)
    val callerId: String,

    @Column(name = "user_id", length = 100)
    val userId: String? = null,

    @Column(name = "account_id", length = 100)
    val accountId: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "last_message_at")
    var lastMessageAt: Instant? = null,

    @Column(name = "message_count")
    var messageCount: Int = 0,

    @Column(name = "tool_calls_count")
    var toolCallsCount: Int = 0,

    @Column(name = "total_tokens")
    var totalTokens: Int = 0,

    @Column(name = "status", length = 20)
    @Enumerated(EnumType.STRING)
    var status: ConversationStatus = ConversationStatus.ACTIVE,

    @Column(name = "s3_key", length = 255)
    var s3Key: String? = null,

    @Column(name = "metadata", columnDefinition = "TEXT")
    var metadata: String? = null,

    @OneToMany(mappedBy = "conversation", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    val messages: MutableList<ConversationMessage> = mutableListOf()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Conversation) return false
        return conversationId == other.conversationId
    }

    override fun hashCode(): Int {
        return conversationId.hashCode()
    }

    override fun toString(): String {
        return "Conversation(conversationId='$conversationId', callerId='$callerId', status=$status, messageCount=$messageCount)"
    }
}
