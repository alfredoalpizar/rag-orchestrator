package com.alfredoalpizar.rag.model.domain

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Metadata stored alongside assistant messages to preserve streaming context.
 * Serialized as JSON in the database.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MessageMetadata(
    val toolCalls: List<ToolCallRecord>? = null,
    val reasoning: String? = null,
    val iterationData: List<IterationRecord>? = null,
    val metrics: MetricsRecord? = null
) {
    companion object {
        private val objectMapper = jacksonObjectMapper()

        fun fromJson(json: String?): MessageMetadata? {
            if (json.isNullOrBlank()) return null
            return try {
                objectMapper.readValue(json, MessageMetadata::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): String {
        return objectMapper.writeValueAsString(this)
    }
}

/**
 * Record of a single tool call during the agentic loop.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolCallRecord(
    val id: String,
    val name: String,
    val arguments: Map<String, Any>? = null,
    val result: ToolResultSummary? = null,
    val success: Boolean = true,
    val iteration: Int? = null
)

/**
 * Summarized tool result to avoid storing huge RAG results.
 * For rag_search: stores chunk IDs and scores, not full text.
 * For other tools: stores relevant summary data.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolResultSummary(
    val type: String,  // "rag_search", "finalize_answer", etc.
    val summary: String? = null,
    val chunks: List<ChunkReference>? = null,  // For RAG search results
    val success: Boolean = true
)

/**
 * Reference to a retrieved chunk (for RAG search results).
 * Stores ID and score, not the full chunk text.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChunkReference(
    val chunkId: String,
    val documentId: String? = null,
    val score: Float? = null
)

/**
 * Per-iteration data for multi-iteration agentic loops.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class IterationRecord(
    val iteration: Int,
    val reasoning: String? = null,
    val toolCallIds: List<String>? = null
)

/**
 * Metrics for this message's generation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MetricsRecord(
    val iterations: Int? = null,
    val totalTokens: Int? = null
)
