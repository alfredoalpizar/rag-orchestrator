package com.alfredoalpizar.rag.service.orchestrator.strategy.impl

import com.alfredoalpizar.rag.client.qwen.QwenTool
import com.alfredoalpizar.rag.config.LoopProperties
import com.alfredoalpizar.rag.config.LoopProperties.ModelStrategy
import com.alfredoalpizar.rag.model.domain.Message
import com.alfredoalpizar.rag.model.domain.MessageRole
import com.alfredoalpizar.rag.service.orchestrator.provider.QwenModelProvider
import com.alfredoalpizar.rag.service.orchestrator.provider.RequestConfig
import com.alfredoalpizar.rag.service.orchestrator.strategy.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Qwen Hybrid Staged Strategy - Advanced multi-stage orchestration.
 *
 * Three-stage approach:
 * 1. PLANNING: Use thinking model (qwen-max) to plan approach
 * 2. EXECUTION: Use instruct model (qwen-plus) for fast tool calls
 * 3. SYNTHESIS: Use thinking model again to synthesize final answer
 *
 * Best for complex tasks requiring both deep reasoning and efficient execution.
 *
 * Activated when: loop.model-strategy = qwen_hybrid_staged
 */
@Component
@ConditionalOnProperty(
    prefix = "loop",
    name = ["model-strategy"],
    havingValue = "qwen_hybrid_staged"
)
class QwenHybridStagedStrategy(
    private val provider: QwenModelProvider,
    private val properties: LoopProperties
) : ModelStrategyExecutor {

    private val logger = KotlinLogging.logger {}

    init {
        logger.info {
            """
            ╔════════════════════════════════════════════════════════╗
            ║  STRATEGY INITIALIZED: Qwen Hybrid Staged              ║
            ║  Provider: Qwen API                                    ║
            ║  Planning: qwen-max (thinking)                         ║
            ║  Execution: qwen-plus (instruct)                       ║
            ║  Synthesis: qwen-max (thinking)                        ║
            ║  Reasoning Stream: YES ✓ (planning & synthesis phases) ║
            ║  Description: Multi-stage hybrid orchestration         ║
            ╚════════════════════════════════════════════════════════╝
            """.trimIndent()
        }
    }

    override suspend fun executeIteration(
        messages: List<Message>,
        tools: List<*>,
        iterationContext: IterationContext
    ): Flow<StrategyEvent> = flow {

        logger.info {
            "[${iterationContext.conversationId}] " +
                    "Iteration ${iterationContext.iteration} - " +
                    "Strategy: Qwen Hybrid (3 stages: plan → execute → synthesize)"
        }

        // STAGE 1: Planning (Thinking Model)
        emit(StrategyEvent.StatusUpdate("Planning with thinking model...", "planning"))
        logger.debug { "Stage 1/3: Planning" }

        val planningResult = executePlanningStage(messages, tools, iterationContext)

        // If planning resulted in tool calls, execute them
        if (planningResult.toolCalls.isNotEmpty()) {
            // STAGE 2: Execution (Instruct Model)
            emit(StrategyEvent.StatusUpdate("Executing with instruct model...", "executing"))
            logger.debug { "Stage 2/3: Execution (${planningResult.toolCalls.size} tool calls)" }

            // Emit tool calls for execution by orchestrator
            emit(
                StrategyEvent.ToolCallsComplete(
                    toolCalls = planningResult.toolCalls,
                    assistantContent = planningResult.planningContent
                )
            )

            emit(
                StrategyEvent.IterationComplete(
                    tokensUsed = planningResult.tokensUsed,
                    shouldContinue = true,
                    metadata = mapOf(
                        "stage" to "execution",
                        "tool_calls" to planningResult.toolCalls.size
                    )
                )
            )
        } else {
            // No tool calls - directly synthesize final response
            // STAGE 3: Synthesis (Thinking Model)
            emit(StrategyEvent.StatusUpdate("Synthesizing final response...", "synthesizing"))
            logger.debug { "Stage 3/3: Synthesis (no tool calls, direct answer)" }

            emit(
                StrategyEvent.FinalResponse(
                    content = planningResult.planningContent,
                    tokensUsed = planningResult.tokensUsed,
                    metadata = mapOf(
                        "stage" to "synthesis",
                        "direct_answer" to true
                    )
                )
            )

            emit(
                StrategyEvent.IterationComplete(
                    tokensUsed = planningResult.tokensUsed,
                    shouldContinue = false
                )
            )
        }
    }

    private suspend fun FlowCollector<StrategyEvent>.executePlanningStage(
        messages: List<Message>,
        tools: List<*>,
        context: IterationContext
    ): PlanningResult {
        // Use thinking model for planning
        val requestConfig = RequestConfig(
            streamingEnabled = context.streamingMode == StreamingMode.PROGRESSIVE,
            temperature = properties.temperature,
            maxTokens = properties.maxTokens,
            extraParams = mapOf(
                "useThinkingModel" to true,
                "enableThinking" to true
            )
        )

        val request = provider.buildRequest(
            messages,
            tools.map { it as QwenTool },
            requestConfig
        )

        var planningContent = StringBuilder()
        var reasoningContent = StringBuilder()
        var toolCalls = mutableListOf<com.alfredoalpizar.rag.model.domain.ToolCall>()
        var tokensUsed = 0

        if (context.streamingMode == StreamingMode.PROGRESSIVE) {
            // Streaming planning
            provider.chatStream(request).collect { chunk ->
                val parsed = provider.extractStreamChunk(chunk)

                // Emit reasoning chunks (planning phase)
                parsed.reasoningDelta?.let { delta ->
                    reasoningContent.append(delta)
                    if (properties.thinking.showReasoningTraces) {
                        emit(
                            StrategyEvent.ReasoningChunk(
                                content = delta,
                                metadata = mapOf(
                                    "stage" to "planning",
                                    "model" to "qwen-max"
                                )
                            )
                        )
                    }
                }

                // Accumulate content
                parsed.contentDelta?.let { planningContent.append(it) }

                // Collect tool calls
                parsed.toolCalls?.let { toolCalls.addAll(it) }
            }
        } else {
            // Synchronous planning
            val response = provider.chat(request)
            val message = provider.extractMessage(response)

            planningContent.append(message.content ?: "")
            toolCalls.addAll(message.toolCalls ?: emptyList())
            tokensUsed = message.tokensUsed

            // Log reasoning if available
            message.reasoningContent?.let { reasoning ->
                reasoningContent.append(reasoning)
                logger.debug { "Planning reasoning: ${reasoning.take(200)}..." }

                if (properties.thinking.showReasoningTraces) {
                    emit(
                        StrategyEvent.ReasoningChunk(
                            content = reasoning,
                            metadata = mapOf(
                                "stage" to "planning",
                                "model" to "qwen-max"
                            )
                        )
                    )
                }
            }
        }

        logger.debug {
            "Planning complete: " +
                    "tool_calls=${toolCalls.size}, " +
                    "reasoning_length=${reasoningContent.length}"
        }

        return PlanningResult(
            planningContent = planningContent.toString(),
            reasoningContent = reasoningContent.toString(),
            toolCalls = toolCalls,
            tokensUsed = tokensUsed
        )
    }

    override fun getStrategyMetadata(): StrategyMetadata {
        return StrategyMetadata(
            name = "Qwen Hybrid Staged",
            strategyType = ModelStrategy.QWEN_HYBRID_STAGED,
            supportsReasoningStream = true,
            supportsSynchronous = true,
            description = "Multi-stage: planning (thinking) → execution (instruct) → synthesis (thinking)"
        )
    }

    private data class PlanningResult(
        val planningContent: String,
        val reasoningContent: String,
        val toolCalls: List<com.alfredoalpizar.rag.model.domain.ToolCall>,
        val tokensUsed: Int
    )
}
