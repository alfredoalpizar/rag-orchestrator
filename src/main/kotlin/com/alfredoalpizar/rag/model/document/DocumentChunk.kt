package com.alfredoalpizar.rag.model.document

/**
 * A chunk of a document with its text content and metadata.
 *
 * Chunks are created by chunking strategies based on document category
 * and will be embedded and stored in ChromaDB.
 */
data class DocumentChunk(
    val id: String,                    // Format: {doc_id}::{chunk_identifier}
    val text: String,                  // The actual text content to be embedded
    val metadata: ChunkMetadata,       // Metadata about this chunk
    val embedding: List<Float>? = null // Embedding vector (added during ingestion)
) {
    /**
     * Create a copy with embedding added.
     */
    fun withEmbedding(embedding: List<Float>): DocumentChunk {
        return copy(embedding = embedding)
    }

    /**
     * Create a copy with enriched metadata (e.g., embedding model info).
     */
    fun withEnrichedMetadata(
        embeddingProvider: String,
        embeddingModel: String,
        embeddingDimensions: Int
    ): DocumentChunk {
        return copy(
            metadata = metadata.copy(
                embeddingProvider = embeddingProvider,
                embeddingModel = embeddingModel,
                embeddingDimensions = embeddingDimensions
            )
        )
    }
}
