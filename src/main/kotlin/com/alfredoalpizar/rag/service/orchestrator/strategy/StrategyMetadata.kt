package com.alfredoalpizar.rag.service.orchestrator.strategy

import com.alfredoalpizar.rag.config.LoopProperties.ModelStrategy

/**
 * Metadata about a model strategy implementation.
 * Used for logging, debugging, and runtime behavior.
 */
data class StrategyMetadata(
    /**
     * Human-readable strategy name (e.g., "DeepSeek Single", "Qwen Single Thinking")
     */
    val name: String,

    /**
     * Strategy type enum for configuration mapping
     */
    val strategyType: ModelStrategy,

    /**
     * Whether this strategy supports streaming reasoning traces
     * (e.g., Qwen thinking models with reasoning_content)
     */
    val supportsReasoningStream: Boolean,

    /**
     * Whether this strategy can operate in synchronous (non-streaming) mode
     * Some complex strategies may require streaming
     */
    val supportsSynchronous: Boolean,

    /**
     * Detailed description for logging and documentation
     */
    val description: String
)

/**
 * Iteration context passed to strategy execution.
 * Tells the strategy how to behave during this iteration.
 */
data class IterationContext(
    /**
     * Unique conversation identifier
     */
    val conversationId: String,

    /**
     * Current iteration number (1-based)
     */
    val iteration: Int,

    /**
     * Maximum iterations allowed
     */
    val maxIterations: Int,

    /**
     * Streaming mode for this execution
     */
    val streamingMode: StreamingMode
)

/**
 * Controls how the strategy emits events.
 */
enum class StreamingMode {
    /**
     * Emit progressive chunks as they arrive (for chat UI with SSE)
     * Strategy emits: ContentChunk, ReasoningChunk, ToolCallDetected
     */
    PROGRESSIVE,

    /**
     * Only emit final results (for synchronous operations)
     * Strategy emits: ToolCallsComplete, FinalResponse, IterationComplete
     */
    FINAL_ONLY,

    /**
     * Only emit reasoning traces (for debugging)
     * Strategy emits: ReasoningChunk only
     */
    REASONING_ONLY
}
