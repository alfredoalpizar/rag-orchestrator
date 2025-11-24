package com.alfredoalpizar.rag.service.orchestrator.strategy.impl

import com.alfredoalpizar.rag.client.deepseek.DeepSeekTool
import com.alfredoalpizar.rag.config.LoopProperties
import com.alfredoalpizar.rag.config.LoopProperties.ModelStrategy
import com.alfredoalpizar.rag.model.domain.Message
import com.alfredoalpizar.rag.service.orchestrator.provider.DeepSeekModelProvider
import com.alfredoalpizar.rag.service.orchestrator.provider.RequestConfig
import com.alfredoalpizar.rag.service.orchestrator.strategy.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * DeepSeek Single Strategy - Backward compatible implementation.
 *
 * Uses a single DeepSeek model (chat or reasoner) for all iterations.
 * This strategy replicates the original OrchestratorService behavior.
 *
 * Activated when: loop.model-strategy = deepseek_single
 */
@Component
@ConditionalOnProperty(
    prefix = "loop",
    name = ["model-strategy"],
    havingValue = "deepseek_single",
    matchIfMissing = true  // Default strategy
)
class DeepSeekSingleStrategy(
    private val provider: DeepSeekModelProvider,
    private val properties: LoopProperties
) : ModelStrategyExecutor {

    private val logger = KotlinLogging.logger {}

    init {
        val model = if (properties.useReasoningModel) "deepseek-reasoner" else "deepseek-chat"
        logger.info {
            """
            ╔════════════════════════════════════════════════════════╗
            ║  STRATEGY INITIALIZED: DeepSeek Single                 ║
            ║  Provider: DeepSeek API                                ║
            ║  Model: $model                                ║
            ║  Reasoning Stream: No                                  ║
            ║  Description: Single DeepSeek model for all iterations ║
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
                    "- Strategy: DeepSeek Single"
        }

        val requestConfig = RequestConfig(
            streamingEnabled = iterationContext.streamingMode == StreamingMode.PROGRESSIVE,
            temperature = properties.temperature,
            maxTokens = properties.maxTokens,
            extraParams = mapOf("useReasoningModel" to properties.useReasoningModel)
        )

        if (iterationContext.streamingMode == StreamingMode.PROGRESSIVE) {
            // STREAMING MODE - Progressive chunks for chat UI
            executeStreamingIteration(messages, tools, requestConfig, iterationContext)
        } else {
            // SYNCHRONOUS MODE - Final results only
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
        var accumulatedToolCalls = mutableListOf<com.alfredoalpizar.rag.model.domain.ToolCall>()
        var totalTokens = 0

        provider.chatStream(request).collect { chunk ->
            val parsed = provider.extractStreamChunk(chunk)

            // Emit content chunks
            parsed.contentDelta?.let { delta ->
                accumulatedContent.append(delta)
                emit(StrategyEvent.ContentChunk(delta))
            }

            // Handle tool calls (progressive)
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
                            "tool_calls=${accumulatedToolCalls.size}"
                }

                // If no tool calls, this is the final response - emit FinalResponse
                if (accumulatedToolCalls.isEmpty() && accumulatedContent.isNotEmpty()) {
                    emit(
                        StrategyEvent.FinalResponse(
                            content = accumulatedContent.toString(),
                            tokensUsed = totalTokens,
                            metadata = mapOf(
                                "finish_reason" to parsed.finishReason,
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

        if (message.toolCalls != null && message.toolCalls.isNotEmpty()) {
            // Tool calls detected - continue loop
            emit(
                StrategyEvent.ToolCallsComplete(
                    toolCalls = message.toolCalls,
                    assistantContent = message.content
                )
            )
            emit(
                StrategyEvent.IterationComplete(
                    tokensUsed = message.tokensUsed,
                    shouldContinue = true
                )
            )
        } else {
            // Final response - end loop
            emit(
                StrategyEvent.FinalResponse(
                    content = message.content ?: "",
                    tokensUsed = message.tokensUsed
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
            name = "DeepSeek Single",
            strategyType = ModelStrategy.DEEPSEEK_SINGLE,
            supportsReasoningStream = false,
            supportsSynchronous = true,
            description = "Single DeepSeek model (deepseek-chat or deepseek-reasoner)"
        )
    }
}
