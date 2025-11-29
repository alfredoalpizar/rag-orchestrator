package com.alfredoalpizar.rag.service.ingestion.chunker

import com.alfredoalpizar.rag.model.document.ChunkMetadata
import com.alfredoalpizar.rag.model.document.Document
import com.alfredoalpizar.rag.model.document.DocumentChunk
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Chunker for workflow documents.
 *
 * Strategy:
 * - Create one chunk for the overview/summary
 * - Create one chunk per step (### Step N: Title)
 */
@Component
class WorkflowChunker : Chunker {
    private val logger = KotlinLogging.logger {}

    // Pattern to match step headings: ### Step 1: Title or ### Step 1. Title
    private val stepPattern = Regex("""###\s+Step\s+(\d+)[:\.]?\s+(.+)""", RegexOption.IGNORE_CASE)

    override fun chunk(document: Document): List<DocumentChunk> {
        val chunks = mutableListOf<DocumentChunk>()
        val body = document.content.body
        val metadata = document.metadata

        // Extract overview (everything before first step)
        val firstStepIndex = body.indexOf("### Step", ignoreCase = true)
        if (firstStepIndex > 0) {
            val overviewText = buildOverviewText(document, body.substring(0, firstStepIndex))
            if (overviewText.isNotBlank()) {
                chunks.add(createOverviewChunk(document, overviewText))
            }
        } else {
            // No steps found, treat entire content as overview
            logger.warn { "No steps found in workflow document: ${metadata.id}" }
            val overviewText = buildOverviewText(document, body)
            chunks.add(createOverviewChunk(document, overviewText))
            return chunks
        }

        // Extract steps
        val steps = extractSteps(body)
        steps.forEach { (stepNumber, stepTitle, stepContent) ->
            chunks.add(createStepChunk(document, stepNumber, stepTitle, stepContent))
        }

        logger.debug { "Chunked workflow document ${metadata.id} into ${chunks.size} chunks (1 overview + ${steps.size} steps)" }
        return chunks
    }

    override fun getCategory(): String = "workflow"

    private fun buildOverviewText(document: Document, overviewContent: String): String {
        val parts = mutableListOf<String>()
        parts.add(document.metadata.title)

        if (document.content.summary != null) {
            parts.add(document.content.summary)
        }

        parts.add(overviewContent.trim())

        return parts.joinToString("\n\n")
    }

    private fun createOverviewChunk(document: Document, text: String): DocumentChunk {
        return DocumentChunk(
            id = "${document.metadata.id}::overview",
            text = text,
            metadata = ChunkMetadata(
                docId = document.metadata.id,
                docTitle = document.metadata.title,
                category = document.metadata.category,
                status = document.metadata.status,
                tags = document.metadata.tags ?: emptyList(),
                lastUpdated = document.metadata.lastUpdated?.toString(),
                chunkType = ChunkMetadata.TYPE_WORKFLOW_OVERVIEW,
                chunkHeading = "Overview"
            )
        )
    }

    private fun createStepChunk(
        document: Document,
        stepNumber: Int,
        stepTitle: String,
        stepContent: String
    ): DocumentChunk {
        val text = "Step $stepNumber: $stepTitle\n\n$stepContent"

        return DocumentChunk(
            id = "${document.metadata.id}::step_$stepNumber",
            text = text.trim(),
            metadata = ChunkMetadata(
                docId = document.metadata.id,
                docTitle = document.metadata.title,
                category = document.metadata.category,
                status = document.metadata.status,
                tags = document.metadata.tags ?: emptyList(),
                lastUpdated = document.metadata.lastUpdated?.toString(),
                chunkType = ChunkMetadata.TYPE_WORKFLOW_STEP,
                chunkHeading = stepTitle,
                stepNumber = stepNumber
            )
        )
    }

    private fun extractSteps(body: String): List<Triple<Int, String, String>> {
        val steps = mutableListOf<Triple<Int, String, String>>()
        val lines = body.lines()

        var currentStepNumber: Int? = null
        var currentStepTitle: String? = null
        val currentStepLines = mutableListOf<String>()

        for (line in lines) {
            val stepMatch = stepPattern.find(line)

            if (stepMatch != null) {
                // Save previous step if exists
                if (currentStepNumber != null && currentStepTitle != null) {
                    val content = currentStepLines.joinToString("\n").trim()
                    steps.add(Triple(currentStepNumber, currentStepTitle, content))
                }

                // Start new step
                currentStepNumber = stepMatch.groupValues[1].toInt()
                currentStepTitle = stepMatch.groupValues[2].trim()
                currentStepLines.clear()
            } else if (currentStepNumber != null) {
                // Add line to current step
                currentStepLines.add(line)
            }
        }

        // Save last step
        if (currentStepNumber != null && currentStepTitle != null) {
            val content = currentStepLines.joinToString("\n").trim()
            steps.add(Triple(currentStepNumber, currentStepTitle, content))
        }

        return steps
    }
}
