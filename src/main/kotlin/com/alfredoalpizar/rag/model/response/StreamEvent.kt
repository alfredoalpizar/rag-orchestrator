package com.alfredoalpizar.rag.model.response

import java.time.Instant

sealed class StreamEvent {
    abstract val conversationId: String
    abstract val timestamp: Instant

    data class StatusUpdate(
        override val conversationId: String,
        val status: String,
        val details: String? = null,
        val iteration: Int? = null,  // Which iteration this status belongs to
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class ToolCallStart(
        override val conversationId: String,
        val toolName: String,
        val toolCallId: String,
        val arguments: Map<String, Any>,
        val iteration: Int? = null,  // Which iteration this tool call belongs to
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class ToolCallResult(
        override val conversationId: String,
        val toolName: String,
        val toolCallId: String,
        val result: String,
        val success: Boolean,
        val iteration: Int? = null,  // Which iteration this result belongs to
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class ResponseChunk(
        override val conversationId: String,
        val content: String,
        val iteration: Int? = null,  // Which iteration this chunk belongs to
        val isFinalAnswer: Boolean = false,  // True when streaming from finalize_answer
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class Completed(
        override val conversationId: String,
        val iterationsUsed: Int,
        val tokensUsed: Int,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    data class Error(
        override val conversationId: String,
        val error: String,
        val details: String? = null,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    // NEW: Show reasoning traces from thinking models
    data class ReasoningTrace(
        override val conversationId: String,
        val content: String,
        val stage: ReasoningStage,
        val iteration: Int? = null,  // Which iteration this reasoning belongs to
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    // NEW: Show execution plan generated during planning stage
    data class ExecutionPlan(
        override val conversationId: String,
        val plannedTools: List<PlannedTool>,
        val reasoning: String,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()

    // NEW: Stage transition notifications
    data class StageTransition(
        override val conversationId: String,
        val fromStage: Stage,
        val toStage: Stage,
        val details: String? = null,
        override val timestamp: Instant = Instant.now()
    ) : StreamEvent()
}

enum class ReasoningStage {
    PLANNING,
    SYNTHESIS
}

enum class Stage {
    LOADING,
    PLANNING,
    EXECUTION,
    SYNTHESIS,
    COMPLETED
}

data class PlannedTool(
    val name: String,
    val purpose: String,
    val arguments: Map<String, Any>
)
