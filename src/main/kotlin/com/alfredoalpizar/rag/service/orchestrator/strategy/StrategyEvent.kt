package com.alfredoalpizar.rag.service.orchestrator.strategy

import com.alfredoalpizar.rag.model.domain.ToolCall

/**
 * Events emitted by ModelStrategyExecutor during iteration execution.
 * Supports both streaming and synchronous modes.
 */
sealed class StrategyEvent {

    /**
     * Reasoning/thinking content chunk (Qwen thinking models, DeepSeek reasoner)
     * Only emitted when strategy supports reasoning and streaming is enabled
     */
    data class ReasoningChunk(
        val content: String,
        val metadata: Map<String, Any> = emptyMap()
    ) : StrategyEvent()

    /**
     * Regular content chunk for progressive streaming
     * Emitted in PROGRESSIVE streaming mode
     */
    data class ContentChunk(
        val content: String
    ) : StrategyEvent()

    /**
     * Single tool call detected (for streaming responses)
     * Emitted progressively as tool calls are identified
     */
    data class ToolCallDetected(
        val toolCall: ToolCall,
        val toolCallId: String
    ) : StrategyEvent()

    /**
     * All tool calls complete (for non-streaming responses)
     * Emitted in FINAL_ONLY mode with all tool calls at once
     */
    data class ToolCallsComplete(
        val toolCalls: List<ToolCall>,
        val assistantContent: String?
    ) : StrategyEvent()

    /**
     * Final response from model (no tool calls)
     * Indicates the end of the agentic loop
     */
    data class FinalResponse(
        val content: String,
        val tokensUsed: Int,
        val metadata: Map<String, Any> = emptyMap()
    ) : StrategyEvent()

    /**
     * Iteration complete marker
     * Signals the end of a single iteration
     */
    data class IterationComplete(
        val tokensUsed: Int,
        val shouldContinue: Boolean,
        val metadata: Map<String, Any> = emptyMap()
    ) : StrategyEvent()

    /**
     * Strategy-specific status update
     * Used for multi-stage strategies (e.g., planning, executing, synthesizing)
     */
    data class StatusUpdate(
        val status: String,
        val phase: String? = null
    ) : StrategyEvent()
}
