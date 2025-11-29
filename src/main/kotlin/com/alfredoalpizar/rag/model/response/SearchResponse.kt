package com.alfredoalpizar.rag.model.response

import com.alfredoalpizar.rag.client.chromadb.ChromaDBResult
import com.alfredoalpizar.rag.model.document.ChunkMetadata
import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.math.max

/**
 * Response containing search results from vector similarity search.
 */
data class SearchResponse(
    val results: List<SearchResult>,
    val total: Int,
    @JsonProperty("processing_time_ms")
    val processingTimeMs: Long,
    val query: String
) {
    companion object {
        fun from(results: List<SearchResult>, query: String, processingTimeMs: Long): SearchResponse {
            return SearchResponse(
                results = results,
                total = results.size,
                processingTimeMs = processingTimeMs,
                query = query
            )
        }
    }
}

/**
 * Individual search result combining chunk content with document context.
 */
data class SearchResult(
    @JsonProperty("chunk_id")
    val chunkId: String,
    @JsonProperty("chunk_text")
    val chunkText: String,
    val score: Float,                    // Similarity score (0.0 = no similarity, 1.0 = perfect match)
    val document: SearchResultDocument,  // Document-level information
    val chunk: SearchResultChunk        // Chunk-specific information
) {
    companion object {
        fun from(chromaResult: ChromaDBResult): SearchResult {
            // Convert distance to similarity score (lower distance = higher similarity)
            // ChromaDB typically returns distances in range 0.0-2.0, so we normalize to 0.0-1.0
            val score = max(0f, 1f - (chromaResult.distance / 2f))

            // Extract chunk metadata from ChromaDB metadata
            val chunkMetadata = ChunkMetadata.fromFlatMap(chromaResult.metadata)

            return SearchResult(
                chunkId = chromaResult.id,
                chunkText = chromaResult.document,
                score = score,
                document = SearchResultDocument.from(chunkMetadata),
                chunk = SearchResultChunk.from(chunkMetadata)
            )
        }
    }
}

/**
 * Document-level information for search results.
 */
data class SearchResultDocument(
    @JsonProperty("doc_id")
    val docId: String,
    val title: String,
    val category: String,
    val status: String,
    val tags: List<String>
) {
    companion object {
        fun from(metadata: ChunkMetadata): SearchResultDocument {
            return SearchResultDocument(
                docId = metadata.docId,
                title = metadata.docTitle,
                category = metadata.category,
                status = metadata.status,
                tags = metadata.tags
            )
        }
    }
}

/**
 * Chunk-specific information for search results.
 */
data class SearchResultChunk(
    @JsonProperty("chunk_type")
    val chunkType: String,
    @JsonProperty("chunk_heading")
    val chunkHeading: String?,
    @JsonProperty("step_number")
    val stepNumber: Int?,
    val question: String?
) {
    companion object {
        fun from(metadata: ChunkMetadata): SearchResultChunk {
            return SearchResultChunk(
                chunkType = metadata.chunkType,
                chunkHeading = metadata.chunkHeading,
                stepNumber = metadata.stepNumber,
                question = metadata.question
            )
        }
    }
}