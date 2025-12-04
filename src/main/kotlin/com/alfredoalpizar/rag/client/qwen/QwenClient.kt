package com.alfredoalpizar.rag.client.qwen

import com.alfredoalpizar.rag.config.Environment
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeoutException

@Service
class QwenClient(
    @Qualifier("qwenWebClient")
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper
) {

    private val logger = KotlinLogging.logger {}

    fun chat(request: QwenChatRequest): Mono<QwenChatResponse> {
        logger.debug { "Sending chat request to Qwen API: model=${request.model}" }

        return webClient.post()
            .uri("/chat/completions")
            .header("Authorization", "Bearer ${Environment.FIREWORKS_API_KEY}")
            .header("Content-Type", "application/json")
            .bodyValue(request)
            .retrieve()
            .onStatus({ it.isError }, { response ->
                response.bodyToMono(String::class.java).flatMap { body ->
                    logger.error { "Qwen API error: ${response.statusCode()} - $body" }
                    Mono.error(QwenApiException(
                        "Qwen API error: ${response.statusCode()}",
                        response.statusCode().value(),
                        body
                    ))
                }
            })
            .bodyToMono(QwenChatResponse::class.java)
            .retryWhen(retrySpec())
            .timeout(Duration.ofSeconds(Environment.QWEN_TIMEOUT_SECONDS))
            .doOnSuccess { response ->
                logger.debug { "Received chat response from Qwen: id=${response.id}, tokens=${response.usage?.totalTokens}" }
            }
            .doOnError { error ->
                logger.error(error) { "Error calling Qwen chat API" }
            }
    }

    fun chatStream(request: QwenChatRequest): Flux<QwenStreamChunk> {
        val streamRequest = request.copy(stream = true)

        logger.info {
            "Starting chat stream to Qwen API: model=${streamRequest.model}, " +
            "messages=${streamRequest.messages.size}, " +
            "tools=${streamRequest.tools?.size ?: 0}, " +
            "temperature=${streamRequest.temperature}, " +
            "stream=${streamRequest.stream}"
        }

        // Log first message to see context
        if (streamRequest.messages.isNotEmpty()) {
            val firstMsg = streamRequest.messages.first()
            logger.debug { "First message: role=${firstMsg.role}, content=${firstMsg.content?.take(200)}" }
        }

        return webClient.post()
            .uri("/chat/completions")
            .header("Authorization", "Bearer ${Environment.FIREWORKS_API_KEY}")
            .header("Content-Type", "application/json")
            .bodyValue(streamRequest)
            .retrieve()
            .onStatus({ it.isError }, { response ->
                response.bodyToMono(String::class.java).flatMap { body ->
                    logger.error { "Qwen API stream error: ${response.statusCode()} - $body" }
                    Mono.error(QwenApiException(
                        "Qwen API stream error: ${response.statusCode()}",
                        response.statusCode().value(),
                        body
                    ))
                }
            })
            .bodyToFlux(DataBuffer::class.java)
            .map { dataBuffer ->
                // Convert DataBuffer to String
                val content = dataBuffer.toString(StandardCharsets.UTF_8)
                DataBufferUtils.release(dataBuffer) // Release buffer to prevent memory leaks
                content
            }
            // SSE line buffering: accumulate data and emit only complete lines
            // This handles the case where JSON is split across TCP packet boundaries
            .scan(SseBuffer("", emptyList())) { buffer, chunk ->
                val combined = buffer.pending + chunk
                val lines = combined.split("\n")
                // All lines except the last are complete; the last may be incomplete
                if (lines.size > 1) {
                    val completeLines = lines.dropLast(1).filter { it.isNotBlank() }
                    val pending = lines.last()
                    SseBuffer(pending, completeLines)
                } else {
                    // No newline in this chunk yet, keep accumulating
                    SseBuffer(combined, emptyList())
                }
            }
            .flatMapIterable { buffer -> buffer.completeLines }
            .doOnNext { line ->
                logger.debug { "SSE line received (length=${line.length}): ${line.take(100)}" }
            }
            .filter { line ->
                val shouldKeep = line.startsWith("data: ") && line != "data: [DONE]"
                if (!shouldKeep && line.isNotEmpty()) {
                    logger.debug { "Filtered out: ${line.take(100)}" }
                }
                shouldKeep
            }
            .doOnNext { line ->
                logger.debug { "Processing valid SSE data line: ${line.take(100)}" }
            }
            .map { line ->
                parseStreamChunk(line)
            }
            .doOnNext { chunk ->
                logger.debug { "Chunk parsed: choices=${chunk.choices.size}" }
            }
            // Accumulate tool calls across chunks - they arrive incrementally (name first, then arguments)
            .scan(ToolCallAccumulator()) { accumulator, chunk ->
                accumulateToolCalls(accumulator, chunk)
            }
            .map { accumulator ->
                // Return the chunk, potentially with assembled tool calls if complete
                assembleChunkWithToolCalls(accumulator)
            }
            .timeout(Duration.ofSeconds(Environment.QWEN_TIMEOUT_SECONDS))
            .doOnComplete {
                logger.info { "Chat stream completed successfully" }
            }
            .doOnError { error ->
                logger.error(error) { "Error in Qwen chat stream" }
            }
    }

    fun embed(texts: List<String>): Mono<List<List<Float>>> {
        logger.debug { "Sending embedding request to Qwen API: ${texts.size} texts" }

        val request = QwenEmbeddingRequest(
            model = Environment.QWEN_EMBEDDING_MODEL,
            input = texts
        )

        return webClient.post()
            .uri("/embeddings")
            .header("Authorization", "Bearer ${Environment.FIREWORKS_API_KEY}")
            .header("Content-Type", "application/json")
            .bodyValue(request)
            .retrieve()
            .onStatus({ it.isError }, { response ->
                response.bodyToMono(String::class.java).flatMap { body ->
                    logger.error { "Qwen embedding API error: ${response.statusCode()} - $body" }
                    Mono.error(QwenApiException(
                        "Qwen embedding API error: ${response.statusCode()}",
                        response.statusCode().value(),
                        body
                    ))
                }
            })
            .bodyToMono(QwenEmbeddingResponse::class.java)
            .map { response ->
                response.data.sortedBy { it.index }.map { it.embedding }
            }
            .retryWhen(retrySpec())
            .timeout(Duration.ofSeconds(Environment.QWEN_TIMEOUT_SECONDS))
            .doOnSuccess { embeddings ->
                logger.debug { "Received ${embeddings.size} embeddings from Qwen" }
            }
            .doOnError { error ->
                logger.error(error) { "Error calling Qwen embedding API" }
            }
    }

    private fun parseStreamChunk(line: String): QwenStreamChunk {
        val json = line.removePrefix("data: ").trim()

        // DEBUG: Log raw JSON to see what Fireworks is actually sending
        logger.trace { "Raw stream chunk JSON: $json" }

        val chunk = objectMapper.readValue(json, QwenStreamChunk::class.java)

        // DEBUG: Log parsed chunk to see if reasoning_content is present
        if (chunk.choices.isNotEmpty()) {
            val choice = chunk.choices.first()
            logger.trace {
                "Parsed chunk - role=${choice.delta?.role}, " +
                        "contentDelta=${choice.delta?.content?.take(30)}, " +
                        "reasoningDelta=${choice.delta?.reasoningContent?.take(30)}, " +
                        "finishReason=${choice.finishReason}"
            }
        }

        return chunk
    }

    private fun retrySpec(): Retry {
        return Retry.backoff(Environment.QWEN_MAX_RETRIES.toLong(), Duration.ofSeconds(1))
            .filter { error ->
                error is WebClientRequestException ||
                error is TimeoutException ||
                (error is WebClientResponseException && error.statusCode.is5xxServerError)
            }
            .doBeforeRetry { signal ->
                logger.warn { "Retrying Qwen API call, attempt ${signal.totalRetries() + 1}" }
            }
    }

    /**
     * Accumulate tool call deltas from a chunk into the accumulator.
     * Tool calls stream incrementally: first the id/name, then arguments piece by piece.
     */
    private fun accumulateToolCalls(
        accumulator: ToolCallAccumulator,
        chunk: QwenStreamChunk
    ): ToolCallAccumulator {
        val choice = chunk.choices.firstOrNull()
        val toolCallDeltas = choice?.delta?.toolCalls

        // Accumulate any tool call deltas
        toolCallDeltas?.forEach { delta ->
            val index = delta.index ?: 0
            val builder = accumulator.builders.getOrPut(index) { ToolCallBuilder() }

            delta.id?.let { builder.id = it }
            delta.type?.let { builder.type = it }
            delta.function?.name?.let { builder.name = it }
            delta.function?.arguments?.let { builder.arguments.append(it) }
        }

        // Check if this is the final chunk
        val isComplete = choice?.finishReason != null

        return ToolCallAccumulator(
            builders = accumulator.builders,
            currentChunk = chunk,
            isComplete = isComplete
        )
    }

    /**
     * Assemble the final chunk with complete tool calls if the stream is finished.
     * For intermediate chunks, return them with tool_calls nulled out.
     */
    private fun assembleChunkWithToolCalls(accumulator: ToolCallAccumulator): QwenStreamChunk {
        val chunk = accumulator.currentChunk ?: return QwenStreamChunk(
            id = "",
            model = "",
            choices = emptyList(),
            usage = null
        )

        // If not complete, return chunk with tool_calls stripped (they're partial)
        if (!accumulator.isComplete) {
            return chunk.copy(
                choices = chunk.choices.map { choice ->
                    choice.copy(
                        delta = choice.delta.copy(toolCalls = null)
                    )
                }
            )
        }

        // Stream is complete - assemble full tool calls from accumulated data
        val completeToolCalls = accumulator.builders.values
            .filter { it.id != null && it.name != null }
            .map { builder ->
                QwenToolCall(
                    id = builder.id,
                    type = builder.type ?: "function",
                    index = null,
                    function = QwenFunctionCall(
                        name = builder.name,
                        arguments = builder.arguments.toString()
                    )
                )
            }

        logger.debug {
            "Tool call accumulation complete: ${completeToolCalls.size} tool calls assembled"
        }
        completeToolCalls.forEach { tc ->
            logger.debug { "  - ${tc.function?.name}(${tc.function?.arguments?.take(100)}...)" }
        }

        // Return final chunk with assembled tool calls
        return chunk.copy(
            choices = chunk.choices.map { choice ->
                choice.copy(
                    delta = choice.delta.copy(
                        toolCalls = if (completeToolCalls.isNotEmpty()) completeToolCalls else null
                    )
                )
            }
        )
    }
}

class QwenApiException(
    message: String,
    val statusCode: Int,
    val responseBody: String
) : RuntimeException(message)

/**
 * Buffer for accumulating SSE data across TCP chunk boundaries.
 * SSE events can be split across multiple network packets, so we need to
 * buffer incomplete lines until we receive a complete line ending with newline.
 */
private data class SseBuffer(
    val pending: String,        // Incomplete line data waiting for more chunks
    val completeLines: List<String>  // Complete lines ready to be emitted
)

/**
 * Accumulator for tool calls that stream incrementally.
 * Tool calls arrive in pieces: first id/name, then arguments chunk by chunk.
 */
private data class ToolCallAccumulator(
    val builders: MutableMap<Int, ToolCallBuilder> = mutableMapOf(),
    val currentChunk: QwenStreamChunk? = null,
    val isComplete: Boolean = false
)

/**
 * Builder for assembling a single tool call from streamed deltas.
 */
private class ToolCallBuilder {
    var id: String? = null
    var type: String? = null
    var name: String? = null
    val arguments: StringBuilder = StringBuilder()
}
