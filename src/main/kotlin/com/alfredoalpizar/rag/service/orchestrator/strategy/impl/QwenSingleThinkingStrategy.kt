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
import org.springframework.stereotype.Component

/**
 * Qwen Single Thinking Strategy - DEFAULT STRATEGY.
 *
 * Uses Qwen thinking model (qwen-max) for all iterations.
 * Supports reasoning traces and deep thinking.
 * Best for complex reasoning tasks where quality > speed.
 *
 * Selection: This is the default. Set Environment.LOOP_MODEL_STRATEGY = "qwen_single_thinking"
 * Requires: FIREWORKS_API_KEY to be configured (Qwen via Fireworks AI)
 */
@Component
class QwenSingleThinkingStrategy(
    private val provider: QwenModelProvider
) : ModelStrategyExecutor {

    private val logger = KotlinLogging.logger {}

    // Regex to extract <think>...</think> tags from Fireworks AI Qwen thinking model
    private val thinkTagRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)

    // Regex for content ending with </think> (no opening tag) - Fireworks AI sometimes omits opening tag
    private val closeThinkTagRegex = Regex("^(.*?)</think>(.*)$", RegexOption.DOT_MATCHES_ALL)

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

            // DEBUG: Log every chunk to see what we're receiving
            logger.debug {
                "[${context.conversationId}] Stream chunk received: " +
                        "contentDelta=${parsed.contentDelta?.take(50)}, " +
                        "reasoningDelta=${parsed.reasoningDelta?.take(50)}, " +
                        "toolCalls=${parsed.toolCalls?.size ?: 0}, " +
                        "finishReason=${parsed.finishReason}, " +
                        "role=${parsed.role}, " +
                        "tokens=${parsed.tokensUsed}"
            }

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
                    logger.info { "[${context.conversationId}] Tool call detected: ${toolCall.function.name}" }
                    emit(StrategyEvent.ToolCallDetected(toolCall, toolCall.id))
                }
            }

            // Accumulate token usage (typically sent in final chunk)
            parsed.tokensUsed?.let { totalTokens += it }

            // Completion
            if (parsed.finishReason != null) {
                val fullContent = accumulatedContent.toString()

                // Extract thinking from <think>...</think> tags (Fireworks AI format)
                val thinkingContent = if (hasThinkingTags(fullContent)) {
                    extractThinking(fullContent)
                } else {
                    ""
                }

                // Remove thinking tags to get clean answer
                val cleanAnswer = if (hasThinkingTags(fullContent)) {
                    removeThinkingTags(fullContent)
                } else {
                    fullContent
                }

                logger.info {
                    "[${context.conversationId}] Stream complete: " +
                            "finish_reason=${parsed.finishReason}, " +
                            "full_content_length=${fullContent.length}, " +
                            "thinking_length=${thinkingContent.length}, " +
                            "clean_answer_length=${cleanAnswer.length}, " +
                            "tool_calls=${accumulatedToolCalls.size}, " +
                            "tokens=$totalTokens"
                }

                // Emit thinking traces if present and enabled
                if (thinkingContent.isNotEmpty() && Environment.LOOP_THINKING_SHOW_REASONING) {
                    logger.debug { "[${context.conversationId}] Emitting extracted thinking: ${thinkingContent.take(200)}..." }
                    emit(
                        StrategyEvent.ReasoningChunk(
                            content = thinkingContent,
                            metadata = mapOf(
                                "model" to "qwen-thinking",
                                "extracted_from" to "think_tags",
                                "source" to "fireworks_ai"
                            )
                        )
                    )
                }

                // If no tool calls, this is the final response - emit FinalResponse
                if (accumulatedToolCalls.isEmpty() && cleanAnswer.isNotEmpty()) {
                    logger.info { "[${context.conversationId}] Emitting final response: ${cleanAnswer.take(100)}..." }
                    emit(
                        StrategyEvent.FinalResponse(
                            content = cleanAnswer,  // Send clean answer without thinking tags
                            tokensUsed = totalTokens,
                            metadata = mapOf(
                                "finish_reason" to parsed.finishReason,
                                "model" to "qwen-thinking",
                                "has_thinking" to thinkingContent.isNotEmpty(),
                                "thinking_length" to thinkingContent.length,
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
                            "model" to "qwen-thinking",
                            "thinking_length" to thinkingContent.length,
                            "clean_answer_length" to cleanAnswer.length,
                            "full_content_length" to fullContent.length
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

        val fullContent = message.content ?: ""

        // Extract thinking from <think>...</think> tags (Fireworks AI format)
        val thinkingContent = if (hasThinkingTags(fullContent)) {
            extractThinking(fullContent)
        } else {
            message.reasoningContent ?: ""  // Fallback to reasoningContent if no tags
        }

        // Remove thinking tags to get clean answer
        val cleanAnswer = if (hasThinkingTags(fullContent)) {
            removeThinkingTags(fullContent)
        } else {
            fullContent
        }

        // Log thinking content if available
        if (thinkingContent.isNotEmpty()) {
            logger.debug { "Thinking content (length=${thinkingContent.length}): ${thinkingContent.take(200)}..." }

            if (Environment.LOOP_THINKING_SHOW_REASONING) {
                emit(
                    StrategyEvent.ReasoningChunk(
                        content = thinkingContent,
                        metadata = mapOf(
                            "model" to "qwen-thinking",
                            "extracted_from" to if (hasThinkingTags(fullContent)) "think_tags" else "reasoning_content",
                            "source" to "fireworks_ai"
                        )
                    )
                )
            }
        }

        if (message.toolCalls != null && message.toolCalls.isNotEmpty()) {
            emit(
                StrategyEvent.ToolCallsComplete(
                    toolCalls = message.toolCalls,
                    assistantContent = cleanAnswer  // Use clean answer without thinking tags
                )
            )
            emit(
                StrategyEvent.IterationComplete(
                    tokensUsed = message.tokensUsed,
                    shouldContinue = true,
                    metadata = mapOf(
                        "model" to "qwen-thinking",
                        "has_thinking" to thinkingContent.isNotEmpty(),
                        "thinking_length" to thinkingContent.length
                    )
                )
            )
        } else {
            emit(
                StrategyEvent.FinalResponse(
                    content = cleanAnswer,  // Send clean answer without thinking tags
                    tokensUsed = message.tokensUsed,
                    metadata = mapOf(
                        "model" to "qwen-thinking",
                        "has_thinking" to thinkingContent.isNotEmpty(),
                        "thinking_length" to thinkingContent.length
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

    /**
     * Extract thinking traces from <think>...</think> tags.
     * Fireworks AI Qwen thinking model embeds reasoning inside content.
     * Also handles cases where only closing </think> tag is present.
     */
    private fun extractThinking(content: String): String {
        // Try full <think>...</think> tags first
        val matches = thinkTagRegex.findAll(content)
        val fromTags = matches.joinToString("\n\n") { it.groupValues[1].trim() }
        if (fromTags.isNotEmpty()) return fromTags

        // Fallback: content before </think> (no opening tag)
        closeThinkTagRegex.find(content)?.let { match ->
            return match.groupValues[1].trim()
        }

        return ""
    }

    /**
     * Remove <think>...</think> tags to get clean answer.
     * Also handles cases where only closing </think> tag is present.
     */
    private fun removeThinkingTags(content: String): String {
        // Check for </think> without opening tag first (return content after the tag)
        closeThinkTagRegex.find(content)?.let { match ->
            return match.groupValues[2].trim()
        }

        // Otherwise remove full <think>...</think> tags
        return content.replace(thinkTagRegex, "").trim()
    }

    /**
     * Check if content contains thinking tags.
     * Also checks for closing </think> tag without opening tag.
     */
    private fun hasThinkingTags(content: String): Boolean {
        return thinkTagRegex.containsMatchIn(content) || content.contains("</think>")
    }
}
