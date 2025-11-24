package com.alfredoalpizar.rag.service.orchestrator

import com.alfredoalpizar.rag.config.LoopProperties
import com.alfredoalpizar.rag.model.domain.*
import com.alfredoalpizar.rag.model.response.StreamEvent
import com.alfredoalpizar.rag.service.context.ContextManager
import com.alfredoalpizar.rag.service.finalizer.FinalizerStrategy
import com.alfredoalpizar.rag.service.orchestrator.strategy.*
import com.alfredoalpizar.rag.service.tool.ToolRegistry
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Orchestrator Service - Model-agnostic agentic loop orchestration.
 *
 * Uses the Strategy Pattern to support multiple model implementations:
 * - DeepSeek (single model)
 * - Qwen (thinking, instruct, or hybrid staged)
 * - Future: Claude, GPT-4, etc.
 *
 * Provides both streaming (SSE for chat UI) and synchronous (internal services) APIs.
 */
@Service
class OrchestratorService(
    private val contextManager: ContextManager,
    private val toolRegistry: ToolRegistry,
    private val strategyExecutor: ModelStrategyExecutor,  // Injected based on configuration
    private val finalizerStrategy: FinalizerStrategy,
    private val properties: LoopProperties,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    init {
        val metadata = strategyExecutor.getStrategyMetadata()
        logger.info {
            """
            ╔════════════════════════════════════════════════════════╗
            ║  ORCHESTRATOR SERVICE INITIALIZED                      ║
            ║  Active Strategy: ${metadata.name.padEnd(35)}║
            ║  Strategy Type: ${metadata.strategyType.name.padEnd(37)}║
            ║  Reasoning Stream: ${if (metadata.supportsReasoningStream) "YES ✓" else "NO  ✗".padEnd(39)}║
            ║  ${metadata.description.padEnd(52)}║
            ╚════════════════════════════════════════════════════════╝
            """.trimIndent()
        }
    }

    /**
     * Process a user message with streaming support (for chat UI with SSE).
     *
     * Emits progressive StreamEvent updates as the agentic loop executes.
     * Supports reasoning traces for models that provide them.
     */
    suspend fun processMessageStream(
        conversationId: String,
        userMessage: String
    ): Flow<StreamEvent> = flow {

        val metadata = strategyExecutor.getStrategyMetadata()
        logger.info {
            "Processing message (stream) [conversation=$conversationId, strategy=${metadata.name}]"
        }

        try {
            // Step 1: Load conversation
            emit(StreamEvent.StatusUpdate(
                conversationId = conversationId,
                status = "Loading conversation..."
            ))

            var context = contextManager.loadConversation(conversationId)

            // Step 2: Add user message
            emit(StreamEvent.StatusUpdate(
                conversationId = conversationId,
                status = "Adding user message..."
            ))

            val userMsg = Message(role = MessageRole.USER, content = userMessage)
            context = contextManager.addMessage(conversationId, userMsg)

            // Step 3: Initial RAG search
            emit(StreamEvent.StatusUpdate(
                conversationId = conversationId,
                status = "Performing initial knowledge search..."
            ))

            val ragResults = performRAGSearch(userMessage)

            // Build message list with conversation history
            val messages = buildMessageList(context, ragResults)

            // Step 4: Tool-calling loop with strategy
            var iteration = 0
            var totalTokens = 0
            var continueLoop = true
            var finalContent = ""

            while (continueLoop && iteration < properties.maxIterations) {
                iteration++

                emit(StreamEvent.StatusUpdate(
                    conversationId = conversationId,
                    status = "Iteration $iteration of ${properties.maxIterations}..."
                ))

                logger.debug {
                    "[${metadata.name}] Iteration $iteration/${properties.maxIterations}"
                }

                // Execute strategy iteration (STREAMING MODE)
                strategyExecutor.executeIteration(
                    messages = messages,
                    tools = toolRegistry.getToolDefinitions(),
                    iterationContext = IterationContext(
                        conversationId = conversationId,
                        iteration = iteration,
                        maxIterations = properties.maxIterations,
                        streamingMode = StreamingMode.PROGRESSIVE  // Progressive chunks
                    )
                ).collect { strategyEvent ->

                    // Transform StrategyEvent → StreamEvent
                    when (strategyEvent) {
                        is StrategyEvent.ReasoningChunk -> {
                            // Only some strategies support reasoning
                            if (properties.streaming.showReasoningTraces) {
                                emit(StreamEvent.ReasoningTrace(
                                    conversationId = conversationId,
                                    content = strategyEvent.content,
                                    stage = com.alfredoalpizar.rag.model.response.ReasoningStage.PLANNING,
                                    metadata = strategyEvent.metadata
                                ))
                            }
                        }

                        is StrategyEvent.ContentChunk -> {
                            emit(StreamEvent.ResponseChunk(
                                conversationId = conversationId,
                                content = strategyEvent.content
                            ))
                            finalContent += strategyEvent.content
                        }

                        is StrategyEvent.ToolCallDetected -> {
                            // Single tool call detected (streaming)
                            emit(StreamEvent.ToolCallStart(
                                conversationId = conversationId,
                                toolName = strategyEvent.toolCall.function.name,
                                toolCallId = strategyEvent.toolCallId,
                                arguments = parseArguments(strategyEvent.toolCall.function.arguments)
                            ))

                            // Execute tool
                            val result = toolRegistry.executeTool(strategyEvent.toolCall)

                            emit(StreamEvent.ToolCallResult(
                                conversationId = conversationId,
                                toolName = result.toolName,
                                toolCallId = result.toolCallId,
                                result = result.result,
                                success = result.success
                            ))

                            // Add tool result to messages
                            messages.add(Message(
                                role = MessageRole.TOOL,
                                content = result.result,
                                toolCallId = result.toolCallId
                            ))

                            context.conversation.toolCallsCount++
                        }

                        is StrategyEvent.ToolCallsComplete -> {
                            // Multiple tool calls complete (non-streaming)
                            // Add assistant message with tool calls
                            messages.add(Message(
                                role = MessageRole.ASSISTANT,
                                content = strategyEvent.assistantContent ?: "",
                                toolCalls = strategyEvent.toolCalls
                            ))

                            // Execute all tools
                            for (toolCall in strategyEvent.toolCalls) {
                                emit(StreamEvent.ToolCallStart(
                                    conversationId = conversationId,
                                    toolName = toolCall.function.name,
                                    toolCallId = toolCall.id,
                                    arguments = parseArguments(toolCall.function.arguments)
                                ))

                                val result = toolRegistry.executeTool(toolCall)

                                emit(StreamEvent.ToolCallResult(
                                    conversationId = conversationId,
                                    toolName = result.toolName,
                                    toolCallId = result.toolCallId,
                                    result = result.result,
                                    success = result.success
                                ))

                                messages.add(Message(
                                    role = MessageRole.TOOL,
                                    content = result.result,
                                    toolCallId = result.toolCallId
                                ))

                                context.conversation.toolCallsCount++
                            }
                        }

                        is StrategyEvent.FinalResponse -> {
                            finalContent = strategyEvent.content

                            // Save assistant message
                            contextManager.addMessage(
                                conversationId,
                                Message(role = MessageRole.ASSISTANT, content = finalContent)
                            )

                            // Apply finalizer
                            val formattedResponse = finalizerStrategy.format(
                                finalContent,
                                mapOf(
                                    "iterations" to iteration,
                                    "tokens" to (totalTokens + strategyEvent.tokensUsed),
                                    "toolCalls" to context.conversation.toolCallsCount
                                )
                            )

                            emit(StreamEvent.ResponseChunk(
                                conversationId = conversationId,
                                content = formattedResponse
                            ))
                        }

                        is StrategyEvent.StatusUpdate -> {
                            emit(StreamEvent.StatusUpdate(
                                conversationId = conversationId,
                                status = strategyEvent.status,
                                phase = strategyEvent.phase
                            ))
                        }

                        is StrategyEvent.IterationComplete -> {
                            totalTokens += strategyEvent.tokensUsed
                            continueLoop = strategyEvent.shouldContinue

                            logger.debug {
                                "Iteration $iteration complete: " +
                                        "tokens=${strategyEvent.tokensUsed}, " +
                                        "continue=$continueLoop"
                            }
                        }
                    }
                }
            }

            // Step 5: Update final stats and complete
            context.conversation.totalTokens += totalTokens
            contextManager.saveConversation(context)

            logger.info {
                "Completed processing (stream) [conversation=$conversationId, " +
                        "strategy=${metadata.name}, iterations=$iteration, tokens=$totalTokens]"
            }

            emit(StreamEvent.Completed(
                conversationId = conversationId,
                iterationsUsed = iteration,
                tokensUsed = totalTokens
            ))

        } catch (e: Exception) {
            logger.error(e) { "Error processing message for conversation $conversationId" }

            emit(StreamEvent.Error(
                conversationId = conversationId,
                error = e.message ?: "Unknown error",
                details = e.stackTraceToString()
            ))
        }
    }

    /**
     * Process a user message synchronously (for automated internal services).
     *
     * Returns final result directly without streaming.
     * More efficient for batch/automated operations.
     */
    suspend fun processMessageSync(
        conversationId: String,
        userMessage: String
    ): SyncResult {

        val metadata = strategyExecutor.getStrategyMetadata()
        logger.info {
            "Processing message (sync) [conversation=$conversationId, strategy=${metadata.name}]"
        }

        try {
            // Load conversation
            var context = contextManager.loadConversation(conversationId)
            context = contextManager.addMessage(conversationId, Message(MessageRole.USER, userMessage))

            // Initial RAG
            val ragResults = performRAGSearch(userMessage)
            val messages = buildMessageList(context, ragResults)

            // Tool-calling loop
            var iteration = 0
            var totalTokens = 0
            var continueLoop = true
            var finalContent = ""

            while (continueLoop && iteration < properties.maxIterations) {
                iteration++

                logger.debug { "[${metadata.name}] Iteration $iteration/${properties.maxIterations}" }

                // Execute strategy iteration (SYNCHRONOUS MODE)
                strategyExecutor.executeIteration(
                    messages = messages,
                    tools = toolRegistry.getToolDefinitions(),
                    iterationContext = IterationContext(
                        conversationId = conversationId,
                        iteration = iteration,
                        maxIterations = properties.maxIterations,
                        streamingMode = StreamingMode.FINAL_ONLY  // Only final results
                    )
                ).collect { strategyEvent ->

                    when (strategyEvent) {
                        is StrategyEvent.ToolCallsComplete -> {
                            messages.add(Message(
                                role = MessageRole.ASSISTANT,
                                content = strategyEvent.assistantContent ?: "",
                                toolCalls = strategyEvent.toolCalls
                            ))

                            strategyEvent.toolCalls.forEach { toolCall ->
                                val result = toolRegistry.executeTool(toolCall)
                                messages.add(Message(
                                    role = MessageRole.TOOL,
                                    content = result.result,
                                    toolCallId = toolCall.id
                                ))
                                context.conversation.toolCallsCount++
                            }
                        }

                        is StrategyEvent.FinalResponse -> {
                            finalContent = strategyEvent.content
                            contextManager.addMessage(
                                conversationId,
                                Message(role = MessageRole.ASSISTANT, content = finalContent)
                            )
                        }

                        is StrategyEvent.IterationComplete -> {
                            totalTokens += strategyEvent.tokensUsed
                            continueLoop = strategyEvent.shouldContinue
                        }

                        // Ignore progressive chunks in sync mode
                        is StrategyEvent.ContentChunk,
                        is StrategyEvent.ReasoningChunk,
                        is StrategyEvent.ToolCallDetected,
                        is StrategyEvent.StatusUpdate -> { /* ignore */ }
                    }
                }
            }

            // Save conversation
            context.conversation.totalTokens += totalTokens
            contextManager.saveConversation(context)

            logger.info {
                "Completed processing (sync) [conversation=$conversationId, " +
                        "strategy=${metadata.name}, iterations=$iteration, tokens=$totalTokens]"
            }

            return SyncResult(
                content = finalContent,
                iterationsUsed = iteration,
                tokensUsed = totalTokens,
                conversationId = conversationId
            )

        } catch (e: Exception) {
            logger.error(e) { "Error processing message (sync) for conversation $conversationId" }
            throw e
        }
    }

    // ===== Private Helper Methods =====

    private fun buildMessageList(context: com.alfredoalpizar.rag.model.domain.ConversationContext, ragResults: String): MutableList<Message> {
        val messages = mutableListOf<Message>()
        messages.addAll(context.messages)

        // Add RAG results as system message if available
        if (ragResults.isNotBlank()) {
            messages.add(Message(
                role = MessageRole.SYSTEM,
                content = "Knowledge base results:\n$ragResults"
            ))
        }

        return messages
    }

    private suspend fun performRAGSearch(query: String): String {
        return try {
            val ragTool = toolRegistry.getTool("rag_search")
                ?: return ""

            val result = ragTool.execute(mapOf("query" to query))

            if (result.success) result.result else ""
        } catch (e: Exception) {
            logger.error(e) { "Error performing initial RAG search" }
            ""
        }
    }

    private fun parseArguments(json: String): Map<String, Any> {
        return try {
            objectMapper.readValue(json, object : TypeReference<Map<String, Any>>() {})
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse arguments: $json" }
            emptyMap()
        }
    }
}

/**
 * Synchronous result for non-streaming operations.
 */
data class SyncResult(
    val content: String,
    val iterationsUsed: Int,
    val tokensUsed: Int,
    val conversationId: String
)
