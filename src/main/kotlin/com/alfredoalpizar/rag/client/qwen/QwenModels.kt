package com.alfredoalpizar.rag.client.qwen

import com.fasterxml.jackson.annotation.JsonProperty

// Request Models
data class QwenChatRequest(
    val model: String,
    val messages: List<QwenMessage>,
    val temperature: Double = 0.7,
    @JsonProperty("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean = false,
    val tools: List<QwenTool>? = null,
    // Note: enable_thinking removed - not supported by Fireworks AI API
    // The qwen3-235b-a22b-thinking model handles reasoning automatically
    @JsonProperty("top_p")
    val topP: Double? = null,
    @JsonProperty("top_k")
    val topK: Int? = null,
    @JsonProperty("presence_penalty")
    val presencePenalty: Double? = null,
    @JsonProperty("frequency_penalty")
    val frequencyPenalty: Double? = null
)

data class QwenMessage(
    val role: String,
    val content: String?,
    @JsonProperty("tool_call_id")
    val toolCallId: String? = null,
    @JsonProperty("tool_calls")
    val toolCalls: List<QwenToolCall>? = null,
    @JsonProperty("reasoning_content")
    val reasoningContent: String? = null
)

data class QwenTool(
    val type: String = "function",
    val function: QwenFunction
)

data class QwenFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class QwenToolCall(
    val id: String,
    val type: String,
    val function: QwenFunctionCall
)

data class QwenFunctionCall(
    val name: String,
    val arguments: String
)

// Response Models
data class QwenChatResponse(
    val id: String,
    val model: String,
    val choices: List<QwenChoice>,
    val usage: QwenUsage?
)

data class QwenChoice(
    val index: Int,
    val message: QwenMessage,
    @JsonProperty("finish_reason")
    val finishReason: String?
)

data class QwenUsage(
    @JsonProperty("prompt_tokens")
    val promptTokens: Int,
    @JsonProperty("completion_tokens")
    val completionTokens: Int,
    @JsonProperty("total_tokens")
    val totalTokens: Int
)

// Stream Models
data class QwenStreamChunk(
    val id: String,
    val model: String,
    val choices: List<QwenStreamChoice>,
    val usage: QwenUsage? = null  // Usage sent in final chunk
)

data class QwenStreamChoice(
    val index: Int,
    val delta: QwenDelta,
    @JsonProperty("finish_reason")
    val finishReason: String?
)

data class QwenDelta(
    val role: String?,
    val content: String?,
    @JsonProperty("reasoning_content")
    val reasoningContent: String?,
    @JsonProperty("tool_calls")
    val toolCalls: List<QwenToolCall>?
)

// Embedding Models
data class QwenEmbeddingRequest(
    val model: String,
    val input: List<String>
)

data class QwenEmbeddingResponse(
    val data: List<QwenEmbedding>,
    val model: String,
    val usage: QwenUsage
)

data class QwenEmbedding(
    val index: Int,
    val embedding: List<Float>
)
