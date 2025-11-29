package com.alfredoalpizar.rag.service.ingestion.chunker

import com.alfredoalpizar.rag.model.document.Document
import com.alfredoalpizar.rag.model.document.DocumentChunk

/**
 * Interface for document chunking strategies.
 *
 * Different document types require different chunking approaches:
 * - Workflows: chunk by steps
 * - FAQs: chunk by Q&A pairs
 * - Reference: chunk by sections
 * - Troubleshooting: chunk by issues
 */
interface Chunker {
    /**
     * Chunk a document into smaller pieces for embedding.
     *
     * @param document Document to chunk
     * @return List of document chunks with metadata
     */
    fun chunk(document: Document): List<DocumentChunk>

    /**
     * Get the category this chunker is designed for.
     *
     * @return Category name (e.g., "workflow", "faq")
     */
    fun getCategory(): String
}
