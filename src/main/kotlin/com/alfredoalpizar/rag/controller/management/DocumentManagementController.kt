package com.alfredoalpizar.rag.controller.management

import com.alfredoalpizar.rag.model.document.Document
import com.alfredoalpizar.rag.model.response.DocumentListResponse
import com.alfredoalpizar.rag.model.response.DocumentResponse
import com.alfredoalpizar.rag.service.ingestion.IngestionService
import com.alfredoalpizar.rag.service.lock.ReindexQueueManager
import com.alfredoalpizar.rag.service.storage.DocumentAlreadyExistsException
import com.alfredoalpizar.rag.service.storage.DocumentNotFoundException
import com.alfredoalpizar.rag.service.storage.DocumentSerializer
import com.alfredoalpizar.rag.service.storage.DocumentStore
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/**
 * REST API for knowledge base document management (CRUD operations).
 *
 * Provides a complete document management interface for the UI:
 * - List all documents with filtering
 * - Get individual document with full content
 * - Create new documents with automatic ingestion
 * - Update documents with optimistic locking (ETag)
 * - Delete documents (removes both file and vectors)
 *
 * Async Reindexing Architecture:
 * - Document storage (YAML) updates immediately (fast HTTP response ~50ms)
 * - Vector reindexing happens asynchronously in background queue (~5-10s delay)
 * - Single-threaded queue eliminates ALL race conditions (no concurrent update conflicts)
 * - Queries may return slightly stale results during reindex window (acceptable for POC)
 *
 * Race Condition Prevention:
 * - Single-threaded executor serializes all reindex operations
 * - Prevents concurrent edit conflicts (Scenario 1: both users' changes preserved)
 * - Minimizes read-write inconsistency (Scenario 2: old vectors exist until new ready)
 * - No distributed locks needed (simple, reliable for POC scale)
 *
 * Optimistic Locking (Recommended):
 * - GET returns ETag header (lastModified timestamp in milliseconds)
 * - PUT accepts If-Match header with ETag (OPTIONAL but RECOMMENDED)
 * - If ETag provided and doesn't match, returns 409 Conflict
 * - If ETag omitted, update proceeds with last-write-wins (warning logged)
 *
 * Version Storage:
 * - Version = file modification timestamp (from filesystem metadata)
 * - NOT stored in Document YAML (keeps YAML clean)
 * - Works for both LOCAL and S3 storage (S3 has LastModified metadata)
 *
 * Scaling Path:
 * - POC: Use current single-threaded queue (good for <100 docs/hour)
 * - Multi-instance production: Migrate to DynamoDB distributed locks
 *   (see docs/PHASE3_DYNAMODB_DISTRIBUTED_LOCKS.md)
 *
 * Base URL: /api/v1/documents
 */
@RestController
@RequestMapping("/api/v1/documents")
class DocumentManagementController(
    private val documentStore: DocumentStore,
    private val documentSerializer: DocumentSerializer,
    private val ingestionService: IngestionService,
    private val reindexQueue: ReindexQueueManager
) {
    private val logger = KotlinLogging.logger {}

    /**
     * List all documents.
     *
     * GET /api/v1/documents?recursive=true
     *
     * Returns summary information for all documents (id, category, path, size, lastModified).
     * Does not include full document content for performance.
     *
     * @param recursive Whether to search recursively in subdirectories (default: true)
     * @return List of document summaries
     */
    @GetMapping
    fun listDocuments(
        @RequestParam(defaultValue = "true") recursive: Boolean
    ): ResponseEntity<DocumentListResponse> = runBlocking {
        logger.info("Listing documents (recursive=$recursive)")

        val refs = documentStore.list(recursive)
        val response = DocumentListResponse.from(refs)

        logger.debug("Found ${response.total} documents")
        return@runBlocking ResponseEntity.ok(response)
    }

    /**
     * Get a single document by ID.
     *
     * GET /api/v1/documents/{docId}
     *
     * Returns full document content with storage metadata.
     * Includes ETag header for optimistic locking (use in subsequent PUT requests).
     *
     * @param docId Document ID
     * @return Document with full content and storage metadata
     * @throws ResponseStatusException 404 if document not found
     */
    @GetMapping("/{docId}")
    fun getDocument(
        @PathVariable docId: String
    ): ResponseEntity<DocumentResponse> = runBlocking {
        logger.info("Fetching document: $docId")

        val ref = documentStore.getById(docId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: $docId")

        val bytes = documentStore.read(ref)
        val document = documentSerializer.deserialize(bytes)

        val response = DocumentResponse.from(document, ref)

        // Return ETag header for optimistic locking
        return@runBlocking ResponseEntity.ok()
            .eTag("\"${ref.lastModified.toEpochMilli()}\"")
            .body(response)
    }

    /**
     * Create a new document.
     *
     * POST /api/v1/documents
     * Body: Document object (JSON)
     *
     * Creates a new document in storage and automatically ingests it to ChromaDB
     * if the document status is "published".
     *
     * Storage location: {category}/{id}.yml
     *
     * @param document Document to create
     * @return Created document with storage metadata
     * @throws ResponseStatusException 400 if validation fails
     * @throws ResponseStatusException 409 if document ID already exists
     */
    @PostMapping
    fun createDocument(
        @RequestBody document: Document
    ): ResponseEntity<DocumentResponse> = runBlocking {
        logger.info("Creating document: ${document.metadata.id}")

        try {
            // Validate document structure
            val validation = document.validate()
            if (!validation.isValid()) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Validation failed: ${validation.errorsOrEmpty().joinToString()}"
                )
            }

            // Store document as YAML file
            val ref = documentStore.create(document)
            logger.debug("Document stored at: ${ref.path}")

            // Queue async reindexing (only if published)
            if (document.shouldIndex()) {
                reindexQueue.queueReindex(document.metadata.id, document, operationType = "create")
                logger.info("Document created and queued for indexing: ${document.metadata.id}")
            } else {
                logger.info("Document created but not queued for indexing (status: ${document.metadata.status}): ${document.metadata.id}")
            }

            val response = DocumentResponse.from(document, ref)
            return@runBlocking ResponseEntity.status(HttpStatus.CREATED).body(response)

        } catch (e: DocumentAlreadyExistsException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
        }
    }

    /**
     * Update an existing document.
     *
     * PUT /api/v1/documents/{docId}
     * Headers: If-Match: "{lastModifiedEpoch}" (optional but recommended)
     * Body: Updated Document object (JSON)
     *
     * Updates both the document file and re-ingests to ChromaDB.
     * Supports optimistic locking via If-Match header.
     *
     * Process:
     * 1. Validate ETag (if provided)
     * 2. Delete old vectors from ChromaDB
     * 3. Update document file in storage
     * 4. Re-chunk and re-embed document
     * 5. Upsert new vectors to ChromaDB
     *
     * @param docId Document ID to update
     * @param ifMatch ETag from previous GET (lastModified timestamp)
     * @param document Updated document
     * @return Updated document with new storage metadata
     * @throws ResponseStatusException 400 if validation fails
     * @throws ResponseStatusException 404 if document not found
     * @throws ResponseStatusException 409 if ETag doesn't match (optimistic lock failure)
     */
    @PutMapping("/{docId}")
    fun updateDocument(
        @PathVariable docId: String,
        @RequestHeader(value = "If-Match", required = false) ifMatch: String?,
        @RequestBody document: Document
    ): ResponseEntity<DocumentResponse> = runBlocking {
        logger.info("Updating document: $docId (ETag check: ${ifMatch != null})")

        try {
            // Validate ID consistency
            if (document.metadata.id != docId) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Document ID mismatch: URL has '$docId', body has '${document.metadata.id}'"
                )
            }

            // Validate document structure
            val validation = document.validate()
            if (!validation.isValid()) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Validation failed: ${validation.errorsOrEmpty().joinToString()}"
                )
            }

            // Check optimistic lock (ETag)
            if (ifMatch != null) {
                val current = documentStore.getById(docId)
                    ?: throw DocumentNotFoundException("Document not found: $docId")

                val currentETag = "\"${current.lastModified.toEpochMilli()}\""
                if (currentETag != ifMatch) {
                    logger.warn("ETag mismatch for $docId: expected $currentETag, got $ifMatch")
                    throw ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Document was modified by another user. Current ETag: $currentETag. Please refresh and try again."
                    )
                }
            } else {
                // If-Match not provided - allow update but warn (last-write-wins)
                logger.warn("Update without If-Match header for $docId - no optimistic locking, last-write-wins")
            }

            // Update storage (YAML file) immediately
            val ref = documentStore.update(docId, document)
            logger.debug("Updated document at: ${ref.path}")

            // Queue async reindexing (delete old vectors + re-embed + upsert new vectors)
            // This happens in background, serialized by queue (prevents race conditions)
            if (document.shouldIndex()) {
                reindexQueue.queueReindex(docId, document, operationType = "update")
                logger.info("Document updated and queued for reindexing: $docId")
            } else {
                // Document unpublished - delete vectors immediately
                ingestionService.deleteDocument(docId)
                logger.info("Document updated and vectors removed (status: ${document.metadata.status}): $docId")
            }

            val response = DocumentResponse.from(document, ref)
            return@runBlocking ResponseEntity.ok()
                .eTag("\"${ref.lastModified.toEpochMilli()}\"")
                .body(response)

        } catch (e: DocumentNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
    }

    /**
     * Delete a document.
     *
     * DELETE /api/v1/documents/{docId}
     *
     * Deletes both:
     * 1. Document file from storage
     * 2. All associated vector embeddings from ChromaDB
     *
     * @param docId Document ID to delete
     * @return 204 No Content on success
     * @throws ResponseStatusException 404 if document not found
     */
    @DeleteMapping("/{docId}")
    fun deleteDocument(
        @PathVariable docId: String
    ): ResponseEntity<Void> = runBlocking {
        logger.info("Deleting document: $docId")

        try {
            // Delete vectors from ChromaDB first
            ingestionService.deleteDocument(docId)
            logger.debug("Deleted vectors for document: $docId")

            // Delete file from storage
            documentStore.delete(docId)
            logger.info("Deleted document: $docId")

            return@runBlocking ResponseEntity.noContent().build()

        } catch (e: DocumentNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
    }

    /**
     * Get reindex queue metrics.
     *
     * GET /api/v1/documents/_metrics
     *
     * Returns statistics about the background reindexing queue:
     * - queued_total: Total documents queued for reindexing
     * - completed: Successfully reindexed documents
     * - failed: Failed reindex operations
     * - pending: Currently queued (not yet processed)
     * - failure_rate: Percentage of failed operations
     *
     * @return Queue metrics
     */
    @GetMapping("/_metrics")
    fun getReindexMetrics(): ResponseEntity<Map<String, Any>> {
        val metrics = reindexQueue.getMetrics()
        return ResponseEntity.ok(metrics)
    }
}
