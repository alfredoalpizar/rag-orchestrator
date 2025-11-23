package com.alfredoalpizar.rag.model.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "conversation_messages")
data class ConversationMessage(
    @Id
    @Column(name = "message_id", length = 36)
    val messageId: String = UUID.randomUUID().toString(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    val conversation: Conversation,

    @Column(name = "role", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    val role: MessageRole,

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    val content: String,

    @Column(name = "tool_call_id", length = 100)
    val toolCallId: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "token_count")
    val tokenCount: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConversationMessage) return false
        return messageId == other.messageId
    }

    override fun hashCode(): Int {
        return messageId.hashCode()
    }

    override fun toString(): String {
        return "ConversationMessage(messageId='$messageId', role=$role, contentLength=${content.length})"
    }

    fun toMessage(): Message {
        return Message(
            role = this.role,
            content = this.content,
            toolCallId = this.toolCallId
        )
    }
}
