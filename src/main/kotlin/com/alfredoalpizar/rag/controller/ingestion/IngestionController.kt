package com.alfredoalpizar.rag.controller.ingestion

import com.alfredoalpizar.rag.model.request.DeleteDocumentRequest
import com.alfredoalpizar.rag.model.request.IngestDirectoryRequest
import com.alfredoalpizar.rag.model.request.IngestDocumentRequest
import com.alfredoalpizar.rag.model.request.ReindexRequest
import com.alfredoalpizar.rag.model.response.DeleteDocumentResponse
import com.alfredoalpizar.rag.model.response.IngestDirectoryResponse
import com.alfredoalpizar.rag.model.response.IngestDocumentResponse
import com.alfredoalpizar.rag.model.response.IngestionStatsResponse
import com.alfredoalpizar.rag.service.ingestion.IngestionService
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * REST API controller for document ingestion operations.
 *
 * Endpoints:
 * - POST /api/v1/ingest/document - Ingest a single document
 * - POST /api/v1/ingest/documents - Ingest all documents from directory
 * - POST /api/v1/ingest/reindex - Full re-index
 * - DELETE /api/v1/ingest/document/{docId} - Delete a document
 * - GET /api/v1/ingest/status - Get ingestion statistics
 */
@RestController
@RequestMapping("/api/v1/ingest")
class IngestionController(
    private val ingestionService: IngestionService
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Ingest a single document from a file path.
     */
    @PostMapping("/document")
    fun ingestDocument(
        @RequestBody request: IngestDocumentRequest
    ): ResponseEntity<IngestDocumentResponse> = runBlocking {
        logger.info { "Ingesting document: ${request.filePath}" }

        val result = ingestionService.ingestDocument(request.filePath)
        val response = IngestDocumentResponse.from(result)

        val status = when (result.status) {
            com.alfredoalpizar.rag.service.ingestion.IngestionStatus.SUCCESS -> HttpStatus.OK
            com.alfredoalpizar.rag.service.ingestion.IngestionStatus.SKIPPED -> HttpStatus.OK
            com.alfredoalpizar.rag.service.ingestion.IngestionStatus.FAILED -> HttpStatus.BAD_REQUEST
        }

        return@runBlocking ResponseEntity.status(status).body(response)
    }

    /**
     * Ingest all documents from a directory.
     */
    @PostMapping("/documents")
    fun ingestDocuments(
        @RequestBody(required = false) request: IngestDirectoryRequest?
    ): ResponseEntity<IngestDirectoryResponse> = runBlocking {
        val recursive = request?.recursive ?: false
        logger.info { "Ingesting documents (recursive=$recursive)" }

        val summary = ingestionService.ingestAll(recursive)
        val response = IngestDirectoryResponse.from(summary)

        return@runBlocking ResponseEntity.ok(response)
    }

    /**
     * Full re-index: delete all and re-ingest.
     */
    @PostMapping("/reindex")
    fun reindex(
        @RequestBody(required = false) request: ReindexRequest?
    ): ResponseEntity<IngestDirectoryResponse> = runBlocking {
        val recursive = request?.recursive ?: false
        logger.info { "Starting full re-index (recursive=$recursive)" }

        val summary = ingestionService.reindexAll(recursive)
        val response = IngestDirectoryResponse.from(summary)

        return@runBlocking ResponseEntity.ok(response)
    }

    /**
     * Delete all chunks for a specific document.
     */
    @DeleteMapping("/document/{docId}")
    fun deleteDocument(
        @PathVariable docId: String
    ): ResponseEntity<DeleteDocumentResponse> = runBlocking {
        logger.info { "Deleting document: $docId" }

        ingestionService.deleteDocument(docId)

        val response = DeleteDocumentResponse(
            docId = docId,
            message = "Document deleted successfully"
        )

        return@runBlocking ResponseEntity.ok(response)
    }

    /**
     * Get ingestion statistics.
     */
    @GetMapping("/status")
    fun getStatus(): ResponseEntity<IngestionStatsResponse> = runBlocking {
        val stats = ingestionService.getStatistics()
        val response = IngestionStatsResponse.from(stats)

        return@runBlocking ResponseEntity.ok(response)
    }
}
