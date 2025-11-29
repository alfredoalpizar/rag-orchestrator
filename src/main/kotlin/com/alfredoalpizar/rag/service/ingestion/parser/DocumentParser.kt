package com.alfredoalpizar.rag.service.ingestion.parser

import com.alfredoalpizar.rag.model.document.Document
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.io.File

/**
 * Parser for document files (YAML, JSON).
 *
 * Supports:
 * - YAML files (.yml, .yaml)
 * - JSON files (.json)
 *
 * Parses files into Document objects with validation.
 */
@Component
class DocumentParser(
    private val jsonObjectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    private val yamlMapper = ObjectMapper(YAMLFactory()).apply {
        // Register Kotlin module for proper Kotlin data class support
        registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
        // Configure to match JSON mapper settings
        configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    /**
     * Parse a document file into a Document object.
     *
     * @param file File to parse
     * @return Parsed document
     * @throws DocumentParseException if parsing fails
     */
    fun parse(file: File): Document {
        logger.debug { "Parsing document file: ${file.absolutePath}" }

        if (!file.exists()) {
            throw DocumentParseException("File not found: ${file.absolutePath}")
        }

        if (!file.isFile) {
            throw DocumentParseException("Not a file: ${file.absolutePath}")
        }

        val document = try {
            when (file.extension.lowercase()) {
                "yml", "yaml" -> parseYaml(file)
                "json" -> parseJson(file)
                else -> throw DocumentParseException(
                    "Unsupported file extension: ${file.extension}. " +
                    "Supported: .yml, .yaml, .json"
                )
            }
        } catch (e: DocumentParseException) {
            throw e
        } catch (e: Exception) {
            throw DocumentParseException("Failed to parse file: ${file.name}", e)
        }

        logger.debug { "Parsed document: id=${document.metadata.id}, category=${document.metadata.category}" }
        return document
    }

    /**
     * Parse a document from a file path string.
     *
     * @param filePath Path to the file
     * @return Parsed document
     */
    fun parse(filePath: String): Document {
        return parse(File(filePath))
    }

    /**
     * Parse all document files in a directory (non-recursive).
     *
     * @param directory Directory to scan
     * @return List of parsed documents
     */
    fun parseDirectory(directory: File): List<DocumentWithSource> {
        logger.debug { "Parsing documents from directory: ${directory.absolutePath}" }

        if (!directory.exists()) {
            throw DocumentParseException("Directory not found: ${directory.absolutePath}")
        }

        if (!directory.isDirectory) {
            throw DocumentParseException("Not a directory: ${directory.absolutePath}")
        }

        val files = directory.listFiles { file ->
            file.isFile && file.extension.lowercase() in listOf("yml", "yaml", "json")
        } ?: emptyArray()

        logger.info { "Found ${files.size} document files in ${directory.name}" }

        val documents = files.mapNotNull { file ->
            try {
                DocumentWithSource(parse(file), file)
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse file: ${file.name}" }
                null
            }
        }

        logger.info { "Successfully parsed ${documents.size}/${files.size} documents" }
        return documents
    }

    /**
     * Parse all document files in a directory recursively.
     *
     * @param directory Directory to scan
     * @return List of parsed documents
     */
    fun parseDirectoryRecursive(directory: File): List<DocumentWithSource> {
        logger.debug { "Recursively parsing documents from directory: ${directory.absolutePath}" }

        if (!directory.exists()) {
            throw DocumentParseException("Directory not found: ${directory.absolutePath}")
        }

        if (!directory.isDirectory) {
            throw DocumentParseException("Not a directory: ${directory.absolutePath}")
        }

        val documents = mutableListOf<DocumentWithSource>()
        directory.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.lowercase() in listOf("yml", "yaml", "json")) {
                try {
                    documents.add(DocumentWithSource(parse(file), file))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse file: ${file.absolutePath}" }
                }
            }
        }

        logger.info { "Successfully parsed ${documents.size} documents recursively" }
        return documents
    }

    private fun parseYaml(file: File): Document {
        return yamlMapper.readValue(file)
    }

    private fun parseJson(file: File): Document {
        return jsonObjectMapper.readValue(file)
    }
}

/**
 * Document with its source file information.
 */
data class DocumentWithSource(
    val document: Document,
    val sourceFile: File
) {
    val fileName: String get() = sourceFile.name
    val filePath: String get() = sourceFile.absolutePath
}

/**
 * Exception thrown when document parsing fails.
 */
class DocumentParseException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
