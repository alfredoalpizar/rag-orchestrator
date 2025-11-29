package com.alfredoalpizar.rag.model.response

import com.alfredoalpizar.rag.model.document.Document
import com.alfredoalpizar.rag.service.storage.DocumentRef
import java.time.Instant

/**
 * Response for a single document with full content and storage metadata.
 *
 * Used by:
 * - GET /api/v1/documents/{docId}
 * - POST /api/v1/documents (create)
 * - PUT /api/v1/documents/{docId} (update)
 */
data class DocumentResponse(
    val document: Document,
    val storage: StorageMetadata
) {
    companion object {
        fun from(document: Document, ref: DocumentRef) = DocumentResponse(
            document = document,
            storage = StorageMetadata(
                path = ref.path,
                lastModified = ref.lastModified,
                lastModifiedEpoch = ref.lastModified.toEpochMilli(),
                sizeBytes = ref.sizeBytes
            )
        )
    }
}

/**
 * Storage metadata for a document.
 * Used for ETag-based optimistic locking and UI display.
 */
data class StorageMetadata(
    val path: String,
    val lastModified: Instant,
    val lastModifiedEpoch: Long,  // ETag value (milliseconds since epoch)
    val sizeBytes: Long
)
