package com.alfredoalpizar.rag.service.storage

import com.alfredoalpizar.rag.coroutine.currentMDCContext
import com.alfredoalpizar.rag.model.document.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.io.path.*

/**
 * Local filesystem implementation of DocumentStore.
 *
 * Storage Convention:
 * - Base path: configured via Environment.INGESTION_LOCAL_PATH (e.g., ./docs/samples)
 * - File path: {category}/{id}.yml (e.g., faq/password_reset.yml)
 * - Uses file modification time as ETag for optimistic locking
 *
 * Features:
 * - Atomic writes (write to .tmp file, then atomic move)
 * - Auto-creates directories as needed
 * - Thread-safe (uses IO dispatcher)
 * - Handles category changes (moves file to new category directory)
 *
 * @param basePath Base directory for document storage
 * @param serializer YAML serializer for Document <-> bytes conversion
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class LocalDocumentStore(
    private val basePath: Path,
    private val serializer: DocumentSerializer
) : DocumentStore {

    private val logger = KotlinLogging.logger {}

    init {
        // Ensure base directory exists on startup
        if (!basePath.exists()) {
            basePath.createDirectories()
            logger.info { "Created documents directory: $basePath" }
        }
    }

    override suspend fun list(recursive: Boolean): List<DocumentRef> = withContext(Dispatchers.IO + currentMDCContext()) {
        val files = if (recursive) {
            basePath.walk().filter { it.isRegularFile() }
        } else {
            basePath.listDirectoryEntries().asSequence().filter { it.isRegularFile() }
        }

        files.filter { it.extension in listOf("yml", "yaml", "json") }
            .mapNotNull { path ->
                try {
                    val bytes = path.readBytes()
                    val document = serializer.deserialize(bytes)
                    createRef(document, path)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse document: $path" }
                    null
                }
            }
            .toList()
    }

    override suspend fun getById(docId: String): DocumentRef? = withContext(Dispatchers.IO + currentMDCContext()) {
        // Search all categories for this docId
        // This is less efficient than a direct lookup, but supports category changes
        list(recursive = true).find { it.docId == docId }
    }

    override suspend fun read(ref: DocumentRef): ByteArray = withContext(Dispatchers.IO + currentMDCContext()) {
        val path = basePath.resolve(ref.path)
        if (!path.exists()) {
            throw DocumentNotFoundException("Document not found: ${ref.docId} at path: ${ref.path}")
        }
        path.readBytes()
    }

    override suspend fun create(document: Document): DocumentRef = withContext(Dispatchers.IO + currentMDCContext()) {
        val docId = document.metadata.id
        val category = document.metadata.category

        // Check if already exists
        if (exists(docId)) {
            throw DocumentAlreadyExistsException("Document already exists: $docId")
        }

        // Validate ID is path-safe
        validateDocumentId(docId)

        // Create category directory if needed
        val categoryPath = basePath.resolve(category)
        if (!categoryPath.exists()) {
            categoryPath.createDirectories()
            logger.debug { "Created category directory: $categoryPath" }
        }

        // Write document atomically
        val fileName = "$docId.yml"
        val filePath = categoryPath.resolve(fileName)
        val bytes = serializer.serialize(document)

        // Atomic write: write to temp file, then move
        val tempPath = filePath.resolveSibling("$fileName.tmp")
        tempPath.writeBytes(bytes)
        Files.move(tempPath, filePath, StandardCopyOption.ATOMIC_MOVE)

        logger.info { "Created document: $docId at $filePath" }

        createRef(document, filePath)
    }

    override suspend fun update(docId: String, document: Document): DocumentRef = withContext(Dispatchers.IO + currentMDCContext()) {
        val existing = getById(docId)
            ?: throw DocumentNotFoundException("Document not found: $docId")

        // Validate the document ID matches
        if (document.metadata.id != docId) {
            throw IllegalArgumentException("Document ID mismatch: expected $docId, got ${document.metadata.id}")
        }

        val existingPath = basePath.resolve(existing.path)
        val newCategory = document.metadata.category

        // Check if category changed (need to move file)
        val needsMove = newCategory != existing.category

        val bytes = serializer.serialize(document)

        if (needsMove) {
            // Delete old file and create in new category
            logger.info { "Moving document $docId from category ${existing.category} to $newCategory" }
            existingPath.deleteIfExists()
            create(document)
        } else {
            // Atomic update in same location
            val tempPath = existingPath.resolveSibling("${existingPath.fileName}.tmp")
            tempPath.writeBytes(bytes)
            Files.move(
                tempPath,
                existingPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )

            logger.info { "Updated document: $docId" }
            createRef(document, existingPath)
        }
    }

    override suspend fun delete(docId: String) = withContext(Dispatchers.IO + currentMDCContext()) {
        val ref = getById(docId)
            ?: throw DocumentNotFoundException("Document not found: $docId")

        val path = basePath.resolve(ref.path)

        if (path.deleteIfExists()) {
            logger.info { "Deleted document: $docId at $path" }
        }
    }

    override suspend fun exists(docId: String): Boolean {
        return getById(docId) != null
    }

    /**
     * Create a DocumentRef from a Document and its file path.
     */
    private fun createRef(document: Document, path: Path): DocumentRef {
        val relativePath = basePath.relativize(path).toString()
        return DocumentRef(
            docId = document.metadata.id,
            path = relativePath,
            name = path.fileName.toString(),
            category = document.metadata.category,
            lastModified = Instant.ofEpochMilli(path.getLastModifiedTime().toMillis()),
            sizeBytes = path.fileSize()
        )
    }

    /**
     * Validate document ID is safe for filesystem paths.
     * Prevents directory traversal attacks and invalid filenames.
     */
    private fun validateDocumentId(docId: String) {
        if (docId.contains("..") || docId.contains("/") || docId.contains("\\")) {
            throw IllegalArgumentException("Document ID contains invalid characters: $docId")
        }
        if (docId.isBlank()) {
            throw IllegalArgumentException("Document ID cannot be blank")
        }
    }
}
