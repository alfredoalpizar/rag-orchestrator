package com.alfredoalpizar.rag.controller

import com.alfredoalpizar.rag.client.chromadb.ChromaDBClient
import com.alfredoalpizar.rag.model.response.SearchResponse
import com.alfredoalpizar.rag.model.response.SearchResult
import com.alfredoalpizar.rag.service.ingestion.embedding.EmbeddingService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import kotlin.system.measureTimeMillis

/**
 * REST API for semantic search using ChromaDB vector similarity.
 *
 * Provides semantic search capabilities over ingested documents by leveraging
 * ChromaDB's vector similarity search. Automatically generates embeddings for
 * search queries and returns ranked results with document context.
 *
 * Features:
 * - Semantic search using vector embeddings
 * - Rich result metadata (document + chunk information)
 * - Configurable result limits
 * - Performance metrics tracking
 * - Comprehensive error handling
 *
 * Base URL: /api/v1/search
 */
@RestController
@RequestMapping("/api/v1/search")
class SearchController(
    private val chromaDBClient: ChromaDBClient,
    private val embeddingService: EmbeddingService
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Perform semantic search across all ingested documents.
     *
     * GET /api/v1/search?q={query}&limit={limit}
     *
     * Uses ChromaDB's vector similarity search to find semantically similar content
     * to the provided query. The query is automatically embedded and compared against
     * all ingested document chunks using cosine similarity.
     *
     * @param query Search query text (required)
     * @param limit Maximum number of results to return (default: 10, max: 50)
     * @return Ranked search results with similarity scores and document context
     * @throws ResponseStatusException 400 if query is blank
     * @throws ResponseStatusException 500 if ChromaDB search fails
     */
    @GetMapping
    fun searchDocuments(
        @RequestParam("q") query: String,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<SearchResponse> = runBlocking {

        // Validate parameters
        if (query.isBlank()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Search query cannot be empty"
            )
        }

        val clampedLimit = when {
            limit <= 0 -> 10
            limit > 50 -> 50
            else -> limit
        }

        logger.info { "Performing semantic search: query='$query', limit=$clampedLimit" }

        try {
            val searchResults: List<SearchResult>
            val processingTimeMs = measureTimeMillis {
                // Ensure ChromaDB collection exists before querying
                chromaDBClient.createCollectionIfNotExists().awaitSingleOrNull()

                // Generate embedding for search query
                val queryEmbedding = embeddingService.embedSingle(query)
                logger.debug { "Generated query embedding (${queryEmbedding.size} dimensions)" }

                // Perform semantic search using pre-computed embedding
                val chromaResults = chromaDBClient.queryWithEmbedding(
                    embedding = queryEmbedding,
                    nResults = clampedLimit
                ).awaitSingleOrNull() ?: emptyList()

                // Transform ChromaDB results to our search result format
                searchResults = chromaResults.map { chromaResult ->
                    SearchResult.from(chromaResult)
                }

                logger.debug { "ChromaDB returned ${chromaResults.size} results for query: $query" }
            }

            val response = SearchResponse.from(searchResults, query, processingTimeMs)

            logger.info {
                "Search completed: query='$query', results=${response.total}, " +
                "processingTime=${response.processingTimeMs}ms"
            }

            return@runBlocking ResponseEntity.ok(response)

        } catch (e: Exception) {
            logger.error(e) { "Search failed for query: $query" }
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Search operation failed: ${e.message}"
            )
        }
    }

    /**
     * Get search statistics and health information.
     *
     * GET /api/v1/search/_status
     *
     * Returns information about the search system status including
     * ChromaDB collection information and document counts.
     *
     * @return Search system status information
     */
    @GetMapping("/_status")
    fun getSearchStatus(): ResponseEntity<Map<String, Any>> = runBlocking {
        logger.info { "Fetching search status" }

        try {
            val collectionInfo = chromaDBClient.getCollectionInfo().awaitSingle()
            val documentCount = chromaDBClient.count().awaitSingle()

            val status: Map<String, Any> = mapOf(
                "status" to "UP",
                "collection_name" to collectionInfo.name,
                "total_chunks" to documentCount,
                "timestamp" to System.currentTimeMillis()
            )

            logger.debug { "Search status: $status" }
            return@runBlocking ResponseEntity.ok(status)

        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch search status" }
            val errorStatus: Map<String, Any> = mapOf(
                "status" to "DOWN",
                "error" to (e.message ?: "Unknown error"),
                "timestamp" to System.currentTimeMillis()
            )
            return@runBlocking ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorStatus)
        }
    }
}