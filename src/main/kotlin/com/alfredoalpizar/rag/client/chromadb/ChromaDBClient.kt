package com.alfredoalpizar.rag.client.chromadb

import com.alfredoalpizar.rag.config.Environment
import com.alfredoalpizar.rag.service.chromadb.ChromaDBCollectionManager
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import java.time.Duration

@Service
class ChromaDBClient(
    @Qualifier("chromaDBWebClient")
    private val webClient: WebClient,
    private val collectionManager: ChromaDBCollectionManager
) {

    private val logger = KotlinLogging.logger {}

    /**
     * Get the collection URL path with UUID.
     * Format: /api/v2/tenants/{tenant}/databases/{database}/collections/{uuid}
     */
    private fun getCollectionUrlPath(operation: String = ""): Mono<String> {
        return mono {
            val uuid = collectionManager.getCollectionUuid()
                ?: throw ChromaDBException(
                    "Failed to get collection UUID",
                    500,
                    "Collection not found or could not be created"
                )
            val basePath =
                "/api/v2/tenants/${Environment.CHROMADB_TENANT}/databases/${Environment.CHROMADB_DATABASE}/collections/$uuid"
            if (operation.isNotEmpty()) "$basePath/$operation" else basePath
        }
    }

    fun query(text: String, nResults: Int = 5): Mono<List<ChromaDBResult>> {
        logger.debug { "Querying ChromaDB: text='${text.take(50)}...', nResults=$nResults" }

        val request = ChromaDBQueryRequest(
            queryTexts = listOf(text),
            nResults = nResults.coerceAtMost(Environment.CHROMADB_MAX_RESULTS)
        )

        return getCollectionUrlPath("query")
            .flatMap { url ->
                webClient.post()
                    .uri(url)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus({ it.isError }, { response ->
                        response.bodyToMono(String::class.java).flatMap { body ->
                            logger.error { "ChromaDB query error: ${response.statusCode()} - $body" }
                            Mono.error(
                                ChromaDBException(
                                    "ChromaDB query error: ${response.statusCode()}",
                                    response.statusCode().value(),
                                    body
                                )
                            )
                        }
                    })
                    .bodyToMono(ChromaDBQueryResponse::class.java)
                    .map { response ->
                        val results = response.toResults()
                        logger.debug { "Retrieved ${results.size} results from ChromaDB" }
                        results
                    }
                    .timeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
                    .doOnError { error ->
                        logger.error(error) { "Error querying ChromaDB" }
                    }
                    .onErrorResume { error ->
                        when (error) {
                            is ChromaDBException -> Mono.error(error)
                            is WebClientResponseException -> {
                                logger.error { "ChromaDB API error ${error.statusCode}: ${error.responseBodyAsString}" }
                                Mono.error(
                                    ChromaDBException(
                                        "ChromaDB API error: ${error.statusCode} - Collection may not exist",
                                        error.statusCode.value(),
                                        error.responseBodyAsString
                                    )
                                )
                            }

                            else -> Mono.error(
                                ChromaDBException(
                                    "Unexpected error querying ChromaDB: ${error.message}",
                                    500,
                                    error.toString()
                                )
                            )
                        }
                    }
            }
    }

    fun add(documents: List<ChromaDBDocument>): Mono<Unit> {
        logger.debug { "Adding ${documents.size} documents to ChromaDB" }

        val request = ChromaDBAddRequest(
            documents = documents.map { it.document },
            ids = documents.map { it.id },
            metadatas = documents.map { it.metadata }
        )

        return getCollectionUrlPath("add")
            .flatMap { url ->
                webClient.post()
                    .uri(url)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus({ it.isError }, { response ->
                        response.bodyToMono(String::class.java).flatMap { body ->
                            logger.error { "ChromaDB add error: ${response.statusCode()} - $body" }
                            Mono.error(
                                ChromaDBException(
                                    "ChromaDB add error: ${response.statusCode()}",
                                    response.statusCode().value(),
                                    body
                                )
                            )
                        }
                    })
                    .bodyToMono(Void::class.java)
                    .then(Mono.just(Unit))
                    .timeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
                    .doOnSuccess {
                        logger.debug { "Successfully added ${documents.size} documents to ChromaDB" }
                    }
                    .doOnError { error ->
                        logger.error(error) { "Error adding documents to ChromaDB" }
                    }
            }
    }

    fun queryWithEmbedding(
        embedding: List<Float>,
        nResults: Int = 5,
        where: Map<String, Any>? = null
    ): Mono<List<ChromaDBResult>> {
        logger.debug { "Querying ChromaDB with embedding: dimensions=${embedding.size}, nResults=$nResults" }

        val request = ChromaDBQueryEmbeddingRequest(
            queryEmbeddings = listOf(embedding),
            nResults = nResults.coerceAtMost(Environment.CHROMADB_MAX_RESULTS),
            where = where
        )

        return getCollectionUrlPath("query")
            .flatMap { url ->
                webClient.post()
                    .uri(url)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus({ it.isError }, { response ->
                        response.bodyToMono(String::class.java).flatMap { body ->
                            logger.error { "ChromaDB query error: ${response.statusCode()} - $body" }
                            Mono.error(
                                ChromaDBException(
                                    "ChromaDB query error: ${response.statusCode()}",
                                    response.statusCode().value(),
                                    body
                                )
                            )
                        }
                    })
                    .bodyToMono(ChromaDBQueryResponse::class.java)
                    .map { response ->
                        val results = response.toResults()
                        logger.debug { "Retrieved ${results.size} results from ChromaDB" }
                        results
                    }
                    .timeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
                    .doOnError { error ->
                        logger.error(error) { "Error querying ChromaDB with embedding" }
                    }
                    .onErrorResume { error ->
                        when (error) {
                            is ChromaDBException -> Mono.error(error)
                            else -> Mono.error(
                                ChromaDBException(
                                    "Unexpected error querying ChromaDB: ${error.message}",
                                    500,
                                    error.toString()
                                )
                            )
                        }
                    }
            }
    }

    fun upsert(
        ids: List<String>,
        embeddings: List<List<Float>>,
        documents: List<String>,
        metadatas: List<Map<String, Any>>
    ): Mono<Unit> {
        logger.debug { "Upserting ${ids.size} documents to ChromaDB with embeddings" }

        val request = ChromaDBUpsertRequest(
            documents = documents,
            ids = ids,
            embeddings = embeddings,
            metadatas = metadatas
        )

        return getCollectionUrlPath("upsert")
            .flatMap { url ->
                webClient.post()
                    .uri(url)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus({ it.isError }, { response ->
                        response.bodyToMono(String::class.java).flatMap { body ->
                            logger.error { "ChromaDB upsert error: ${response.statusCode()} - $body" }
                            Mono.error(
                                ChromaDBException(
                                    "ChromaDB upsert error: ${response.statusCode()}",
                                    response.statusCode().value(),
                                    body
                                )
                            )
                        }
                    })
                    .bodyToMono(Void::class.java)
                    .then(Mono.just(Unit))
                    .timeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
                    .doOnSuccess {
                        logger.debug { "Successfully upserted ${ids.size} documents to ChromaDB" }
                    }
                    .doOnError { error ->
                        logger.error(error) { "Error upserting documents to ChromaDB" }
                    }
            }
    }

    fun delete(ids: List<String>): Mono<Unit> {
        logger.debug { "Deleting ${ids.size} documents from ChromaDB" }

        val request = ChromaDBDeleteRequest(ids = ids)

        return getCollectionUrlPath("delete")
            .flatMap { url ->
                webClient.post()
                    .uri(url)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus({ it.isError }, { response ->
                        response.bodyToMono(String::class.java).flatMap { body ->
                            logger.error { "ChromaDB delete error: ${response.statusCode()} - $body" }
                            Mono.error(
                                ChromaDBException(
                                    "ChromaDB delete error: ${response.statusCode()}",
                                    response.statusCode().value(),
                                    body
                                )
                            )
                        }
                    })
                    .bodyToMono(Void::class.java)
                    .then(Mono.just(Unit))
                    .timeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
                    .doOnSuccess {
                        logger.debug { "Successfully deleted ${ids.size} documents from ChromaDB" }
                    }
                    .doOnError { error ->
                        logger.error(error) { "Error deleting documents from ChromaDB" }
                    }
            }
    }

    fun deleteByMetadata(filter: Map<String, Any>): Mono<Unit> {
        logger.debug { "Deleting documents from ChromaDB by metadata filter: $filter" }

        val request = ChromaDBDeleteRequest(where = filter)

        return getCollectionUrlPath("delete")
            .flatMap { url ->
                webClient.post()
                    .uri(url)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus({ it.isError }, { response ->
                        response.bodyToMono(String::class.java).flatMap { body ->
                            logger.error { "ChromaDB delete by metadata error: ${response.statusCode()} - $body" }
                            Mono.error(
                                ChromaDBException(
                                    "ChromaDB delete by metadata error: ${response.statusCode()}",
                                    response.statusCode().value(),
                                    body
                                )
                            )
                        }
                    })
                    .bodyToMono(Void::class.java)
                    .then(Mono.just(Unit))
                    .timeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
                    .doOnSuccess {
                        logger.debug { "Successfully deleted documents by metadata filter" }
                    }
                    .doOnError { error ->
                        logger.error(error) { "Error deleting documents by metadata" }
                    }
            }
    }

    fun count(): Mono<Int> {
        logger.debug { "Counting documents in ChromaDB collection '${Environment.CHROMADB_COLLECTION_NAME}'" }

        return getCollectionUrlPath("count")
            .flatMap { url ->
                webClient.post()
                    .uri(url)
                    .retrieve()
                    .onStatus({ it.isError }, { response ->
                        response.bodyToMono(String::class.java).flatMap { body ->
                            logger.error { "ChromaDB count error: ${response.statusCode()} - $body" }
                            Mono.error(
                                ChromaDBException(
                                    "ChromaDB count error: ${response.statusCode()}",
                                    response.statusCode().value(),
                                    body
                                )
                            )
                        }
                    })
                    .bodyToMono(ChromaDBCountResponse::class.java)
                    .map { response ->
                        logger.debug { "Collection count: ${response.count}" }
                        response.count
                    }
                    .timeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
                    .doOnError { error ->
                        logger.error(error) { "Error counting documents" }
                    }
                    .onErrorReturn(0) // Return 0 on error
            }
    }

    fun getCollectionInfo(): Mono<ChromaDBCollection> {
        logger.debug { "Getting collection info for '${Environment.CHROMADB_COLLECTION_NAME}'" }

        return getCollectionUrlPath()
            .flatMap { url ->
                webClient.get()
                    .uri(url)
                    .retrieve()
                    .onStatus({ it.isError }, { response ->
                        response.bodyToMono(String::class.java).flatMap { body ->
                            logger.error { "ChromaDB collection info error: ${response.statusCode()} - $body" }
                            Mono.error(
                                ChromaDBException(
                                    "ChromaDB collection info error: ${response.statusCode()}",
                                    response.statusCode().value(),
                                    body
                                )
                            )
                        }
                    })
                    .bodyToMono(ChromaDBCollectionResponse::class.java)
                    .flatMap { response ->
                        // Get the count separately
                        count().map { count ->
                            ChromaDBCollection(
                                name = response.name,
                                count = count
                            )
                        }
                    }
                    .timeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
                    .doOnSuccess { collection ->
                        logger.debug { "Retrieved collection info: name=${collection.name}, count=${collection.count}" }
                    }
                    .doOnError { error ->
                        logger.error(error) { "Error getting collection info" }
                    }
            }
    }

    fun createCollectionIfNotExists(): Mono<Unit> {
        logger.debug { "Creating collection '${Environment.CHROMADB_COLLECTION_NAME}' if it doesn't exist" }

        val createRequest = mapOf(
            "name" to Environment.CHROMADB_COLLECTION_NAME,
            "get_or_create" to true
        )

        return webClient.post()
            .uri("/api/v2/tenants/${Environment.CHROMADB_TENANT}/databases/${Environment.CHROMADB_DATABASE}/collections")
            .bodyValue(createRequest)
            .retrieve()
            .onStatus({ it.isError }, { response ->
                response.bodyToMono(String::class.java).flatMap { body ->
                    logger.error { "ChromaDB collection creation error: ${response.statusCode()} - $body" }
                    Mono.error(
                        ChromaDBException(
                            "ChromaDB collection creation error: ${response.statusCode()}",
                            response.statusCode().value(),
                            body
                        )
                    )
                }
            })
            .bodyToMono(Void::class.java)
            .then(Mono.just(Unit))
            .then(
                // Clear the cache after successful creation to ensure fresh UUID lookup
                mono { collectionManager.clearCache() }.then(Mono.just(Unit))
            )
            .timeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
            .doOnSuccess {
                logger.info { "Successfully created/verified collection: ${Environment.CHROMADB_COLLECTION_NAME}" }
            }
            .doOnError { error ->
                logger.error(error) { "Error creating collection" }
            }
    }

    class ChromaDBException(
        message: String,
        val statusCode: Int,
        val responseBody: String
    ) : RuntimeException(message)
}
