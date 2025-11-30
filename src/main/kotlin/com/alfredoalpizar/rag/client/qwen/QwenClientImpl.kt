package com.alfredoalpizar.rag.client.qwen

import com.alfredoalpizar.rag.config.Environment
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeoutException

@Component
class QwenClientImpl(
    @Qualifier("qwenWebClient")
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper
) : QwenClient {

    private val logger = KotlinLogging.logger {}

    override fun chat(request: QwenChatRequest): Mono<QwenChatResponse> {
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

    override fun chatStream(request: QwenChatRequest): Flux<QwenStreamChunk> {
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
            .flatMap { dataBuffer ->
                // Convert DataBuffer to String and split into complete lines
                val content = dataBuffer.toString(StandardCharsets.UTF_8)
                DataBufferUtils.release(dataBuffer) // Release buffer to prevent memory leaks

                // Split by newlines - SSE sends one event per line
                // Empty lines are kept to maintain SSE protocol integrity
                val lines = content.lines()
                Flux.fromIterable(lines)
            }
            .filter { line -> line.isNotEmpty() } // Remove empty lines
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
            .timeout(Duration.ofSeconds(Environment.QWEN_TIMEOUT_SECONDS))
            .doOnComplete {
                logger.info { "Chat stream completed successfully" }
            }
            .doOnError { error ->
                logger.error(error) { "Error in Qwen chat stream" }
            }
    }

    override fun embed(texts: List<String>): Mono<List<List<Float>>> {
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
}

class QwenApiException(
    message: String,
    val statusCode: Int,
    val responseBody: String
) : RuntimeException(message)
