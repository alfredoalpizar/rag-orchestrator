package com.alfredoalpizar.rag.service.orchestrator.provider

import com.alfredoalpizar.rag.model.domain.ToolCall

/**
 * Provider-agnostic message extracted from LLM response.
 * Normalizes different provider formats (DeepSeek, Qwen, etc.)
 */
data class ProviderMessage(
    /**
     * Assistant message content
     */
    val content: String?,

    /**
     * Tool calls requested by the assistant
     */
    val toolCalls: List<ToolCall>?,

    /**
     * Tokens used for this response
     */
    val tokensUsed: Int,

    /**
     * Reasoning/thinking content (Qwen thinking models, DeepSeek reasoner)
     * null for models that don't support reasoning traces
     */
    val reasoningContent: String? = null
)

/**
 * Provider-agnostic streaming chunk.
 * Normalizes streaming responses from different providers.
 */
data class ProviderStreamChunk(
    /**
     * Incremental content delta
     */
    val contentDelta: String?,

    /**
     * Incremental reasoning delta (Qwen thinking models)
     */
    val reasoningDelta: String?,

    /**
     * Tool calls (may be progressively built across chunks)
     */
    val toolCalls: List<ToolCall>?,

    /**
     * Finish reason (when stream completes)
     */
    val finishReason: String?,

    /**
     * Role (usually 'assistant')
     */
    val role: String?,

    /**
     * Tokens used (typically only present in final chunk)
     */
    val tokensUsed: Int? = null
)

/**
 * Information about a model provider.
 */
data class ProviderInfo(
    /**
     * Provider name (e.g., "DeepSeek", "Qwen")
     */
    val name: String,

    /**
     * Whether this provider supports streaming responses
     */
    val supportsStreaming: Boolean,

    /**
     * Whether this provider supports reasoning/thinking streams
     */
    val supportsReasoningStream: Boolean,

    /**
     * Whether this provider supports tool calling
     */
    val supportsToolCalling: Boolean
)

/**
 * Configuration for building provider-specific requests.
 */
data class RequestConfig(
    /**
     * Whether to enable streaming in the request
     */
    val streamingEnabled: Boolean,

    /**
     * Model-specific temperature override (null = use default)
     */
    val temperature: Double?,

    /**
     * Model-specific max tokens override (null = use default)
     */
    val maxTokens: Int?,

    /**
     * Additional provider-specific parameters
     */
    val extraParams: Map<String, Any> = emptyMap()
)
