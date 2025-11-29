package com.alfredoalpizar.rag.service.storage

import java.time.Instant

/**
 * Reference to a document in storage with metadata.
 *
 * Used for:
 * - Listing documents without loading full content
 * - Version tracking via lastModified timestamp
 * - ETag-based optimistic locking (uses lastModified as ETag)
 * - Mapping docId to storage location (path)
 *
 * Storage path convention: {category}/{id}.yml
 * Example: faq/password_reset.yml
 */
data class DocumentRef(
    /**
     * Document ID from metadata.id
     * Example: "password_reset_faq"
     */
    val docId: String,

    /**
     * Relative path in storage (e.g., "faq/password_reset.yml")
     * For local storage: relative to base path
     * For S3: key without bucket name
     */
    val path: String,

    /**
     * File name (e.g., "password_reset.yml")
     */
    val name: String,

    /**
     * Document category from metadata.category
     * Example: "faq", "troubleshooting", "workflow"
     */
    val category: String,

    /**
     * Last modification timestamp.
     * Used as ETag for optimistic locking.
     * Clients send this back in If-Match header when updating.
     */
    val lastModified: Instant,

    /**
     * File size in bytes (for UI display)
     */
    val sizeBytes: Long = 0
)
