package com.alfredoalpizar.rag.service.ingestion.chunker

import com.alfredoalpizar.rag.model.document.ChunkMetadata
import com.alfredoalpizar.rag.model.document.Document
import com.alfredoalpizar.rag.model.document.DocumentChunk
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Chunker for troubleshooting documents.
 *
 * Strategy:
 * - Chunk by H2 sections (## Issue Title)
 * - Each issue/problem becomes an independent chunk with its solution
 */
@Component
class TroubleshootingChunker : Chunker {
    private val logger = KotlinLogging.logger {}

    // Pattern to match H2 headings: ## Title
    private val issuePattern = Regex("""##\s+(.+)""")

    override fun chunk(document: Document): List<DocumentChunk> {
        val chunks = mutableListOf<DocumentChunk>()
        val body = document.content.body

        val issues = extractIssues(body)

        if (issues.isEmpty()) {
            logger.warn { "No issues found in troubleshooting document: ${document.metadata.id}" }
            // Fallback: create single chunk with all content
            return listOf(createSingleChunk(document))
        }

        issues.forEachIndexed { index, (issueTitle, issueContent) ->
            chunks.add(createIssueChunk(document, index + 1, issueTitle, issueContent))
        }

        logger.debug { "Chunked troubleshooting document ${document.metadata.id} into ${chunks.size} issue chunks" }
        return chunks
    }

    override fun getCategory(): String = "troubleshooting"

    private fun extractIssues(body: String): List<Pair<String, String>> {
        val issues = mutableListOf<Pair<String, String>>()
        val lines = body.lines()

        var currentIssue: String? = null
        val currentIssueLines = mutableListOf<String>()

        for (line in lines) {
            val issueMatch = issuePattern.find(line)

            if (issueMatch != null) {
                // Save previous issue if exists
                if (currentIssue != null) {
                    val content = currentIssueLines.joinToString("\n").trim()
                    if (content.isNotBlank()) {
                        issues.add(Pair(currentIssue, content))
                    }
                }

                // Start new issue
                currentIssue = issueMatch.groupValues[1].trim()
                currentIssueLines.clear()
            } else if (currentIssue != null) {
                // Add line to current issue
                currentIssueLines.add(line)
            }
        }

        // Save last issue
        if (currentIssue != null) {
            val content = currentIssueLines.joinToString("\n").trim()
            if (content.isNotBlank()) {
                issues.add(Pair(currentIssue, content))
            }
        }

        return issues
    }

    private fun createIssueChunk(
        document: Document,
        index: Int,
        issueTitle: String,
        issueContent: String
    ): DocumentChunk {
        val text = "$issueTitle\n\n$issueContent"

        return DocumentChunk(
            id = "${document.metadata.id}::issue_$index",
            text = text.trim(),
            metadata = ChunkMetadata(
                docId = document.metadata.id,
                docTitle = document.metadata.title,
                category = document.metadata.category,
                status = document.metadata.status,
                tags = document.metadata.tags ?: emptyList(),
                lastUpdated = document.metadata.lastUpdated?.toString(),
                chunkType = ChunkMetadata.TYPE_TROUBLESHOOTING_ISSUE,
                chunkHeading = issueTitle
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
                chunkType = ChunkMetadata.TYPE_TROUBLESHOOTING_ISSUE,
                chunkHeading = "All Content"
            )
        )
    }
}
