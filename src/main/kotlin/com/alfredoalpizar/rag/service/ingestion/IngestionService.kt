package com.alfredoalpizar.rag.service.ingestion

import com.alfredoalpizar.rag.client.chromadb.ChromaDBClient
import com.alfredoalpizar.rag.config.Environment
import com.alfredoalpizar.rag.model.document.Document
import com.alfredoalpizar.rag.model.document.DocumentChunk
import com.alfredoalpizar.rag.service.ingestion.chunker.ChunkingService
import com.alfredoalpizar.rag.service.ingestion.embedding.EmbeddingService
import com.alfredoalpizar.rag.service.ingestion.parser.DocumentParser
import com.alfredoalpizar.rag.service.ingestion.parser.DocumentWithSource
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File

/**
 * Main ingestion service for processing documents and storing them in ChromaDB.
 *
 * Pipeline:
 * 1. Parse document from file
 * 2. Validate document (only "published" documents are indexed)
 * 3. Chunk document based on category
 * 4. Generate embeddings for chunks
 * 5. Delete existing chunks for this document (if re-indexing)
 * 6. Upsert new chunks to ChromaDB
 */
@Service
class IngestionService(
    private val documentParser: DocumentParser,
    private val chunkingService: ChunkingService,
    private val embeddingService: EmbeddingService,
    private val chromaDBClient: ChromaDBClient
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Ingest a single document from a file path.
     *
     * @param filePath Path to the document file
     * @return Ingestion result with statistics
     */
    suspend fun ingestDocument(filePath: String): IngestionResult {
        return ingestDocument(File(filePath))
    }

    /**
     * Ingest a single document from a File object.
     *
     * @param file Document file
     * @return Ingestion result with statistics
     */
    suspend fun ingestDocument(file: File): IngestionResult {
        logger.info("Ingesting document: ${file.name}")

        return try {
            // 1. Parse document
            val document = documentParser.parse(file)

            // 2. Use shared internal method
            ingestDocumentInternal(document, file.name)
        } catch (e: Exception) {
            logger.error("Failed to parse document: ${file.name}", e)
            IngestionResult.failed(
                fileName = file.name,
                error = "Failed to parse document: ${e.message}"
            )
        }
    }

    /**
     * Ingest all documents from the configured documents directory.
     *
     * @param recursive Whether to search recursively in subdirectories
     * @return Summary of ingestion results
     */
    suspend fun ingestAll(recursive: Boolean = false): IngestionSummary {
        val documentsPath = File(Environment.INGESTION_DOCS_PATH)

        if (!documentsPath.exists()) {
            logger.error("Documents path does not exist: ${Environment.INGESTION_DOCS_PATH}")
            return IngestionSummary(
                totalFiles = 0,
                successful = 0,
                skipped = 0,
                failed = 0,
                results = emptyList(),
                error = "Documents path does not exist: ${Environment.INGESTION_DOCS_PATH}"
            )
        }

        val documentsWithSource = if (recursive) {
            documentParser.parseDirectoryRecursive(documentsPath)
        } else {
            documentParser.parseDirectory(documentsPath)
        }

        logger.info("Found ${documentsWithSource.size} documents to ingest")

        val results = documentsWithSource.map { docWithSource ->
            ingestDocumentWithSource(docWithSource)
        }

        val summary = IngestionSummary(
            totalFiles = results.size,
            successful = results.count { it.status == IngestionStatus.SUCCESS },
            skipped = results.count { it.status == IngestionStatus.SKIPPED },
            failed = results.count { it.status == IngestionStatus.FAILED },
            results = results
        )

        logger.info("Ingestion complete: ${summary.successful} successful, ${summary.skipped} skipped, ${summary.failed} failed")

        return summary
    }

    /**
     * Delete all chunks for a specific document.
     *
     * @param docId Document ID
     */
    suspend fun deleteDocument(docId: String) {
        logger.info("Deleting all chunks for document: $docId")
        deleteDocumentChunks(docId)
    }

    /**
     * Re-index all documents (delete all and re-ingest).
     *
     * @param recursive Whether to search recursively
     * @return Ingestion summary
     */
    suspend fun reindexAll(recursive: Boolean = false): IngestionSummary {
        logger.info("Starting full re-index")

        // Note: We could delete all chunks here, but it's safer to delete per-document
        // during ingestion to avoid data loss if ingestion fails

        return ingestAll(recursive)
    }

    /**
     * Ingest a document object directly (for API-created documents).
     *
     * This method accepts a Document object instead of a file path,
     * making it suitable for documents created/updated via the management API.
     *
     * @param document Document to ingest
     * @return Ingestion result with statistics
     */
    suspend fun ingestDocument(document: Document): IngestionResult {
        logger.info("Ingesting document: ${document.metadata.id}")
        return ingestDocumentInternal(document, document.metadata.id)
    }

    /**
     * Get ingestion statistics.
     *
     * @return Current statistics about the knowledge base
     */
    suspend fun getStatistics(): IngestionStatistics {
        val count = chromaDBClient.count().awaitSingle()
        val modelInfo = embeddingService.getModelInfo()

        return IngestionStatistics(
            totalChunks = count,
            embeddingProvider = modelInfo.provider,
            embeddingModel = modelInfo.model,
            embeddingDimensions = modelInfo.dimensions
        )
    }

    /**
     * Internal shared ingestion logic to avoid duplicate deletion operations.
     */
    private suspend fun ingestDocumentInternal(document: Document, fileName: String): IngestionResult {
        try {
            // 1. Validate
            val validationResult = document.validate()
            if (!validationResult.isValid()) {
                logger.error("Document validation failed: ${validationResult.errorsOrEmpty()}")
                return IngestionResult.failed(
                    fileName = fileName,
                    error = "Validation failed: ${validationResult.errorsOrEmpty().joinToString()}"
                )
            }

            if (!document.shouldIndex()) {
                logger.info("Skipping document ${document.metadata.id}: status is ${document.metadata.status}")
                return IngestionResult.skipped(
                    fileName = fileName,
                    reason = "Document status is not 'published'"
                )
            }

            // 2. Chunk document
            val chunks = chunkingService.chunk(document)
            logger.debug("Created ${chunks.size} chunks for document ${document.metadata.id}")

            // 3. Generate embeddings
            val chunkTexts = chunks.map { it.text }
            val embeddings = embeddingService.embedBatch(chunkTexts)
            logger.debug("Generated ${embeddings.size} embeddings")

            // 4. Enrich chunks with embeddings and model metadata
            val modelInfo = embeddingService.getModelInfo()
            val enrichedChunks = chunks.mapIndexed { index, chunk ->
                chunk
                    .withEmbedding(embeddings[index])
                    .withEnrichedMetadata(
                        embeddingProvider = modelInfo.provider,
                        embeddingModel = modelInfo.model,
                        embeddingDimensions = modelInfo.dimensions
                    )
            }

            // 5. Ensure ChromaDB collection exists
            chromaDBClient.createCollectionIfNotExists().awaitSingleOrNull()

            // 6. Delete existing chunks for this document (only once!)
            deleteDocumentChunks(document.metadata.id)

            // 7. Upsert to ChromaDB
            upsertChunks(enrichedChunks)

            logger.info("✅ Successfully ingested document ${document.metadata.id}: ${chunks.size} chunks")

            return IngestionResult.success(
                fileName = fileName,
                docId = document.metadata.id,
                chunksCreated = chunks.size
            )

        } catch (e: Exception) {
            logger.error("Failed to ingest document: $fileName", e)
            return IngestionResult.failed(
                fileName = fileName,
                error = e.message ?: "Unknown error"
            )
        }
    }

    private suspend fun ingestDocumentWithSource(docWithSource: DocumentWithSource): IngestionResult {
        return ingestDocumentInternal(docWithSource.document, docWithSource.fileName)
    }

    private suspend fun deleteDocumentChunks(docId: String) {
        try {
            chromaDBClient.deleteByMetadata(mapOf("doc_id" to docId)).awaitSingle()
            logger.debug("Deleted existing chunks for document: $docId")
        } catch (e: Exception) {
            logger.warn("Failed to delete existing chunks for document: $docId", e)
            // Continue anyway - upsert will handle it
        }
    }

    private suspend fun upsertChunks(chunks: List<DocumentChunk>) {
        if (chunks.isEmpty()) {
            logger.warn("No chunks to upsert")
            return
        }

        val ids = chunks.map { it.id }
        val embeddings = chunks.map { it.embedding ?: throw IllegalStateException("Chunk missing embedding") }
        val documents = chunks.map { it.text }
        val metadatas = chunks.map { it.metadata.toFlatMap() }

        chromaDBClient.upsert(ids, embeddings, documents, metadatas).awaitSingle()
    }
}

enum class IngestionStatus {
    SUCCESS, SKIPPED, FAILED
}

data class IngestionResult(
    val fileName: String,
    val status: IngestionStatus,
    val docId: String? = null,
    val chunksCreated: Int? = null,
    val error: String? = null
) {
    companion object {
        fun success(fileName: String, docId: String, chunksCreated: Int) = IngestionResult(
            fileName = fileName,
            status = IngestionStatus.SUCCESS,
            docId = docId,
            chunksCreated = chunksCreated
        )

        fun skipped(fileName: String, reason: String) = IngestionResult(
            fileName = fileName,
            status = IngestionStatus.SKIPPED,
            error = reason
        )

        fun failed(fileName: String, error: String) = IngestionResult(
            fileName = fileName,
            status = IngestionStatus.FAILED,
            error = error
        )
    }
}

data class IngestionSummary(
    val totalFiles: Int,
    val successful: Int,
    val skipped: Int,
    val failed: Int,
    val results: List<IngestionResult>,
    val error: String? = null
)

data class IngestionStatistics(
    val totalChunks: Int,
    val embeddingProvider: String,
    val embeddingModel: String,
    val embeddingDimensions: Int
)
