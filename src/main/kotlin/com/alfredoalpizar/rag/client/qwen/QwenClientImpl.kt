package com.alfredoalpizar.rag.client.qwen

import com.alfredoalpizar.rag.config.QwenProperties
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration
import java.util.concurrent.TimeoutException

@Component
class QwenClientImpl(
    private val webClient: WebClient,
    private val properties: QwenProperties,
    private val objectMapper: ObjectMapper
) : QwenClient {

    private val logger = KotlinLogging.logger {}

    override fun chat(request: QwenChatRequest): Mono<QwenChatResponse> {
        logger.debug { "Sending chat request to Qwen API: model=${request.model}" }

        return webClient.post()
            .uri("${properties.baseUrl}/v1/chat/completions")
            .header("Authorization", "Bearer ${properties.apiKey}")
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
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .doOnSuccess { response ->
                logger.debug { "Received chat response from Qwen: id=${response.id}, tokens=${response.usage?.totalTokens}" }
            }
            .doOnError { error ->
                logger.error(error) { "Error calling Qwen chat API" }
            }
    }

    override fun chatStream(request: QwenChatRequest): Flux<QwenStreamChunk> {
        val streamRequest = request.copy(stream = true)

        logger.debug { "Starting chat stream to Qwen API: model=${streamRequest.model}" }

        return webClient.post()
            .uri("${properties.baseUrl}/v1/chat/completions")
            .header("Authorization", "Bearer ${properties.apiKey}")
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
            .bodyToFlux(String::class.java)
            .filter { line ->
                line.startsWith("data: ") && line != "data: [DONE]"
            }
            .map { line ->
                parseStreamChunk(line)
            }
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .doOnComplete {
                logger.debug { "Chat stream completed" }
            }
            .doOnError { error ->
                logger.error(error) { "Error in Qwen chat stream" }
            }
    }

    override fun embed(texts: List<String>): Mono<List<List<Float>>> {
        logger.debug { "Sending embedding request to Qwen API: ${texts.size} texts" }

        val request = QwenEmbeddingRequest(
            model = properties.embeddingModel,
            input = texts
        )

        return webClient.post()
            .uri("${properties.baseUrl}/v1/embeddings")
            .header("Authorization", "Bearer ${properties.apiKey}")
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
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .doOnSuccess { embeddings ->
                logger.debug { "Received ${embeddings.size} embeddings from Qwen" }
            }
            .doOnError { error ->
                logger.error(error) { "Error calling Qwen embedding API" }
            }
    }

    private fun parseStreamChunk(line: String): QwenStreamChunk {
        val json = line.removePrefix("data: ").trim()
        return objectMapper.readValue(json, QwenStreamChunk::class.java)
    }

    private fun retrySpec(): Retry {
        return Retry.backoff(properties.maxRetries.toLong(), Duration.ofSeconds(1))
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
