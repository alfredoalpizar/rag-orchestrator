package com.alfredoalpizar.rag.service.ingestion.chunker

import com.alfredoalpizar.rag.model.document.ChunkMetadata
import com.alfredoalpizar.rag.model.document.Document
import com.alfredoalpizar.rag.model.document.DocumentChunk
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Chunker for FAQ documents.
 *
 * Strategy:
 * - One chunk per Q&A pair
 * - Questions are identified by H2 headings (##) ending with ?
 * - Answer is the content following the question until the next question
 */
@Component
class FAQChunker : Chunker {
    private val logger = KotlinLogging.logger {}

    // Pattern to match question headings: ## Question?
    private val questionPattern = Regex("""##\s+(.+\?)""")

    override fun chunk(document: Document): List<DocumentChunk> {
        val chunks = mutableListOf<DocumentChunk>()
        val body = document.content.body

        val qaList = extractQAPairs(body)

        if (qaList.isEmpty()) {
            logger.warn { "No Q&A pairs found in FAQ document: ${document.metadata.id}" }
            // Fallback: create single chunk with all content
            return listOf(createSingleChunk(document))
        }

        qaList.forEachIndexed { index, (question, answer) ->
            chunks.add(createQAChunk(document, index + 1, question, answer))
        }

        logger.debug { "Chunked FAQ document ${document.metadata.id} into ${chunks.size} Q&A chunks" }
        return chunks
    }

    override fun getCategory(): String = "faq"

    private fun extractQAPairs(body: String): List<Pair<String, String>> {
        val qaPairs = mutableListOf<Pair<String, String>>()
        val lines = body.lines()

        var currentQuestion: String? = null
        val currentAnswerLines = mutableListOf<String>()

        for (line in lines) {
            val questionMatch = questionPattern.find(line)

            if (questionMatch != null) {
                // Save previous Q&A pair if exists
                if (currentQuestion != null) {
                    val answer = currentAnswerLines.joinToString("\n").trim()
                    qaPairs.add(Pair(currentQuestion, answer))
                }

                // Start new question
                currentQuestion = questionMatch.groupValues[1].trim()
                currentAnswerLines.clear()
            } else if (currentQuestion != null && line.isNotBlank()) {
                // Add line to current answer
                currentAnswerLines.add(line)
            }
        }

        // Save last Q&A pair
        if (currentQuestion != null) {
            val answer = currentAnswerLines.joinToString("\n").trim()
            qaPairs.add(Pair(currentQuestion, answer))
        }

        return qaPairs
    }

    private fun createQAChunk(
        document: Document,
        index: Int,
        question: String,
        answer: String
    ): DocumentChunk {
        val text = "$question\n\n$answer"

        return DocumentChunk(
            id = "${document.metadata.id}::qa_$index",
            text = text.trim(),
            metadata = ChunkMetadata(
                docId = document.metadata.id,
                docTitle = document.metadata.title,
                category = document.metadata.category,
                status = document.metadata.status,
                tags = document.metadata.tags ?: emptyList(),
                lastUpdated = document.metadata.lastUpdated?.toString(),
                chunkType = ChunkMetadata.TYPE_FAQ_QA,
                chunkHeading = question,
                question = question
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
                chunkType = ChunkMetadata.TYPE_FAQ_QA,
                chunkHeading = "All Content"
            )
        )
    }
}
