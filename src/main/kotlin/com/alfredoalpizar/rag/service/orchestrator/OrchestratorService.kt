package com.alfredoalpizar.rag.service.orchestrator

import com.alfredoalpizar.rag.config.Environment
import com.alfredoalpizar.rag.model.domain.*
import com.alfredoalpizar.rag.model.response.StreamEvent
import java.util.concurrent.ConcurrentHashMap
import com.alfredoalpizar.rag.service.context.ContextManager
import com.alfredoalpizar.rag.service.finalizer.FinalizerStrategy
import com.alfredoalpizar.rag.service.orchestrator.provider.QwenModelProvider
import com.alfredoalpizar.rag.service.orchestrator.provider.RequestConfig
import com.alfredoalpizar.rag.service.orchestrator.strategy.*
import com.alfredoalpizar.rag.service.tool.FinalizeAnswerTool
import com.alfredoalpizar.rag.service.tool.ToolRegistry
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Orchestrator Service - Model-agnostic agentic loop orchestration.
 *
 * Uses the Strategy Pattern to support multiple model implementations:
 * - DeepSeek (single model)
 * - Qwen (thinking or instruct)
 * - Future: Claude, GPT-4, etc.
 *
 * Provides both streaming (SSE for chat UI) and synchronous (internal services) APIs.
 */
@Service
class OrchestratorService(
    private val contextManager: ContextManager,
    private val toolRegistry: ToolRegistry,
    private val strategyFactory: StrategyFactory,
    private val finalizerStrategy: FinalizerStrategy,
    private val objectMapper: ObjectMapper,
    private val qwenProvider: QwenModelProvider  // For finalize_answer streaming
) {
    private val logger = KotlinLogging.logger {}

    // Strategy selected at runtime from Environment.kt
    private val strategyExecutor: ModelStrategyExecutor = strategyFactory.getStrategy()

    init {
        val metadata = strategyExecutor.getStrategyMetadata()
        logger.info {
            """
            ╔════════════════════════════════════════════════════════╗
            ║  ORCHESTRATOR SERVICE INITIALIZED                      ║
            ║  Active Strategy: ${metadata.name.padEnd(35)}║
            ║  Strategy Type: ${metadata.strategyType.name.padEnd(37)}║
            ║  Reasoning Stream: ${if (metadata.supportsReasoningStream) "YES ✓" else "NO  ✗".padEnd(39)}║
            ║  Source: Environment.kt (LOOP_MODEL_STRATEGY)          ║
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

            // Step 2: Add user message
            emit(StreamEvent.StatusUpdate(
                conversationId = conversationId,
                status = "Adding user message..."
            ))

            val userMsg = Message(role = MessageRole.USER, content = userMessage)
            var context = contextManager.addMessage(conversationId, userMsg)

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

            // Metadata collectors for persistence
            val collectedToolCalls = mutableListOf<ToolCallRecord>()
            val collectedReasoning = StringBuilder()
            val iterationReasoningMap = ConcurrentHashMap<Int, StringBuilder>()

            while (continueLoop && iteration < Environment.LOOP_MAX_ITERATIONS) {
                iteration++

                emit(StreamEvent.StatusUpdate(
                    conversationId = conversationId,
                    status = "Iteration $iteration of ${Environment.LOOP_MAX_ITERATIONS}...",
                    iteration = iteration
                ))

                logger.debug {
                    "[${metadata.name}] Iteration $iteration/${Environment.LOOP_MAX_ITERATIONS}"
                }

                // Execute strategy iteration (STREAMING MODE)
                strategyExecutor.executeIteration(
                    messages = messages,
                    tools = toolRegistry.getToolDefinitions(),
                    iterationContext = IterationContext(
                        conversationId = conversationId,
                        iteration = iteration,
                        maxIterations = Environment.LOOP_MAX_ITERATIONS,
                        streamingMode = StreamingMode.PROGRESSIVE  // Progressive chunks
                    )
                ).collect { strategyEvent ->

                    // Transform StrategyEvent → StreamEvent
                    when (strategyEvent) {
                        is StrategyEvent.ReasoningChunk -> {
                            // Collect reasoning for metadata persistence
                            collectedReasoning.append(strategyEvent.content)
                            iterationReasoningMap.computeIfAbsent(iteration) { StringBuilder() }
                                .append(strategyEvent.content)

                            // Only some strategies support reasoning
                            logger.debug { "[$conversationId] Received ReasoningChunk (${strategyEvent.content.length} chars), SHOW_REASONING=${Environment.LOOP_STREAMING_SHOW_REASONING_TRACES}" }
                            if (Environment.LOOP_STREAMING_SHOW_REASONING_TRACES) {
                                logger.info { "[$conversationId] >>> EMIT StreamEvent.ReasoningTrace (${strategyEvent.content.length} chars)" }
                                emit(StreamEvent.ReasoningTrace(
                                    conversationId = conversationId,
                                    content = strategyEvent.content,
                                    stage = com.alfredoalpizar.rag.model.response.ReasoningStage.PLANNING,
                                    iteration = iteration
                                ))
                            }
                        }

                        is StrategyEvent.ContentChunk -> {
                            logger.info { "[$conversationId] >>> EMIT StreamEvent.ResponseChunk (${strategyEvent.content.length} chars)" }
                            emit(StreamEvent.ResponseChunk(
                                conversationId = conversationId,
                                content = strategyEvent.content,
                                iteration = iteration
                            ))
                            finalContent += strategyEvent.content
                        }

                        is StrategyEvent.ToolCallDetected -> {
                            val toolName = strategyEvent.toolCall.function.name
                            val toolCallId = strategyEvent.toolCallId
                            val arguments = parseArguments(strategyEvent.toolCall.function.arguments)

                            // Check if this is the special finalize_answer tool
                            if (toolName == "finalize_answer") {
                                logger.info { "[$conversationId] finalize_answer detected - streaming final response" }

                                emit(StreamEvent.ToolCallStart(
                                    conversationId = conversationId,
                                    toolName = toolName,
                                    toolCallId = toolCallId,
                                    arguments = arguments,
                                    iteration = iteration
                                ))

                                // Execute finalize_answer streaming
                                val finalizeResult = executeFinalizeAnswer(
                                    conversationId = conversationId,
                                    context = arguments["context"] as? String ?: "",
                                    userQuestion = arguments["user_question"] as? String ?: "",
                                    answerStyle = arguments["answer_style"] as? String ?: "detailed"
                                ) { chunk ->
                                    // Stream each chunk as ResponseChunk with final answer flag
                                    emit(StreamEvent.ResponseChunk(
                                        conversationId = conversationId,
                                        content = chunk,
                                        iteration = iteration,
                                        isFinalAnswer = true
                                    ))
                                }

                                finalContent = finalizeResult.content
                                totalTokens += finalizeResult.tokensUsed

                                // Collect finalize_answer tool call
                                collectedToolCalls.add(ToolCallRecord(
                                    id = toolCallId,
                                    name = toolName,
                                    arguments = arguments.filterKeys { it != "context" },  // Exclude large context
                                    result = ToolResultSummary(
                                        type = "finalize_answer",
                                        summary = "Final answer generated (${finalizeResult.content.length} chars)",
                                        success = true
                                    ),
                                    success = true,
                                    iteration = iteration
                                ))

                                // Build metadata for persistence
                                val metadata = buildMessageMetadata(
                                    collectedToolCalls,
                                    collectedReasoning.toString(),
                                    iterationReasoningMap,
                                    iteration,
                                    totalTokens
                                )

                                // Persist finalize_answer response with metadata
                                contextManager.addMessageWithMetadata(
                                    conversationId,
                                    Message(role = MessageRole.ASSISTANT, content = finalizeResult.content),
                                    metadata.toJson()
                                )

                                emit(StreamEvent.ToolCallResult(
                                    conversationId = conversationId,
                                    toolName = toolName,
                                    toolCallId = toolCallId,
                                    result = "Final answer streamed successfully",
                                    success = true,
                                    iteration = iteration
                                ))

                                // Mark loop as complete - finalize_answer ends the loop
                                continueLoop = false

                            } else {
                                // Normal tool execution
                                emit(StreamEvent.ToolCallStart(
                                    conversationId = conversationId,
                                    toolName = toolName,
                                    toolCallId = toolCallId,
                                    arguments = arguments,
                                    iteration = iteration
                                ))

                                val result = toolRegistry.executeTool(strategyEvent.toolCall)

                                // Collect tool call for metadata
                                val resultSummary = createToolResultSummary(toolName, result.result, result.success)
                                collectedToolCalls.add(ToolCallRecord(
                                    id = result.toolCallId,
                                    name = result.toolName,
                                    arguments = arguments,
                                    result = resultSummary,
                                    success = result.success,
                                    iteration = iteration
                                ))

                                emit(StreamEvent.ToolCallResult(
                                    conversationId = conversationId,
                                    toolName = result.toolName,
                                    toolCallId = result.toolCallId,
                                    result = result.result,
                                    success = result.success,
                                    iteration = iteration
                                ))

                                // Add tool result to messages
                                messages.add(Message(
                                    role = MessageRole.TOOL,
                                    content = result.result,
                                    toolCallId = result.toolCallId
                                ))

                                context.conversation.toolCallsCount++
                            }
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
                                val toolName = toolCall.function.name
                                val toolCallId = toolCall.id
                                val arguments = parseArguments(toolCall.function.arguments)

                                // Check if this is the special finalize_answer tool
                                if (toolName == "finalize_answer") {
                                    logger.info { "[$conversationId] finalize_answer detected in batch - streaming final response" }

                                    emit(StreamEvent.ToolCallStart(
                                        conversationId = conversationId,
                                        toolName = toolName,
                                        toolCallId = toolCallId,
                                        arguments = arguments,
                                        iteration = iteration
                                    ))

                                    // Execute finalize_answer streaming
                                    val finalizeResult = executeFinalizeAnswer(
                                        conversationId = conversationId,
                                        context = arguments["context"] as? String ?: "",
                                        userQuestion = arguments["user_question"] as? String ?: "",
                                        answerStyle = arguments["answer_style"] as? String ?: "detailed"
                                    ) { chunk ->
                                        emit(StreamEvent.ResponseChunk(
                                            conversationId = conversationId,
                                            content = chunk,
                                            iteration = iteration,
                                            isFinalAnswer = true
                                        ))
                                    }

                                    finalContent = finalizeResult.content
                                    totalTokens += finalizeResult.tokensUsed

                                    // Collect finalize_answer tool call
                                    collectedToolCalls.add(ToolCallRecord(
                                        id = toolCallId,
                                        name = toolName,
                                        arguments = arguments.filterKeys { it != "context" },
                                        result = ToolResultSummary(
                                            type = "finalize_answer",
                                            summary = "Final answer generated (${finalizeResult.content.length} chars)",
                                            success = true
                                        ),
                                        success = true,
                                        iteration = iteration
                                    ))

                                    // Build metadata for persistence
                                    val metadata = buildMessageMetadata(
                                        collectedToolCalls,
                                        collectedReasoning.toString(),
                                        iterationReasoningMap,
                                        iteration,
                                        totalTokens
                                    )

                                    // Persist finalize_answer response with metadata
                                    contextManager.addMessageWithMetadata(
                                        conversationId,
                                        Message(role = MessageRole.ASSISTANT, content = finalizeResult.content),
                                        metadata.toJson()
                                    )

                                    emit(StreamEvent.ToolCallResult(
                                        conversationId = conversationId,
                                        toolName = toolName,
                                        toolCallId = toolCallId,
                                        result = "Final answer streamed successfully",
                                        success = true,
                                        iteration = iteration
                                    ))

                                    // Mark loop as complete
                                    continueLoop = false

                                } else {
                                    // Normal tool execution
                                    emit(StreamEvent.ToolCallStart(
                                        conversationId = conversationId,
                                        toolName = toolName,
                                        toolCallId = toolCallId,
                                        arguments = arguments,
                                        iteration = iteration
                                    ))

                                    val result = toolRegistry.executeTool(toolCall)

                                    // Collect tool call for metadata
                                    val resultSummary = createToolResultSummary(toolName, result.result, result.success)
                                    collectedToolCalls.add(ToolCallRecord(
                                        id = result.toolCallId,
                                        name = result.toolName,
                                        arguments = arguments,
                                        result = resultSummary,
                                        success = result.success,
                                        iteration = iteration
                                    ))

                                    emit(StreamEvent.ToolCallResult(
                                        conversationId = conversationId,
                                        toolName = result.toolName,
                                        toolCallId = result.toolCallId,
                                        result = result.result,
                                        success = result.success,
                                        iteration = iteration
                                    ))

                                    messages.add(Message(
                                        role = MessageRole.TOOL,
                                        content = result.result,
                                        toolCallId = result.toolCallId
                                    ))

                                    context.conversation.toolCallsCount++
                                }
                            }
                        }

                        is StrategyEvent.FinalResponse -> {
                            finalContent = strategyEvent.content

                            // Build metadata for persistence
                            val updatedTokens = totalTokens + strategyEvent.tokensUsed
                            val metadata = buildMessageMetadata(
                                collectedToolCalls,
                                collectedReasoning.toString(),
                                iterationReasoningMap,
                                iteration,
                                updatedTokens
                            )

                            // Save assistant message with metadata
                            contextManager.addMessageWithMetadata(
                                conversationId,
                                Message(role = MessageRole.ASSISTANT, content = finalContent),
                                metadata.toJson()
                            )

                            // Apply finalizer
                            val formattedResponse = finalizerStrategy.format(
                                finalContent,
                                mapOf(
                                    "iterations" to iteration,
                                    "tokens" to updatedTokens,
                                    "toolCalls" to context.conversation.toolCallsCount
                                )
                            )

                            emit(StreamEvent.ResponseChunk(
                                conversationId = conversationId,
                                content = formattedResponse,
                                iteration = iteration,
                                isFinalAnswer = true  // FinalResponse is the final answer
                            ))
                        }

                        is StrategyEvent.StatusUpdate -> {
                            emit(StreamEvent.StatusUpdate(
                                conversationId = conversationId,
                                status = strategyEvent.status,
                                details = strategyEvent.phase,
                                iteration = iteration
                            ))
                        }

                        is StrategyEvent.IterationComplete -> {
                            totalTokens += strategyEvent.tokensUsed
                            // Use && to preserve continueLoop=false set by finalize_answer
                            continueLoop = continueLoop && strategyEvent.shouldContinue

                            logger.info {
                                "[$conversationId] Iteration $iteration complete: " +
                                        "tokens=${strategyEvent.tokensUsed}, " +
                                        "shouldContinue=$continueLoop, " +
                                        "metadata=${strategyEvent.metadata}"
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
            // Add user message (returns updated context)
            var context = contextManager.addMessage(conversationId, Message(MessageRole.USER, userMessage))

            // Initial RAG
            val ragResults = performRAGSearch(userMessage)
            val messages = buildMessageList(context, ragResults)

            // Tool-calling loop
            var iteration = 0
            var totalTokens = 0
            var continueLoop = true
            var finalContent = ""

            while (continueLoop && iteration < Environment.LOOP_MAX_ITERATIONS) {
                iteration++

                logger.debug { "[${metadata.name}] Iteration $iteration/${Environment.LOOP_MAX_ITERATIONS}" }

                // Execute strategy iteration (SYNCHRONOUS MODE)
                strategyExecutor.executeIteration(
                    messages = messages,
                    tools = toolRegistry.getToolDefinitions(),
                    iterationContext = IterationContext(
                        conversationId = conversationId,
                        iteration = iteration,
                        maxIterations = Environment.LOOP_MAX_ITERATIONS,
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

                            for (toolCall in strategyEvent.toolCalls) {
                                val toolName = toolCall.function.name
                                val arguments = parseArguments(toolCall.function.arguments)

                                if (toolName == "finalize_answer") {
                                    // Execute finalize_answer (non-streaming - ignore chunks)
                                    val finalizeResult = executeFinalizeAnswer(
                                        conversationId = conversationId,
                                        context = arguments["context"] as? String ?: "",
                                        userQuestion = arguments["user_question"] as? String ?: "",
                                        answerStyle = arguments["answer_style"] as? String ?: "detailed"
                                    ) { /* ignore chunks in sync mode */ }

                                    finalContent = finalizeResult.content
                                    totalTokens += finalizeResult.tokensUsed

                                    // Persist to conversation history
                                    contextManager.addMessage(
                                        conversationId,
                                        Message(role = MessageRole.ASSISTANT, content = finalizeResult.content)
                                    )

                                    // Mark loop as complete
                                    continueLoop = false
                                } else {
                                    // Normal tool execution
                                    val result = toolRegistry.executeTool(toolCall)
                                    messages.add(Message(
                                        role = MessageRole.TOOL,
                                        content = result.result,
                                        toolCallId = toolCall.id
                                    ))
                                    context.conversation.toolCallsCount++
                                }
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
                            // Use && to preserve continueLoop=false set by finalize_answer
                            continueLoop = continueLoop && strategyEvent.shouldContinue
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

        // Add orchestrator system prompt (first message) - explains workflow to model
        messages.add(Message(
            role = MessageRole.SYSTEM,
            content = """
                You are a helpful assistant that answers questions using available tools and knowledge.

                ## How This Works
                1. Knowledge base context has been automatically retrieved for you (see below)
                2. You have access to tools for additional searches if needed
                3. When you have enough information to answer, call the finalize_answer tool

                ## Tool Usage Guidelines
                - Use rag_search for additional knowledge base queries if the pre-retrieved context is insufficient
                - Call finalize_answer when you're ready to give your final response to the user

                ## When to Finalize
                - If the knowledge base provides related context (even if not an explicit definition), USE IT to form a helpful answer
                - You may synthesize and infer from available context - don't wait for a "perfect" explicit answer
                - After 1-2 rag_search attempts without finding significantly new information, proceed to finalize_answer
                - Combine KB context with reasonable inference to provide a helpful response
                - It's better to give a helpful answer based on available context than to keep searching indefinitely
            """.trimIndent()
        ))

        // Add conversation history
        messages.addAll(context.messages)

        // Add pre-retrieved RAG results with clear labeling
        if (ragResults.isNotBlank()) {
            messages.add(Message(
                role = MessageRole.SYSTEM,
                content = """
                    ## Pre-Retrieved Knowledge Base Context
                    The following information was automatically retrieved from the knowledge base based on the user's question.
                    Use this context to inform your response. If you need additional information not covered here,
                    use the rag_search tool to search for more specific details.

                    ---
                    $ragResults
                """.trimIndent()
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

    /**
     * Execute the finalize_answer tool by making a streaming call to Qwen
     * with reasoning_effort: "none" to get clean, final output.
     *
     * @param conversationId For logging
     * @param context All relevant information gathered from previous tool calls
     * @param userQuestion The original user question
     * @param answerStyle How to format the answer: concise, detailed, step_by_step
     * @param onChunk Callback for each streamed chunk
     * @return FinalizeResult containing the accumulated response and tokens used
     */
    private suspend fun executeFinalizeAnswer(
        conversationId: String,
        context: String,
        userQuestion: String,
        answerStyle: String,
        onChunk: suspend (String) -> Unit
    ): FinalizeResult {
        logger.info {
            "[$conversationId] Executing finalize_answer: " +
                    "userQuestion='${userQuestion.take(50)}...', " +
                    "contextLength=${context.length}, " +
                    "answerStyle=$answerStyle"
        }

        // Build the finalization prompt
        val styleInstruction = when (answerStyle) {
            "concise" -> "Provide a brief, direct answer. Be concise."
            "step_by_step" -> "Provide step-by-step instructions. Use numbered steps."
            else -> "Provide a comprehensive, helpful answer with relevant details."
        }

        val systemPrompt = """
            You are a helpful assistant providing a final answer to the user.
            $styleInstruction

            CRITICAL RULES:
            - Address the user directly with a clean, professional response
            - DO NOT include any internal reasoning, meta-commentary, or "thinking out loud"
            - DO NOT describe what you're doing (e.g., "Let me break this down", "checking the context")
            - DO NOT mention instructions, context sources, or how you arrived at the answer
            - Just provide the answer directly in clear, readable markdown
            - Base your answer primarily on the provided context
            - You MAY make reasonable inferences when the context implies something but doesn't state it explicitly
            - If the context discusses a topic (e.g., password reset for locked users), you can infer related concepts (e.g., what being locked out means)
            - If the context contains URLs, paths, or links, INCLUDE them in your response (e.g., "visit /internal/reset-password")
        """.trimIndent()

        val userPrompt = """
            ## User Question
            $userQuestion

            ## Available Context
            $context

            ## Instructions
            Based on the context above, provide a helpful answer to the user's question.
        """.trimIndent()

        val messages = listOf(
            Message(role = MessageRole.SYSTEM, content = systemPrompt),
            Message(role = MessageRole.USER, content = userPrompt)
        )

        // Build request using instruct model for clean output (NOT thinking model)
        // Thinking models output reasoning in content even with reasoning_effort=none
        val config = RequestConfig(
            streamingEnabled = true,
            temperature = Environment.LOOP_TEMPERATURE,
            maxTokens = Environment.LOOP_MAX_TOKENS,
            extraParams = mapOf("useInstructModel" to true)  // Use instruct model for clean output
        )

        val request = qwenProvider.buildRequest(
            messages = messages,
            tools = emptyList<Any>(),  // No tools for finalization
            config = config
        )

        // Stream the response
        val accumulatedContent = StringBuilder()
        var tokensUsed = 0

        qwenProvider.chatStream(request).collect { chunk ->
            val parsed = qwenProvider.extractStreamChunk(chunk)

            parsed.contentDelta?.let { delta ->
                accumulatedContent.append(delta)
                onChunk(delta)
            }

            // Capture token usage (typically in final chunk)
            parsed.tokensUsed?.let { tokens ->
                tokensUsed = tokens
            }

            if (parsed.finishReason != null) {
                logger.info {
                    "[$conversationId] finalize_answer complete: " +
                            "${accumulatedContent.length} chars, " +
                            "tokensUsed=$tokensUsed, " +
                            "finishReason=${parsed.finishReason}"
                }
            }
        }

        return FinalizeResult(accumulatedContent.toString(), tokensUsed)
    }

    /**
     * Build MessageMetadata from collected data during the streaming loop.
     */
    private fun buildMessageMetadata(
        toolCalls: List<ToolCallRecord>,
        reasoning: String,
        iterationReasoningMap: Map<Int, StringBuilder>,
        iterations: Int,
        totalTokens: Int
    ): MessageMetadata {
        val iterationData = iterationReasoningMap.map { (iter, reasoningBuilder) ->
            IterationRecord(
                iteration = iter,
                reasoning = reasoningBuilder.toString().takeIf { it.isNotBlank() },
                toolCallIds = toolCalls.filter { it.iteration == iter }.map { it.id }
            )
        }.sortedBy { it.iteration }

        return MessageMetadata(
            toolCalls = toolCalls.takeIf { it.isNotEmpty() },
            reasoning = reasoning.takeIf { it.isNotBlank() },
            iterationData = iterationData.takeIf { it.isNotEmpty() },
            metrics = MetricsRecord(
                iterations = iterations,
                totalTokens = totalTokens
            )
        )
    }

    /**
     * Create a summarized tool result for storage (avoiding large RAG results).
     */
    private fun createToolResultSummary(toolName: String, result: String, success: Boolean): ToolResultSummary {
        return when (toolName) {
            "rag_search" -> {
                // Parse RAG search result to extract chunk IDs/scores if possible
                // For now, just summarize
                val numResults = result.count { it == '\n' }.coerceAtLeast(1)
                ToolResultSummary(
                    type = "rag_search",
                    summary = "Retrieved $numResults document chunks (${result.length} chars)",
                    success = success
                )
            }
            else -> {
                ToolResultSummary(
                    type = toolName,
                    summary = if (result.length > 200) "${result.take(200)}..." else result,
                    success = success
                )
            }
        }
    }
}

/**
 * Result from finalize_answer execution.
 */
data class FinalizeResult(
    val content: String,
    val tokensUsed: Int
)

/**
 * Synchronous result for non-streaming operations.
 */
data class SyncResult(
    val content: String,
    val iterationsUsed: Int,
    val tokensUsed: Int,
    val conversationId: String
)
