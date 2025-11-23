package com.alfredoalpizar.rag.service.orchestrator

import com.alfredoalpizar.rag.client.deepseek.*
import com.alfredoalpizar.rag.config.LoopProperties
import com.alfredoalpizar.rag.model.domain.*
import com.alfredoalpizar.rag.model.response.StreamEvent
import com.alfredoalpizar.rag.service.context.ContextManager
import com.alfredoalpizar.rag.service.finalizer.FinalizerStrategy
import com.alfredoalpizar.rag.service.tool.ToolRegistry
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class OrchestratorService(
    private val contextManager: ContextManager,
    private val toolRegistry: ToolRegistry,
    private val deepSeekClient: DeepSeekClient,
    private val finalizerStrategy: FinalizerStrategy,
    private val properties: LoopProperties,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    suspend fun processMessageStream(
        conversationId: String,
        userMessage: String
    ): Flow<StreamEvent> = flow {

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

            // Build initial context
            val messages = mutableListOf<Message>()
            messages.addAll(context.messages)

            // Add RAG results as system message if available
            if (ragResults.isNotBlank()) {
                messages.add(Message(
                    role = MessageRole.SYSTEM,
                    content = "Knowledge base results:\n$ragResults"
                ))
            }

            // Step 4: Tool-calling loop
            var iteration = 0
            var totalTokens = 0
            var continueLoop = true

            while (continueLoop && iteration < properties.maxIterations) {
                iteration++

                emit(StreamEvent.StatusUpdate(
                    conversationId = conversationId,
                    status = "Iteration $iteration of ${properties.maxIterations}..."
                ))

                logger.debug { "Starting iteration $iteration for conversation $conversationId" }

                // Call LLM
                val request = buildDeepSeekRequest(messages)
                val response = deepSeekClient.chat(request).awaitSingle()

                val assistantMessage = response.choices.firstOrNull()?.message
                    ?: throw IllegalStateException("No response from DeepSeek API")

                totalTokens += response.usage?.totalTokens ?: 0

                // Check for tool calls
                if (!assistantMessage.toolCalls.isNullOrEmpty()) {
                    logger.debug { "Assistant requested ${assistantMessage.toolCalls.size} tool calls" }

                    // Add assistant message with tool calls
                    messages.add(Message(
                        role = MessageRole.ASSISTANT,
                        content = assistantMessage.content ?: "",
                        toolCalls = assistantMessage.toolCalls.map { it.toToolCall() }
                    ))

                    // Execute each tool
                    for (toolCall in assistantMessage.toolCalls) {

                        // Emit ToolCallStart
                        emit(StreamEvent.ToolCallStart(
                            conversationId = conversationId,
                            toolName = toolCall.function.name,
                            toolCallId = toolCall.id,
                            arguments = parseArguments(toolCall.function.arguments)
                        ))

                        // Execute tool
                        val result = toolRegistry.executeTool(toolCall.toToolCall())

                        // Emit ToolCallResult
                        emit(StreamEvent.ToolCallResult(
                            conversationId = conversationId,
                            toolName = result.toolName,
                            toolCallId = result.toolCallId,
                            result = result.result,
                            success = result.success
                        ))

                        // Add result to context
                        messages.add(Message(
                            role = MessageRole.TOOL,
                            content = result.result,
                            toolCallId = result.toolCallId
                        ))

                        // Update conversation stats
                        context.conversation.toolCallsCount++
                    }

                } else {
                    // No tool calls - final response
                    continueLoop = false

                    val finalContent = assistantMessage.content ?: ""
                    messages.add(Message(role = MessageRole.ASSISTANT, content = finalContent))

                    logger.debug { "Final response generated for conversation $conversationId" }

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
                            "tokens" to totalTokens,
                            "toolCalls" to context.conversation.toolCallsCount
                        )
                    )

                    // Emit response
                    emit(StreamEvent.ResponseChunk(
                        conversationId = conversationId,
                        content = formattedResponse
                    ))
                }
            }

            // Step 5: Update final stats and complete
            context.conversation.totalTokens += totalTokens
            contextManager.saveConversation(context)

            logger.info { "Completed processing for conversation $conversationId: iterations=$iteration, tokens=$totalTokens" }

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

    private fun buildDeepSeekRequest(messages: List<Message>): DeepSeekChatRequest {
        val model = if (properties.useReasoningModel) {
            "deepseek-reasoner"
        } else {
            "deepseek-chat"
        }

        return DeepSeekChatRequest(
            model = model,
            messages = messages.map { it.toDeepSeekMessage() },
            temperature = properties.temperature,
            maxTokens = properties.maxTokens,
            tools = toolRegistry.getToolDefinitions()
        )
    }

    private fun Message.toDeepSeekMessage(): DeepSeekMessage {
        return DeepSeekMessage(
            role = this.role.name.lowercase(),
            content = this.content,
            toolCallId = this.toolCallId,
            toolCalls = this.toolCalls?.map { it.toDeepSeekToolCall() }
        )
    }

    private fun ToolCall.toDeepSeekToolCall(): DeepSeekToolCall {
        return DeepSeekToolCall(
            id = this.id,
            type = this.type,
            function = DeepSeekFunctionCall(
                name = this.function.name,
                arguments = this.function.arguments
            )
        )
    }

    private fun DeepSeekToolCall.toToolCall(): ToolCall {
        return ToolCall(
            id = this.id,
            type = this.type,
            function = FunctionCall(
                name = this.function.name,
                arguments = this.function.arguments
            )
        )
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
