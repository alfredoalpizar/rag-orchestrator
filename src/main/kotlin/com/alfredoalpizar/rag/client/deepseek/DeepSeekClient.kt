package com.alfredoalpizar.rag.client.deepseek

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface DeepSeekClient {
    fun chat(request: DeepSeekChatRequest): Mono<DeepSeekChatResponse>
    fun chatStream(request: DeepSeekChatRequest): Flux<DeepSeekStreamChunk>
    fun embed(texts: List<String>): Mono<List<List<Float>>>
}
