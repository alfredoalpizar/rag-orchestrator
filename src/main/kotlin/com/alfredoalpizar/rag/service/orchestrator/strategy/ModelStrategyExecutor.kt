package com.alfredoalpizar.rag.service.orchestrator.strategy

import com.alfredoalpizar.rag.model.domain.Message
import kotlinx.coroutines.flow.Flow

/**
 * Interface for model strategy execution.
 *
 * Strategies implement different approaches to LLM orchestration:
 * - Single model strategies (DeepSeek, Qwen thinking, Qwen instruct)
 * - Custom strategies for specific use cases
 *
 * Strategies are stateless and isolated - they don't manage conversation history.
 * The OrchestratorService handles conversation management via ContextManager.
 */
interface ModelStrategyExecutor {

    /**
     * Execute a single iteration of the agentic loop.
     *
     * @param messages Current conversation messages (including history)
     * @param tools Available tool definitions for the LLM
     * @param iterationContext Execution context (iteration number, streaming mode, etc.)
     * @return Flow of strategy events (supports both streaming and synchronous collection)
     */
    suspend fun executeIteration(
        messages: List<Message>,
        tools: List<*>,
        iterationContext: IterationContext
    ): Flow<StrategyEvent>

    /**
     * Get metadata about this strategy for logging and runtime behavior.
     */
    fun getStrategyMetadata(): StrategyMetadata
}
