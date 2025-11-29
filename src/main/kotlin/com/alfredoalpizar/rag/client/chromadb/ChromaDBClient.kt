package com.alfredoalpizar.rag.client.chromadb

import reactor.core.publisher.Mono

interface ChromaDBClient {
    /**
     * Query collection using text (ChromaDB auto-generates embeddings)
     */
    fun query(text: String, nResults: Int = 5): Mono<List<ChromaDBResult>>

    /**
     * Query collection using pre-computed embeddings
     */
    fun queryWithEmbedding(
        embedding: List<Float>,
        nResults: Int = 5,
        where: Map<String, Any>? = null
    ): Mono<List<ChromaDBResult>>

    /**
     * Add documents (ChromaDB auto-generates embeddings)
     */
    fun add(documents: List<ChromaDBDocument>): Mono<Unit>

    /**
     * Upsert documents with pre-computed embeddings
     */
    fun upsert(
        ids: List<String>,
        embeddings: List<List<Float>>,
        documents: List<String>,
        metadatas: List<Map<String, Any>>
    ): Mono<Unit>

    /**
     * Delete documents by IDs
     */
    fun delete(ids: List<String>): Mono<Unit>

    /**
     * Delete documents by metadata filter
     */
    fun deleteByMetadata(filter: Map<String, Any>): Mono<Unit>

    /**
     * Count documents in collection
     */
    fun count(): Mono<Int>

    /**
     * Get collection information
     */
    fun getCollectionInfo(): Mono<ChromaDBCollection>

    /**
     * Create collection if it doesn't exist
     */
    fun createCollectionIfNotExists(): Mono<Unit>
}
