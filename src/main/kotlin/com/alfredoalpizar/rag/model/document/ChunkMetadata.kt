package com.alfredoalpizar.rag.model.document

import java.time.Instant

/**
 * Metadata for a document chunk.
 *
 * Includes both document-level metadata (inherited from parent document)
 * and chunk-specific metadata (chunk type, heading, etc.).
 */
data class ChunkMetadata(
    // Document-level metadata (inherited)
    val docId: String,
    val docTitle: String,
    val category: String,
    val status: String,
    val tags: List<String> = emptyList(),
    val lastUpdated: String? = null,

    // Chunk-specific metadata
    val chunkType: String,              // e.g., "workflow_step", "faq_qa", "reference_section"
    val chunkHeading: String? = null,   // e.g., "Step 1: Validate Email"
    val stepNumber: Int? = null,        // For workflow steps
    val question: String? = null,       // For FAQ chunks

    // Embedding metadata (added during ingestion)
    val embeddingProvider: String? = null,
    val embeddingModel: String? = null,
    val embeddingDimensions: Int? = null,
    val ingestionTimestamp: String? = Instant.now().toString()
) {
    /**
     * Convert to flat map for ChromaDB storage.
     * Arrays are flattened to comma-separated strings.
     */
    fun toFlatMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "doc_id" to docId,
            "doc_title" to docTitle,
            "category" to category,
            "status" to status,
            "chunk_type" to chunkType
        )

        // Add optional document-level fields
        if (tags.isNotEmpty()) map["tags"] = tags.joinToString(",")
        if (lastUpdated != null) map["last_updated"] = lastUpdated

        // Add optional chunk-specific fields
        if (chunkHeading != null) map["chunk_heading"] = chunkHeading
        if (stepNumber != null) map["step_number"] = stepNumber
        if (question != null) map["question"] = question

        // Add embedding metadata
        if (embeddingProvider != null) map["embedding_provider"] = embeddingProvider
        if (embeddingModel != null) map["embedding_model"] = embeddingModel
        if (embeddingDimensions != null) map["embedding_dimensions"] = embeddingDimensions
        if (ingestionTimestamp != null) map["ingestion_timestamp"] = ingestionTimestamp

        return map
    }

    companion object {
        // Chunk types
        const val TYPE_WORKFLOW_OVERVIEW = "workflow_overview"
        const val TYPE_WORKFLOW_STEP = "workflow_step"
        const val TYPE_FAQ_QA = "faq_qa"
        const val TYPE_REFERENCE_SECTION = "reference_section"
        const val TYPE_TROUBLESHOOTING_ISSUE = "troubleshooting_issue"
        const val TYPE_SEMANTIC_CHUNK = "semantic_chunk"

        /**
         * Convert from flat map (from ChromaDB metadata) back to ChunkMetadata.
         * Reverses the operation of toFlatMap().
         */
        fun fromFlatMap(map: Map<String, Any>): ChunkMetadata {
            return ChunkMetadata(
                docId = map["doc_id"] as? String ?: "",
                docTitle = map["doc_title"] as? String ?: "",
                category = map["category"] as? String ?: "",
                status = map["status"] as? String ?: "",
                tags = (map["tags"] as? String)?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                lastUpdated = map["last_updated"] as? String,
                chunkType = map["chunk_type"] as? String ?: TYPE_SEMANTIC_CHUNK,
                chunkHeading = map["chunk_heading"] as? String,
                stepNumber = (map["step_number"] as? Number)?.toInt(),
                question = map["question"] as? String,
                embeddingProvider = map["embedding_provider"] as? String,
                embeddingModel = map["embedding_model"] as? String,
                embeddingDimensions = (map["embedding_dimensions"] as? Number)?.toInt(),
                ingestionTimestamp = map["ingestion_timestamp"] as? String
            )
        }
    }
}
