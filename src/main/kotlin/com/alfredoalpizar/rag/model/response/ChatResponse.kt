package com.alfredoalpizar.rag.model.response

import com.alfredoalpizar.rag.model.domain.MessageRole
import java.time.Instant

data class ChatResponse(
    val conversationId: String,
    val message: String,
    val role: MessageRole,
    val toolCallsCount: Int = 0,
    val iterationsUsed: Int = 0,
    val tokensUsed: Int = 0,
    val timestamp: Instant = Instant.now()
)

data class MessageResponse(
    val role: String,
    val content: String
)
