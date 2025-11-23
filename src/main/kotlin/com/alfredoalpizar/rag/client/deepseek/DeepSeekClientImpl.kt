package com.alfredoalpizar.rag.client.deepseek

import com.alfredoalpizar.rag.config.DeepSeekProperties
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
class DeepSeekClientImpl(
    private val webClient: WebClient,
    private val properties: DeepSeekProperties,
    private val objectMapper: ObjectMapper
) : DeepSeekClient {

    private val logger = KotlinLogging.logger {}

    override fun chat(request: DeepSeekChatRequest): Mono<DeepSeekChatResponse> {
        logger.debug { "Sending chat request to DeepSeek API: model=${request.model}" }

        return webClient.post()
            .uri("${properties.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${properties.apiKey}")
            .header("Content-Type", "application/json")
            .bodyValue(request)
            .retrieve()
            .onStatus({ it.isError }, { response ->
                response.bodyToMono(String::class.java).flatMap { body ->
                    logger.error { "DeepSeek API error: ${response.statusCode()} - $body" }
                    Mono.error(DeepSeekApiException(
                        "DeepSeek API error: ${response.statusCode()}",
                        response.statusCode().value(),
                        body
                    ))
                }
            })
            .bodyToMono(DeepSeekChatResponse::class.java)
            .retryWhen(retrySpec())
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .doOnSuccess { response ->
                logger.debug { "Received chat response from DeepSeek: id=${response.id}, tokens=${response.usage?.totalTokens}" }
            }
            .doOnError { error ->
                logger.error(error) { "Error calling DeepSeek chat API" }
            }
    }

    override fun chatStream(request: DeepSeekChatRequest): Flux<DeepSeekStreamChunk> {
        val streamRequest = request.copy(stream = true)

        logger.debug { "Starting chat stream to DeepSeek API: model=${streamRequest.model}" }

        return webClient.post()
            .uri("${properties.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${properties.apiKey}")
            .header("Content-Type", "application/json")
            .bodyValue(streamRequest)
            .retrieve()
            .onStatus({ it.isError }, { response ->
                response.bodyToMono(String::class.java).flatMap { body ->
                    logger.error { "DeepSeek API stream error: ${response.statusCode()} - $body" }
                    Mono.error(DeepSeekApiException(
                        "DeepSeek API stream error: ${response.statusCode()}",
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
                logger.error(error) { "Error in DeepSeek chat stream" }
            }
    }

    override fun embed(texts: List<String>): Mono<List<List<Float>>> {
        logger.debug { "Sending embedding request to DeepSeek API: ${texts.size} texts" }

        val request = DeepSeekEmbeddingRequest(
            model = properties.embeddingModel,
            input = texts
        )

        return webClient.post()
            .uri("${properties.baseUrl}/embeddings")
            .header("Authorization", "Bearer ${properties.apiKey}")
            .header("Content-Type", "application/json")
            .bodyValue(request)
            .retrieve()
            .onStatus({ it.isError }, { response ->
                response.bodyToMono(String::class.java).flatMap { body ->
                    logger.error { "DeepSeek embedding API error: ${response.statusCode()} - $body" }
                    Mono.error(DeepSeekApiException(
                        "DeepSeek embedding API error: ${response.statusCode()}",
                        response.statusCode().value(),
                        body
                    ))
                }
            })
            .bodyToMono(DeepSeekEmbeddingResponse::class.java)
            .map { response ->
                response.data.sortedBy { it.index }.map { it.embedding }
            }
            .retryWhen(retrySpec())
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .doOnSuccess { embeddings ->
                logger.debug { "Received ${embeddings.size} embeddings from DeepSeek" }
            }
            .doOnError { error ->
                logger.error(error) { "Error calling DeepSeek embedding API" }
            }
    }

    private fun parseStreamChunk(line: String): DeepSeekStreamChunk {
        val json = line.removePrefix("data: ").trim()
        return objectMapper.readValue(json, DeepSeekStreamChunk::class.java)
    }

    private fun retrySpec(): Retry {
        return Retry.backoff(properties.maxRetries.toLong(), Duration.ofSeconds(1))
            .filter { error ->
                error is WebClientRequestException ||
                error is TimeoutException ||
                (error is WebClientResponseException && error.statusCode.is5xxServerError)
            }
            .doBeforeRetry { signal ->
                logger.warn { "Retrying DeepSeek API call, attempt ${signal.totalRetries() + 1}" }
            }
    }
}

class DeepSeekApiException(
    message: String,
    val statusCode: Int,
    val responseBody: String
) : RuntimeException(message)
