package com.alfredoalpizar.rag.client.chromadb

import com.alfredoalpizar.rag.config.ChromaDBProperties
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class ChromaDBClientImpl(
    private val webClient: WebClient,
    private val properties: ChromaDBProperties
) : ChromaDBClient {

    private val logger = KotlinLogging.logger {}

    override fun query(text: String, nResults: Int): Mono<List<ChromaDBResult>> {
        logger.debug { "Querying ChromaDB: text='${text.take(50)}...', nResults=$nResults" }

        val request = ChromaDBQueryRequest(
            queryTexts = listOf(text),
            nResults = nResults.coerceAtMost(properties.maxResults)
        )

        return webClient.post()
            .uri("${properties.baseUrl}/api/v1/collections/${properties.collectionName}/query")
            .bodyValue(request)
            .retrieve()
            .onStatus({ it.isError }, { response ->
                response.bodyToMono(String::class.java).flatMap { body ->
                    logger.error { "ChromaDB query error: ${response.statusCode()} - $body" }
                    Mono.error(ChromaDBException(
                        "ChromaDB query error: ${response.statusCode()}",
                        response.statusCode().value(),
                        body
                    ))
                }
            })
            .bodyToMono(ChromaDBQueryResponse::class.java)
            .map { response ->
                val results = response.toResults()
                logger.debug { "Retrieved ${results.size} results from ChromaDB" }
                results
            }
            .timeout(Duration.ofSeconds(properties.timeoutSeconds.toLong()))
            .doOnError { error ->
                logger.error(error) { "Error querying ChromaDB" }
            }
            .onErrorResume { error ->
                when (error) {
                    is ChromaDBException -> Mono.error(error)
                    is WebClientResponseException -> {
                        if (error.statusCode.value() == 404) {
                            logger.warn { "Collection '${properties.collectionName}' not found in ChromaDB" }
                            Mono.just(emptyList())
                        } else {
                            Mono.error(ChromaDBException(
                                "ChromaDB error: ${error.message}",
                                error.statusCode.value(),
                                error.responseBodyAsString
                            ))
                        }
                    }
                    else -> Mono.error(ChromaDBException(
                        "Unexpected error querying ChromaDB: ${error.message}",
                        500,
                        error.toString()
                    ))
                }
            }
    }

    override fun add(documents: List<ChromaDBDocument>): Mono<Unit> {
        logger.debug { "Adding ${documents.size} documents to ChromaDB" }

        val request = ChromaDBAddRequest(
            documents = documents.map { it.document },
            ids = documents.map { it.id },
            metadatas = documents.map { it.metadata }
        )

        return webClient.post()
            .uri("${properties.baseUrl}/api/v1/collections/${properties.collectionName}/add")
            .bodyValue(request)
            .retrieve()
            .onStatus({ it.isError }, { response ->
                response.bodyToMono(String::class.java).flatMap { body ->
                    logger.error { "ChromaDB add error: ${response.statusCode()} - $body" }
                    Mono.error(ChromaDBException(
                        "ChromaDB add error: ${response.statusCode()}",
                        response.statusCode().value(),
                        body
                    ))
                }
            })
            .bodyToMono(Void::class.java)
            .then(Mono.just(Unit))
            .timeout(Duration.ofSeconds(properties.timeoutSeconds.toLong()))
            .doOnSuccess {
                logger.debug { "Successfully added ${documents.size} documents to ChromaDB" }
            }
            .doOnError { error ->
                logger.error(error) { "Error adding documents to ChromaDB" }
            }
    }

    override fun getCollectionInfo(): Mono<ChromaDBCollection> {
        logger.debug { "Getting collection info for '${properties.collectionName}'" }

        return webClient.get()
            .uri("${properties.baseUrl}/api/v1/collections/${properties.collectionName}")
            .retrieve()
            .onStatus({ it.isError }, { response ->
                response.bodyToMono(String::class.java).flatMap { body ->
                    logger.error { "ChromaDB collection info error: ${response.statusCode()} - $body" }
                    Mono.error(ChromaDBException(
                        "ChromaDB collection info error: ${response.statusCode()}",
                        response.statusCode().value(),
                        body
                    ))
                }
            })
            .bodyToMono(ChromaDBCollectionResponse::class.java)
            .map { response ->
                ChromaDBCollection(
                    name = response.name,
                    count = 0 // ChromaDB API doesn't return count in basic response
                )
            }
            .timeout(Duration.ofSeconds(properties.timeoutSeconds.toLong()))
            .doOnSuccess { collection ->
                logger.debug { "Retrieved collection info: name=${collection.name}" }
            }
            .doOnError { error ->
                logger.error(error) { "Error getting collection info" }
            }
    }
}

class ChromaDBException(
    message: String,
    val statusCode: Int,
    val responseBody: String
) : RuntimeException(message)
