package com.alfredoalpizar.rag.model.response

import com.alfredoalpizar.rag.model.domain.MessageRole
import com.fasterxml.jackson.annotation.JsonInclude
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

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MessageResponse(
    val role: String,
    val content: String,
    val metadata: MessageMetadataResponse? = null
)

/**
 * Metadata response for conversation history.
 * Mirrors the structure expected by the frontend.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MessageMetadataResponse(
    val toolCalls: List<ToolCallResponse>? = null,
    val reasoning: String? = null,
    val iterationData: List<IterationResponse>? = null,
    val metrics: MetricsResponse? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolCallResponse(
    val id: String,
    val name: String,
    val arguments: Map<String, Any>? = null,
    val result: Any? = null,
    val success: Boolean? = null,
    val iteration: Int? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class IterationResponse(
    val iteration: Int,
    val reasoning: String? = null,
    val toolCalls: List<ToolCallResponse>? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MetricsResponse(
    val iterations: Int? = null,
    val totalTokens: Int? = null
)
