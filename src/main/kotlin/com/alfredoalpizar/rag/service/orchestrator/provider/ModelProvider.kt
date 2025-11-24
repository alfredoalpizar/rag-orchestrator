package com.alfredoalpizar.rag.service.orchestrator.provider

import com.alfredoalpizar.rag.model.domain.Message
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction layer for LLM provider clients.
 *
 * Wraps vendor-specific clients (DeepSeek, Qwen, etc.) with a unified interface.
 * Handles request/response transformation between domain models and provider-specific formats.
 *
 * @param TRequest Provider-specific request type (e.g., DeepSeekChatRequest)
 * @param TResponse Provider-specific response type (e.g., DeepSeekChatResponse)
 * @param TStreamChunk Provider-specific stream chunk type (e.g., DeepSeekStreamChunk)
 */
interface ModelProvider<TRequest, TResponse, TStreamChunk> {

    /**
     * Send a synchronous (non-streaming) chat request.
     *
     * @param request Provider-specific request
     * @return Provider-specific response
     */
    suspend fun chat(request: TRequest): TResponse

    /**
     * Send a streaming chat request.
     *
     * @param request Provider-specific request with stream=true
     * @return Flow of provider-specific stream chunks
     */
    fun chatStream(request: TRequest): Flow<TStreamChunk>

    /**
     * Build a provider-specific request from domain messages.
     *
     * @param messages Conversation messages in domain format
     * @param tools Tool definitions in domain format
     * @param config Request configuration (streaming, temperature, etc.)
     * @return Provider-specific request ready to send
     */
    fun buildRequest(
        messages: List<Message>,
        tools: List<*>,
        config: RequestConfig
    ): TRequest

    /**
     * Extract a provider-agnostic message from a synchronous response.
     *
     * @param response Provider-specific response
     * @return Normalized message with content, tool calls, token usage
     */
    fun extractMessage(response: TResponse): ProviderMessage

    /**
     * Extract a provider-agnostic chunk from a streaming response.
     *
     * @param chunk Provider-specific stream chunk
     * @return Normalized chunk with deltas and metadata
     */
    fun extractStreamChunk(chunk: TStreamChunk): ProviderStreamChunk

    /**
     * Get information about this provider's capabilities.
     */
    fun getProviderInfo(): ProviderInfo
}
