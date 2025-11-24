package com.alfredoalpizar.rag.client.qwen

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface QwenClient {
    /**
     * Send a chat completion request to Qwen API
     */
    fun chat(request: QwenChatRequest): Mono<QwenChatResponse>

    /**
     * Send a streaming chat completion request to Qwen API
     * Returns a stream of chunks for real-time response streaming
     */
    fun chatStream(request: QwenChatRequest): Flux<QwenStreamChunk>

    /**
     * Generate embeddings for the given texts
     */
    fun embed(texts: List<String>): Mono<List<List<Float>>>
}
