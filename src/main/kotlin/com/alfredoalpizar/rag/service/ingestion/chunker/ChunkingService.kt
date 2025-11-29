package com.alfredoalpizar.rag.service.ingestion.chunker

import com.alfredoalpizar.rag.model.document.Document
import com.alfredoalpizar.rag.model.document.DocumentChunk
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Service that routes documents to appropriate chunking strategies
 * based on their category.
 *
 * Supported categories:
 * - workflow: WorkflowChunker (chunk by steps)
 * - faq: FAQChunker (chunk by Q&A pairs)
 * - reference: ReferenceChunker (chunk by sections)
 * - troubleshooting: TroubleshootingChunker (chunk by issues)
 *
 * If category is not recognized or chunking fails, falls back to
 * creating a single chunk with all content.
 */
@Service
class ChunkingService(
    private val workflowChunker: WorkflowChunker,
    private val faqChunker: FAQChunker,
    private val referenceChunker: ReferenceChunker,
    private val troubleshootingChunker: TroubleshootingChunker
) {
    private val logger = KotlinLogging.logger {}

    // Map of category to chunker
    private val chunkers: Map<String, Chunker> by lazy {
        mapOf(
            "workflow" to workflowChunker,
            "faq" to faqChunker,
            "reference" to referenceChunker,
            "troubleshooting" to troubleshootingChunker
        )
    }

    /**
     * Chunk a document using the appropriate strategy for its category.
     *
     * @param document Document to chunk
     * @return List of document chunks
     * @throws ChunkingException if chunking fails
     */
    fun chunk(document: Document): List<DocumentChunk> {
        val category = document.metadata.category.lowercase()

        logger.debug { "Chunking document ${document.metadata.id} with category: $category" }

        val chunker = chunkers[category]

        if (chunker == null) {
            logger.warn { "No chunker found for category: $category, using fallback" }
            return createFallbackChunk(document)
        }

        return try {
            val chunks = chunker.chunk(document)
            if (chunks.isEmpty()) {
                logger.warn { "Chunker produced no chunks for document ${document.metadata.id}, using fallback" }
                createFallbackChunk(document)
            } else {
                chunks
            }
        } catch (e: Exception) {
            logger.error(e) { "Error chunking document ${document.metadata.id}, using fallback" }
            createFallbackChunk(document)
        }
    }

    /**
     * Create a fallback chunk when category-specific chunking fails or is unavailable.
     * Returns a single chunk with all content.
     */
    private fun createFallbackChunk(document: Document): List<DocumentChunk> {
        val text = buildString {
            append(document.metadata.title)
            append("\n\n")
            if (document.content.summary != null) {
                append(document.content.summary)
                append("\n\n")
            }
            append(document.content.body)
        }

        return listOf(
            DocumentChunk(
                id = "${document.metadata.id}::all",
                text = text.trim(),
                metadata = com.alfredoalpizar.rag.model.document.ChunkMetadata(
                    docId = document.metadata.id,
                    docTitle = document.metadata.title,
                    category = document.metadata.category,
                    status = document.metadata.status,
                    tags = document.metadata.tags ?: emptyList(),
                    lastUpdated = document.metadata.lastUpdated?.toString(),
                    chunkType = com.alfredoalpizar.rag.model.document.ChunkMetadata.TYPE_SEMANTIC_CHUNK,
                    chunkHeading = "All Content"
                )
            )
        )
    }

    /**
     * Get available chunking categories.
     */
    fun getAvailableCategories(): Set<String> {
        return chunkers.keys
    }
}

/**
 * Exception thrown when chunking fails.
 */
class ChunkingException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
