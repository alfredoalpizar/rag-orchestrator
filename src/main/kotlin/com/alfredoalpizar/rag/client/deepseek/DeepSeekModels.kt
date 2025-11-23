package com.alfredoalpizar.rag.client.deepseek

import com.fasterxml.jackson.annotation.JsonProperty

// Request Models
data class DeepSeekChatRequest(
    val model: String,
    val messages: List<DeepSeekMessage>,
    val temperature: Double = 0.7,
    @JsonProperty("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean = false,
    val tools: List<DeepSeekTool>? = null
)

data class DeepSeekMessage(
    val role: String,
    val content: String?,
    @JsonProperty("tool_call_id")
    val toolCallId: String? = null,
    @JsonProperty("tool_calls")
    val toolCalls: List<DeepSeekToolCall>? = null
)

data class DeepSeekTool(
    val type: String = "function",
    val function: DeepSeekFunction
)

data class DeepSeekFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class DeepSeekToolCall(
    val id: String,
    val type: String,
    val function: DeepSeekFunctionCall
)

data class DeepSeekFunctionCall(
    val name: String,
    val arguments: String
)

// Response Models
data class DeepSeekChatResponse(
    val id: String,
    val model: String,
    val choices: List<DeepSeekChoice>,
    val usage: DeepSeekUsage?
)

data class DeepSeekChoice(
    val index: Int,
    val message: DeepSeekMessage,
    @JsonProperty("finish_reason")
    val finishReason: String?
)

data class DeepSeekUsage(
    @JsonProperty("prompt_tokens")
    val promptTokens: Int,
    @JsonProperty("completion_tokens")
    val completionTokens: Int,
    @JsonProperty("total_tokens")
    val totalTokens: Int
)

// Stream Models
data class DeepSeekStreamChunk(
    val id: String,
    val model: String,
    val choices: List<DeepSeekStreamChoice>
)

data class DeepSeekStreamChoice(
    val index: Int,
    val delta: DeepSeekDelta,
    @JsonProperty("finish_reason")
    val finishReason: String?
)

data class DeepSeekDelta(
    val role: String?,
    val content: String?,
    @JsonProperty("tool_calls")
    val toolCalls: List<DeepSeekToolCall>?
)

// Embedding Models
data class DeepSeekEmbeddingRequest(
    val model: String,
    val input: List<String>
)

data class DeepSeekEmbeddingResponse(
    val data: List<DeepSeekEmbedding>,
    val model: String,
    val usage: DeepSeekUsage
)

data class DeepSeekEmbedding(
    val index: Int,
    val embedding: List<Float>
)
