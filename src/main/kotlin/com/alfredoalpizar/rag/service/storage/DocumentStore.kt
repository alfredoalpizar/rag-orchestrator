package com.alfredoalpizar.rag.service.storage

import com.alfredoalpizar.rag.model.document.Document

/**
 * Abstraction for document storage (local filesystem or S3).
 *
 * Provides CRUD operations for knowledge base documents, decoupling
 * business logic from storage implementation details.
 *
 * Implementations:
 * - LocalDocumentStore: Stores documents as YAML files in local directory
 * - S3DocumentStore: Stores documents in S3 bucket (future)
 *
 * Path Convention:
 * - Documents are stored at: {category}/{id}.yml
 * - Example: faq/password_reset.yml
 * - Category is extracted from Document.metadata.category
 * - ID is extracted from Document.metadata.id
 */
interface DocumentStore {

    /**
     * List all documents in storage.
     *
     * @param recursive Whether to search recursively in subdirectories/prefixes
     * @return List of document references with metadata
     */
    suspend fun list(recursive: Boolean = true): List<DocumentRef>

    /**
     * Get document reference by ID.
     *
     * Searches across all categories to find the document with given ID.
     *
     * @param docId Document ID from metadata.id
     * @return Document reference or null if not found
     */
    suspend fun getById(docId: String): DocumentRef?

    /**
     * Read document content as raw bytes.
     *
     * Use DocumentSerializer to deserialize bytes into Document object.
     *
     * @param ref Document reference
     * @return Raw document bytes (YAML or JSON format)
     * @throws DocumentNotFoundException if document doesn't exist
     */
    suspend fun read(ref: DocumentRef): ByteArray

    /**
     * Create a new document.
     *
     * Stores the document at: {category}/{id}.yml
     *
     * @param document Document to create
     * @return Document reference with storage metadata (path, lastModified, etc.)
     * @throws DocumentAlreadyExistsException if document ID already exists
     */
    suspend fun create(document: Document): DocumentRef

    /**
     * Update an existing document.
     *
     * If the category changed, the file will be moved to the new category directory.
     *
     * @param docId Document ID to update
     * @param document Updated document (must have same ID)
     * @return Updated document reference with new lastModified timestamp
     * @throws DocumentNotFoundException if document not found
     */
    suspend fun update(docId: String, document: Document): DocumentRef

    /**
     * Delete a document.
     *
     * @param docId Document ID to delete
     * @throws DocumentNotFoundException if document not found
     */
    suspend fun delete(docId: String)

    /**
     * Check if document exists.
     *
     * @param docId Document ID
     * @return True if document exists in storage
     */
    suspend fun exists(docId: String): Boolean
}

/**
 * Exception thrown when attempting to read, update, or delete a document that doesn't exist.
 */
class DocumentNotFoundException(message: String) : RuntimeException(message)

/**
 * Exception thrown when attempting to create a document with an ID that already exists.
 */
class DocumentAlreadyExistsException(message: String) : RuntimeException(message)
