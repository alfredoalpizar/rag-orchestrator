package com.alfredoalpizar.rag.service.orchestrator.strategy.impl

import com.alfredoalpizar.rag.client.qwen.QwenTool
import com.alfredoalpizar.rag.config.Environment
import com.alfredoalpizar.rag.config.Environment.ModelStrategy
import com.alfredoalpizar.rag.model.domain.Message
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
 * Qwen Single Thinking Strategy.
 *
 * Uses Qwen thinking model (qwen-max) for all iterations.
 * Supports reasoning traces and deep thinking.
 * Best for complex reasoning tasks where quality > speed.
 *
 * Activated when: loop.model-strategy = qwen_single_thinking
 */
@Component
@ConditionalOnProperty(
    prefix = "loop",
    name = ["model-strategy"],
    havingValue = "qwen_single_thinking"
)
class QwenSingleThinkingStrategy(
    private val provider: QwenModelProvider
) : ModelStrategyExecutor {

    private val logger = KotlinLogging.logger {}

    init {
        logger.info {
            """
            ╔════════════════════════════════════════════════════════╗
            ║  STRATEGY INITIALIZED: Qwen Single Thinking            ║
            ║  Provider: Qwen API                                    ║
            ║  Model: qwen-max (thinking)                            ║
            ║  Reasoning Stream: YES ✓                               ║
            ║  Description: Thinking model with reasoning traces     ║
            ╚════════════════════════════════════════════════════════╝
            """.trimIndent()
        }
    }

    override suspend fun executeIteration(
        messages: List<Message>,
        tools: List<*>,
        iterationContext: IterationContext
    ): Flow<StrategyEvent> = flow {

        logger.debug {
            "[${iterationContext.conversationId}] " +
                    "Iteration ${iterationContext.iteration}/${iterationContext.maxIterations} " +
                    "- Strategy: Qwen Thinking (reasoning enabled)"
        }

        val requestConfig = RequestConfig(
            streamingEnabled = iterationContext.streamingMode == StreamingMode.PROGRESSIVE,
            temperature = Environment.LOOP_TEMPERATURE,
            maxTokens = Environment.LOOP_MAX_TOKENS,
            extraParams = mapOf(
                "useThinkingModel" to true,
                "enableThinking" to true  // Enable reasoning for thinking model
            )
        )

        if (iterationContext.streamingMode == StreamingMode.PROGRESSIVE) {
            emit(StrategyEvent.StatusUpdate(
                status = "Qwen model thinking...",
                phase = "reasoning"
            ))
            executeStreamingIteration(messages, tools, requestConfig, iterationContext)
        } else {
            executeSynchronousIteration(messages, tools, requestConfig)
        }
    }

    private suspend fun FlowCollector<StrategyEvent>.executeStreamingIteration(
        messages: List<Message>,
        tools: List<*>,
        config: RequestConfig,
        context: IterationContext
    ) {
        val request = provider.buildRequest(messages, tools, config)

        var accumulatedContent = StringBuilder()
        var accumulatedReasoning = StringBuilder()
        var accumulatedToolCalls = mutableListOf<com.alfredoalpizar.rag.model.domain.ToolCall>()
        var totalTokens = 0

        provider.chatStream(request).collect { chunk ->
            val parsed = provider.extractStreamChunk(chunk)

            // Emit REASONING chunks (Qwen-specific!)
            parsed.reasoningDelta?.let { reasoningDelta ->
                accumulatedReasoning.append(reasoningDelta)
                logger.trace { "Reasoning trace: $reasoningDelta" }

                if (Environment.LOOP_THINKING_SHOW_REASONING) {
                    emit(
                        StrategyEvent.ReasoningChunk(
                            content = reasoningDelta,
                            metadata = mapOf(
                                "model" to "qwen-max",
                                "thinking_enabled" to true
                            )
                        )
                    )
                }
            }

            // Emit content chunks
            parsed.contentDelta?.let { delta ->
                accumulatedContent.append(delta)
                emit(StrategyEvent.ContentChunk(delta))
            }

            // Handle tool calls
            parsed.toolCalls?.let { toolCalls ->
                toolCalls.forEach { toolCall ->
                    accumulatedToolCalls.add(toolCall)
                    emit(StrategyEvent.ToolCallDetected(toolCall, toolCall.id))
                }
            }

            // Accumulate token usage (typically sent in final chunk)
            parsed.tokensUsed?.let { totalTokens += it }

            // Completion
            if (parsed.finishReason != null) {
                logger.debug {
                    "[${context.conversationId}] Stream complete: " +
                            "finish_reason=${parsed.finishReason}, " +
                            "tool_calls=${accumulatedToolCalls.size}, " +
                            "reasoning_length=${accumulatedReasoning.length}"
                }

                // If no tool calls, this is the final response - emit FinalResponse
                if (accumulatedToolCalls.isEmpty() && accumulatedContent.isNotEmpty()) {
                    emit(
                        StrategyEvent.FinalResponse(
                            content = accumulatedContent.toString(),
                            tokensUsed = totalTokens,
                            metadata = mapOf(
                                "finish_reason" to parsed.finishReason,
                                "model" to "qwen-max",
                                "reasoning_length" to accumulatedReasoning.length,
                                "streaming" to true
                            )
                        )
                    )
                }

                emit(
                    StrategyEvent.IterationComplete(
                        tokensUsed = totalTokens,
                        shouldContinue = accumulatedToolCalls.isNotEmpty(),
                        metadata = mapOf(
                            "finish_reason" to parsed.finishReason,
                            "model" to "qwen-max",
                            "reasoning_length" to accumulatedReasoning.length,
                            "content_length" to accumulatedContent.length
                        )
                    )
                )
            }
        }
    }

    private suspend fun FlowCollector<StrategyEvent>.executeSynchronousIteration(
        messages: List<Message>,
        tools: List<*>,
        config: RequestConfig
    ) {
        val request = provider.buildRequest(messages, tools, config)
        val response = provider.chat(request)
        val message = provider.extractMessage(response)

        // Log reasoning content if available
        message.reasoningContent?.let { reasoning ->
            logger.debug { "Reasoning content (length=${reasoning.length}): ${reasoning.take(200)}..." }

            if (Environment.LOOP_THINKING_SHOW_REASONING) {
                emit(
                    StrategyEvent.ReasoningChunk(
                        content = reasoning,
                        metadata = mapOf(
                            "model" to "qwen-max",
                            "thinking_enabled" to true
                        )
                    )
                )
            }
        }

        if (message.toolCalls != null && message.toolCalls.isNotEmpty()) {
            emit(
                StrategyEvent.ToolCallsComplete(
                    toolCalls = message.toolCalls,
                    assistantContent = message.content
                )
            )
            emit(
                StrategyEvent.IterationComplete(
                    tokensUsed = message.tokensUsed,
                    shouldContinue = true,
                    metadata = mapOf(
                        "model" to "qwen-max",
                        "has_reasoning" to (message.reasoningContent != null)
                    )
                )
            )
        } else {
            emit(
                StrategyEvent.FinalResponse(
                    content = message.content ?: "",
                    tokensUsed = message.tokensUsed,
                    metadata = mapOf(
                        "model" to "qwen-max",
                        "has_reasoning" to (message.reasoningContent != null)
                    )
                )
            )
            emit(
                StrategyEvent.IterationComplete(
                    tokensUsed = message.tokensUsed,
                    shouldContinue = false
                )
            )
        }
    }

    override fun getStrategyMetadata(): StrategyMetadata {
        return StrategyMetadata(
            name = "Qwen Single Thinking",
            strategyType = ModelStrategy.QWEN_SINGLE_THINKING,
            supportsReasoningStream = true,  // ✓
            supportsSynchronous = true,
            description = "Qwen thinking model (qwen-max) with streaming reasoning traces"
        )
    }
}
