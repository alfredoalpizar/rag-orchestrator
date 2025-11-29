package com.alfredoalpizar.rag.service.ingestion.chunker

import com.alfredoalpizar.rag.model.document.ChunkMetadata
import com.alfredoalpizar.rag.model.document.Document
import com.alfredoalpizar.rag.model.document.DocumentChunk
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Chunker for reference documentation.
 *
 * Strategy:
 * - Chunk by H2 sections (## Section Title)
 * - Each section becomes an independent chunk
 */
@Component
class ReferenceChunker : Chunker {
    private val logger = KotlinLogging.logger {}

    // Pattern to match H2 headings: ## Title
    private val sectionPattern = Regex("""##\s+(.+)""")

    override fun chunk(document: Document): List<DocumentChunk> {
        val chunks = mutableListOf<DocumentChunk>()
        val body = document.content.body

        val sections = extractSections(body)

        if (sections.isEmpty()) {
            logger.warn { "No sections found in reference document: ${document.metadata.id}" }
            // Fallback: create single chunk with all content
            return listOf(createSingleChunk(document))
        }

        sections.forEachIndexed { index, (sectionTitle, sectionContent) ->
            chunks.add(createSectionChunk(document, index + 1, sectionTitle, sectionContent))
        }

        logger.debug { "Chunked reference document ${document.metadata.id} into ${chunks.size} section chunks" }
        return chunks
    }

    override fun getCategory(): String = "reference"

    private fun extractSections(body: String): List<Pair<String, String>> {
        val sections = mutableListOf<Pair<String, String>>()
        val lines = body.lines()

        var currentSection: String? = null
        val currentSectionLines = mutableListOf<String>()

        for (line in lines) {
            val sectionMatch = sectionPattern.find(line)

            if (sectionMatch != null) {
                // Save previous section if exists
                if (currentSection != null) {
                    val content = currentSectionLines.joinToString("\n").trim()
                    if (content.isNotBlank()) {
                        sections.add(Pair(currentSection, content))
                    }
                }

                // Start new section
                currentSection = sectionMatch.groupValues[1].trim()
                currentSectionLines.clear()
            } else if (currentSection != null) {
                // Add line to current section
                currentSectionLines.add(line)
            }
        }

        // Save last section
        if (currentSection != null) {
            val content = currentSectionLines.joinToString("\n").trim()
            if (content.isNotBlank()) {
                sections.add(Pair(currentSection, content))
            }
        }

        return sections
    }

    private fun createSectionChunk(
        document: Document,
        index: Int,
        sectionTitle: String,
        sectionContent: String
    ): DocumentChunk {
        val text = "$sectionTitle\n\n$sectionContent"

        return DocumentChunk(
            id = "${document.metadata.id}::section_$index",
            text = text.trim(),
            metadata = ChunkMetadata(
                docId = document.metadata.id,
                docTitle = document.metadata.title,
                category = document.metadata.category,
                status = document.metadata.status,
                tags = document.metadata.tags ?: emptyList(),
                lastUpdated = document.metadata.lastUpdated?.toString(),
                chunkType = ChunkMetadata.TYPE_REFERENCE_SECTION,
                chunkHeading = sectionTitle
            )
        )
    }

    private fun createSingleChunk(document: Document): DocumentChunk {
        val text = buildString {
            append(document.metadata.title)
            append("\n\n")
            if (document.content.summary != null) {
                append(document.content.summary)
                append("\n\n")
            }
            append(document.content.body)
        }

        return DocumentChunk(
            id = "${document.metadata.id}::all",
            text = text.trim(),
            metadata = ChunkMetadata(
                docId = document.metadata.id,
                docTitle = document.metadata.title,
                category = document.metadata.category,
                status = document.metadata.status,
                tags = document.metadata.tags ?: emptyList(),
                lastUpdated = document.metadata.lastUpdated?.toString(),
                chunkType = ChunkMetadata.TYPE_REFERENCE_SECTION,
                chunkHeading = "All Content"
            )
        )
    }
}
