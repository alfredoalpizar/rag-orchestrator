package com.alfredoalpizar.rag.service.tool

import com.alfredoalpizar.rag.client.chromadb.ChromaDBClient
import com.alfredoalpizar.rag.model.domain.ToolResult
import com.alfredoalpizar.rag.service.ingestion.embedding.EmbeddingService
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class RAGTool(
    private val chromaDBClient: ChromaDBClient,
    private val embeddingService: EmbeddingService
) : Tool {

    private val logger = KotlinLogging.logger {}

    override val name = "rag_search"

    override val description = """
        Search the knowledge base for relevant information.

        IMPORTANT GUIDELINES:
        - Only use this if the pre-retrieved context is clearly insufficient
        - Do NOT call this multiple times with similar queries - if previous searches didn't find explicit info, the KB likely doesn't have it
        - If you already have related context that allows you to infer an answer, use finalize_answer instead
    """.trimIndent()

    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf(
                "type" to "string",
                "description" to "The search query to find relevant documents"
            ),
            "max_results" to mapOf(
                "type" to "integer",
                "description" to "Maximum number of results to return (default: 5, max: 10)"
            )
        ),
        "required" to listOf("query")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val query = arguments["query"] as? String
            ?: return ToolResult(
                toolCallId = "",
                toolName = name,
                result = "",
                success = false,
                error = "Missing required parameter: query"
            )

        val maxResults = (arguments["max_results"] as? Number)?.toInt()
            ?.coerceIn(1, 10) ?: 5

        return try {
            logger.debug { "Performing RAG search: query='$query', maxResults=$maxResults" }

            // Generate query embedding using same provider as ingestion
            val queryEmbedding = embeddingService.embedSingle(query)
            logger.debug { "Generated query embedding: ${queryEmbedding.size} dimensions" }

            // Query ChromaDB with embedding
            val results = chromaDBClient.queryWithEmbedding(queryEmbedding, maxResults).awaitSingle()

            if (results.isEmpty()) {
                logger.debug { "No results found for query: $query" }
                return ToolResult(
                    toolCallId = "",
                    toolName = name,
                    result = "No relevant documents found in the knowledge base for query: $query",
                    success = true,
                    metadata = mapOf("resultsCount" to 0)
                )
            }

            val formattedResults = results.mapIndexed { index, result ->
                val relevanceScore = String.format("%.2f", (1 - result.distance) * 100)
                """
                Result ${index + 1}:
                Document: ${result.document}
                Relevance: ${relevanceScore}%
                """.trimIndent()
            }.joinToString("\n\n")

            logger.debug { "RAG search completed: found ${results.size} results" }

            ToolResult(
                toolCallId = "",
                toolName = name,
                result = formattedResults,
                success = true,
                metadata = mapOf(
                    "resultsCount" to results.size,
                    "query" to query
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "RAG search failed for query: $query" }
            ToolResult(
                toolCallId = "",
                toolName = name,
                result = "",
                success = false,
                error = "RAG search failed: ${e.message}"
            )
        }
    }
}
