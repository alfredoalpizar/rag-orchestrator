package com.alfredoalpizar.rag.model.response

import java.time.Instant

sealed class StreamEvent {
    abstract val conversationId: String
    abstract val timestamp: Instant

    data class StatusUpdate(
        override val conversationId: String,
        val status: String,
        val details: String? = null,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class ToolCallStart(
        override val conversationId: String,
        val toolName: String,
        val toolCallId: String,
        val arguments: Map<String, Any>,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class ToolCallResult(
        override val conversationId: String,
        val toolName: String,
        val toolCallId: String,
        val result: String,
        val success: Boolean,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class ResponseChunk(
        override val conversationId: String,
        val content: String,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class Completed(
        override val conversationId: String,
        val iterationsUsed: Int,
        val tokensUsed: Int,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class Error(
        override val conversationId: String,
        val error: String,
        val details: String? = null,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()
}
