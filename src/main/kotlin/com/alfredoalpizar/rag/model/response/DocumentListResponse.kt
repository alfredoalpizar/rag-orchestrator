package com.alfredoalpizar.rag.model.response

import com.alfredoalpizar.rag.service.storage.DocumentRef
import java.time.Instant

/**
 * Response for listing documents.
 *
 * Used by GET /api/v1/documents endpoint.
 * Returns summary information for all documents without full content.
 */
data class DocumentListResponse(
    val total: Int,
    val documents: List<DocumentSummary>
) {
    companion object {
        fun from(refs: List<DocumentRef>) = DocumentListResponse(
            total = refs.size,
            documents = refs.map { DocumentSummary.from(it) }
        )
    }
}

/**
 * Summary information for a single document in the list.
 * Contains metadata only, not full content.
 */
data class DocumentSummary(
    val id: String,
    val name: String,
    val category: String,
    val path: String,
    val lastModified: Instant,
    val lastModifiedEpoch: Long,  // ETag value for UI to use in updates
    val sizeBytes: Long
) {
    companion object {
        fun from(ref: DocumentRef) = DocumentSummary(
            id = ref.docId,
            name = ref.name,
            category = ref.category,
            path = ref.path,
            lastModified = ref.lastModified,
            lastModifiedEpoch = ref.lastModified.toEpochMilli(),
            sizeBytes = ref.sizeBytes
        )
    }
}
