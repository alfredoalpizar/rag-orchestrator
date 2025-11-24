package com.alfredoalpizar.rag.service.orchestrator.strategy.impl

import com.alfredoalpizar.rag.client.qwen.QwenTool
import com.alfredoalpizar.rag.config.LoopProperties
import com.alfredoalpizar.rag.config.LoopProperties.ModelStrategy
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
 * Qwen Single Instruct Strategy.
 *
 * Uses Qwen instruct model (qwen-plus) for all iterations.
 * Fast, efficient execution without reasoning traces.
 * Good for production workloads where speed is critical.
 *
 * Activated when: loop.model-strategy = qwen_single_instruct
 */
@Component
@ConditionalOnProperty(
    prefix = "loop",
    name = ["model-strategy"],
    havingValue = "qwen_single_instruct"
)
class QwenSingleInstructStrategy(
    private val provider: QwenModelProvider,
    private val properties: LoopProperties
) : ModelStrategyExecutor {

    private val logger = KotlinLogging.logger {}

    init {
        logger.info {
            """
            ╔════════════════════════════════════════════════════════╗
            ║  STRATEGY INITIALIZED: Qwen Single Instruct            ║
            ║  Provider: Qwen API                                    ║
            ║  Model: qwen-plus (instruct)                           ║
            ║  Reasoning Stream: No                                  ║
            ║  Description: Fast instruct model for tool execution   ║
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
                    "- Strategy: Qwen Single Instruct"
        }

        val requestConfig = RequestConfig(
            streamingEnabled = iterationContext.streamingMode == StreamingMode.PROGRESSIVE,
            temperature = properties.temperature,
            maxTokens = properties.maxTokens,
            extraParams = mapOf(
                "useInstructModel" to true,
                "enableThinking" to false  // No thinking for instruct model
            )
        )

        if (iterationContext.streamingMode == StreamingMode.PROGRESSIVE) {
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
        val request = provider.buildRequest(messages, tools.map { it as QwenTool }, config)

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

            // Handle tool calls
            parsed.toolCalls?.let { toolCalls ->
                toolCalls.forEach { toolCall ->
                    accumulatedToolCalls.add(toolCall)
                    emit(StrategyEvent.ToolCallDetected(toolCall, toolCall.id))
                }
            }

            // Completion
            if (parsed.finishReason != null) {
                logger.debug {
                    "[${context.conversationId}] Stream complete: " +
                            "finish_reason=${parsed.finishReason}, " +
                            "tool_calls=${accumulatedToolCalls.size}"
                }

                emit(
                    StrategyEvent.IterationComplete(
                        tokensUsed = totalTokens,
                        shouldContinue = accumulatedToolCalls.isNotEmpty(),
                        metadata = mapOf(
                            "finish_reason" to parsed.finishReason,
                            "model" to "qwen-plus",
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
        val request = provider.buildRequest(messages, tools.map { it as QwenTool }, config)
        val response = provider.chat(request)
        val message = provider.extractMessage(response)

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
                    metadata = mapOf("model" to "qwen-plus")
                )
            )
        } else {
            emit(
                StrategyEvent.FinalResponse(
                    content = message.content ?: "",
                    tokensUsed = message.tokensUsed,
                    metadata = mapOf("model" to "qwen-plus")
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
            name = "Qwen Single Instruct",
            strategyType = ModelStrategy.QWEN_SINGLE_INSTRUCT,
            supportsReasoningStream = false,
            supportsSynchronous = true,
            description = "Fast Qwen instruct model (qwen-plus) for efficient tool execution"
        )
    }
}
