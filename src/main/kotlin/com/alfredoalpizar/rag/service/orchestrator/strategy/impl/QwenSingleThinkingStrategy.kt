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
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    // Debug output directory for raw streaming responses
    private val debugOutputDir = File("debug-streams").apply { mkdirs() }
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")

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

        // DIAGNOSTIC: Track reasoning_content chunk count to see if it streams incrementally
        var reasoningChunkCount = 0

        // Incremental thinking tag parser state
        val thinkingParser = ThinkingTagParser()

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

            // Emit REASONING chunks from reasoning_content field (native Qwen reasoning)
            parsed.reasoningDelta?.let { reasoningDelta ->
                reasoningChunkCount++
                // DIAGNOSTIC: Log chunk number and size to see if reasoning_content streams incrementally
                logger.info {
                    "[${context.conversationId}] REASONING_CONTENT chunk #$reasoningChunkCount: " +
                    "${reasoningDelta.length} chars, first50=${reasoningDelta.take(50).replace("\n", "\\n")}"
                }
                accumulatedReasoning.append(reasoningDelta)
                logger.trace { "Reasoning trace (native): $reasoningDelta" }

                if (Environment.LOOP_THINKING_SHOW_REASONING) {
                    emit(
                        StrategyEvent.ReasoningChunk(
                            content = reasoningDelta,
                            metadata = mapOf(
                                "model" to "qwen-max",
                                "thinking_enabled" to true,
                                "source" to "reasoning_content"
                            )
                        )
                    )
                }
            }

            // Process content through incremental thinking tag parser
            // This separates <think>...</think> content from the answer in real-time
            parsed.contentDelta?.let { delta ->
                accumulatedContent.append(delta)

                // DIAGNOSTIC: Log accumulated content periodically to see when <think> tags appear
                val currentLength = accumulatedContent.length
                if (currentLength % 500 < delta.length || currentLength < 50) {  // Every ~500 chars or first 50
                    val content = accumulatedContent.toString()
                    val hasOpenTag = content.contains("<think>")
                    val hasCloseTag = content.contains("</think>")
                    logger.info {
                        "[${context.conversationId}] ACCUMULATED ($currentLength chars): " +
                        "hasOpenTag=$hasOpenTag, hasCloseTag=$hasCloseTag, " +
                        "first80=${content.take(80).replace("\n", "\\n")}"
                    }
                }

                // Parse and emit separated chunks via ThinkingTagParser
                val parsedChunks = thinkingParser.processChunk(delta)

                // DEBUG: Log what the parser produced
                if (parsedChunks.isNotEmpty()) {
                    logger.debug {
                        "[${context.conversationId}] ThinkingTagParser: ${parsedChunks.size} chunks from delta (${delta.length} chars): " +
                        parsedChunks.map { (c, isThinking) ->
                            "${if (isThinking) "THINKING" else "CONTENT"}(${c.length} chars)"
                        }.joinToString(", ")
                    }
                }

                for ((content, isThinking) in parsedChunks) {
                    if (content.isNotEmpty()) {
                        if (isThinking) {
                            accumulatedReasoning.append(content)
                            if (Environment.LOOP_THINKING_SHOW_REASONING) {
                                logger.info { "[${context.conversationId}] >>> EMIT ReasoningChunk (${content.length} chars): ${content.take(60).replace("\n", "\\n")}..." }
                                emit(
                                    StrategyEvent.ReasoningChunk(
                                        content = content,
                                        metadata = mapOf(
                                            "model" to "qwen-max",
                                            "source" to "think_tags"
                                        )
                                    )
                                )
                            }
                        } else {
                            logger.info { "[${context.conversationId}] >>> EMIT ContentChunk (${content.length} chars): ${content.take(60).replace("\n", "\\n")}..." }
                            emit(StrategyEvent.ContentChunk(content))
                        }
                    }
                }
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
                // Flush any remaining buffered content from the parser
                thinkingParser.flush()?.let { (content, isThinking) ->
                    if (content.isNotEmpty()) {
                        if (isThinking) {
                            accumulatedReasoning.append(content)
                            if (Environment.LOOP_THINKING_SHOW_REASONING) {
                                emit(StrategyEvent.ReasoningChunk(
                                    content = content,
                                    metadata = mapOf("model" to "qwen-max", "source" to "think_tags_flush")
                                ))
                            }
                        } else {
                            emit(StrategyEvent.ContentChunk(content))
                        }
                    }
                }

                val fullContent = accumulatedContent.toString()

                // Extract thinking from <think>...</think> tags (for logging/metadata)
                val thinkingContent = if (hasThinkingTags(fullContent)) {
                    extractThinking(fullContent)
                } else {
                    ""
                }

                // Remove thinking tags to get clean answer (for logging/metadata)
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

                // === DEBUG: Log full accumulated response for this iteration ===
                logger.info {
                    """
                    |
                    |╔══════════════════════════════════════════════════════════════════════════════╗
                    |║ QWEN ITERATION ${context.iteration} COMPLETE - FULL RESPONSE
                    |╠══════════════════════════════════════════════════════════════════════════════╣
                    |║ Conversation: ${context.conversationId}
                    |║ Finish Reason: ${parsed.finishReason}
                    |║ Total Tokens: $totalTokens
                    |╠══════════════════════════════════════════════════════════════════════════════╣
                    |║ REASONING CONTENT (reasoning_content field, ${accumulatedReasoning.length} chars):
                    |╟──────────────────────────────────────────────────────────────────────────────╢
                    |${accumulatedReasoning.toString().lines().joinToString("\n") { "║ $it" }}
                    |╠══════════════════════════════════════════════════════════════════════════════╣
                    |║ THINKING TAGS (<think>...</think> extracted, ${thinkingContent.length} chars):
                    |╟──────────────────────────────────────────────────────────────────────────────╢
                    |${thinkingContent.lines().joinToString("\n") { "║ $it" }}
                    |╠══════════════════════════════════════════════════════════════════════════════╣
                    |║ FINAL ANSWER (clean content, ${cleanAnswer.length} chars):
                    |╟──────────────────────────────────────────────────────────────────────────────╢
                    |${cleanAnswer.lines().joinToString("\n") { "║ $it" }}
                    |╠══════════════════════════════════════════════════════════════════════════════╣
                    |║ TOOL CALLS (${accumulatedToolCalls.size}):
                    |╟──────────────────────────────────────────────────────────────────────────────╢
                    |${if (accumulatedToolCalls.isEmpty()) "║ (none)" else accumulatedToolCalls.joinToString("\n") { "║ - ${it.function.name}(${it.function.arguments})" }}
                    |╚══════════════════════════════════════════════════════════════════════════════╝
                    """.trimMargin()
                }

                // === DEBUG: Save raw aggregated response to file ===
                try {
                    val timestamp = LocalDateTime.now().format(dateFormatter)
                    val filename = "${timestamp}_${context.conversationId}_iter${context.iteration}.txt"
                    val debugFile = File(debugOutputDir, filename)
                    debugFile.writeText("""
                        |=== RAW STREAM DEBUG ===
                        |Timestamp: $timestamp
                        |Conversation: ${context.conversationId}
                        |Iteration: ${context.iteration}
                        |Finish Reason: ${parsed.finishReason}
                        |Total Tokens: $totalTokens
                        |
                        |=== REASONING_CONTENT STREAMING ANALYSIS ===
                        |Total reasoning_content chunks received: $reasoningChunkCount
                        |If chunks > 1: reasoning_content IS streaming incrementally (use it as primary source)
                        |If chunks == 1: reasoning_content arrives all at once (keep using </think> parser)
                        |
                        |=== RAW ACCUMULATED CONTENT (${fullContent.length} chars) ===
                        |$fullContent
                        |
                        |=== REASONING_CONTENT FIELD (${accumulatedReasoning.length} chars) ===
                        |${accumulatedReasoning}
                        |
                        |=== EXTRACTED THINKING via </think> parser (${thinkingContent.length} chars) ===
                        |$thinkingContent
                        |
                        |=== CLEAN ANSWER (${cleanAnswer.length} chars) ===
                        |$cleanAnswer
                        |
                        |=== TOOL CALLS (${accumulatedToolCalls.size}) ===
                        |${if (accumulatedToolCalls.isEmpty()) "(none)" else accumulatedToolCalls.joinToString("\n") { "- ${it.function.name}(${it.function.arguments})" }}
                    """.trimMargin())
                    logger.info { "[${context.conversationId}] Debug file saved: ${debugFile.absolutePath}" }
                } catch (e: Exception) {
                    logger.warn(e) { "[${context.conversationId}] Failed to save debug file" }
                }

                // Note: Thinking content is now streamed incrementally via ThinkingTagParser
                // No need to emit it again here - it was already sent as ReasoningChunk events

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

/**
 * Incremental parser for thinking content in streaming responses.
 *
 * Fireworks AI Qwen thinking model sends content in this format:
 * - Thinking content (raw text, no opening tag)
 * - </think> closing tag
 * - Answer content
 *
 * This parser starts in "thinking mode" and watches for </think> to switch to answer mode.
 * Handles the case where the tag can be split across chunks:
 * - Chunk 1: "Let me think about this...</th"
 * - Chunk 2: "ink>Here's the answer"
 *
 * Returns pairs of (content, isThinking) for each parsed segment.
 */
private class ThinkingTagParser {
    // START in thinking mode - Fireworks sends thinking first, no <think> opening tag
    private var insideThinkingTag = true
    private val pendingBuffer = StringBuilder()

    // Potential partial tag prefixes to watch for (only </think> closing tag)
    private val closeTagPrefixes = listOf("<", "</", "</t", "</th", "</thi", "</thin", "</think")

    /**
     * Process a content chunk and return separated (content, isThinking) pairs.
     */
    fun processChunk(chunk: String): List<Pair<String, Boolean>> {
        pendingBuffer.append(chunk)
        val results = mutableListOf<Pair<String, Boolean>>()

        while (pendingBuffer.isNotEmpty()) {
            if (insideThinkingTag) {
                // In thinking mode - looking for </think> to end thinking
                val thinkEnd = pendingBuffer.indexOf("</think>")
                if (thinkEnd != -1) {
                    // Found closing tag - emit thinking content before it
                    if (thinkEnd > 0) {
                        results.add(pendingBuffer.substring(0, thinkEnd) to true)  // THINKING
                    }
                    pendingBuffer.delete(0, thinkEnd + 8) // Remove "</think>"
                    insideThinkingTag = false
                } else {
                    // No complete tag - check if buffer ends with partial tag
                    val potentialPartial = findPartialCloseTag(pendingBuffer.toString())
                    if (potentialPartial != null) {
                        // Keep partial tag in buffer, emit everything before it as thinking
                        val safeEnd = pendingBuffer.length - potentialPartial.length
                        if (safeEnd > 0) {
                            results.add(pendingBuffer.substring(0, safeEnd) to true)  // THINKING
                            pendingBuffer.delete(0, safeEnd)
                        }
                        break // Wait for more data
                    } else {
                        // No partial tag - emit all as thinking content
                        results.add(pendingBuffer.toString() to true)  // THINKING
                        pendingBuffer.clear()
                        break
                    }
                }
            } else {
                // After </think> - everything is answer content
                results.add(pendingBuffer.toString() to false)  // CONTENT (answer)
                pendingBuffer.clear()
                break
            }
        }

        return results
    }

    /**
     * Check if the string ends with any partial </think> tag prefix.
     */
    private fun findPartialCloseTag(s: String): String? {
        for (prefix in closeTagPrefixes.sortedByDescending { it.length }) {
            if (s.endsWith(prefix) && prefix != "</think>") {
                return prefix
            }
        }
        return null
    }

    /**
     * Flush any remaining buffered content (call at end of stream).
     */
    fun flush(): Pair<String, Boolean>? {
        if (pendingBuffer.isNotEmpty()) {
            val content = pendingBuffer.toString()
            pendingBuffer.clear()
            return content to insideThinkingTag
        }
        return null
    }
}
