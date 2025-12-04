package com.alfredoalpizar.rag.service.ingestion.chunker

import com.alfredoalpizar.rag.model.document.ChunkMetadata
import com.alfredoalpizar.rag.model.document.Document
import com.alfredoalpizar.rag.model.document.DocumentChunk
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Simplified chunking service using generic heading-based splitting.
 *
 * Works for ALL document categories with one simple approach:
 * - Split on ## headings (keeps the heading with its content)
 * - Falls back to single chunk if no headings found
 *
 * No need for separate WorkflowChunker, FAQChunker, ReferenceChunker, TroubleshootingChunker.
 */
@Service
class ChunkingService {
    private val logger = KotlinLogging.logger {}

    // Regex to split on ## headings (lookahead keeps the heading with its content)
    private val headingPattern = Regex("""(?=^##\s)""", RegexOption.MULTILINE)

    // Regex to extract heading text from a section
    private val headingExtractPattern = Regex("""^##\s+(.+)$""", RegexOption.MULTILINE)

    /**
     * Chunk a document by splitting on ## headings.
     * Works for any category - no category-specific logic needed.
     *
     * @param document Document to chunk
     * @return List of document chunks
     */
    fun chunk(document: Document): List<DocumentChunk> {
        val docId = document.metadata.id
        val body = document.content.body

        logger.debug { "Chunking document $docId with generic heading-based chunker" }

        // Split on ## headings
        val sections = body.split(headingPattern)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (sections.isEmpty()) {
            logger.debug { "No sections found in document $docId, creating single chunk" }
            return listOf(createChunk(document, 0, "All Content", buildFullText(document)))
        }

        val chunks = sections.mapIndexed { index, section ->
            val heading = extractHeading(section) ?: "Section ${index + 1}"
            createChunk(document, index, heading, section)
        }

        logger.debug { "Chunked document $docId into ${chunks.size} sections" }
        return chunks
    }

    /**
     * Extract the heading text from a section (first ## line).
     */
    private fun extractHeading(section: String): String? {
        val match = headingExtractPattern.find(section)
        return match?.groupValues?.get(1)?.trim()
    }

    /**
     * Build full document text for fallback single-chunk case.
     */
    private fun buildFullText(document: Document): String {
        return buildString {
            append(document.metadata.title)
            document.content.summary?.let {
                append("\n\n")
                append(it)
            }
            append("\n\n")
            append(document.content.body)
        }
    }

    /**
     * Create a DocumentChunk with proper metadata.
     */
    private fun createChunk(
        document: Document,
        index: Int,
        heading: String,
        text: String
    ): DocumentChunk {
        return DocumentChunk(
            id = "${document.metadata.id}::section_$index",
            text = text,
            metadata = ChunkMetadata(
                docId = document.metadata.id,
                docTitle = document.metadata.title,
                category = document.metadata.category,
                status = document.metadata.status,
                tags = document.metadata.tags ?: emptyList(),
                lastUpdated = document.metadata.lastUpdated?.toString(),
                chunkType = ChunkMetadata.TYPE_SEMANTIC_CHUNK,
                chunkHeading = heading
            )
        )
    }
}

/**
 * Exception thrown when chunking fails.
 */
class ChunkingException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
