package com.alfredoalpizar.rag.service.orchestrator.provider

import com.alfredoalpizar.rag.client.qwen.*
import com.alfredoalpizar.rag.config.Environment
import com.alfredoalpizar.rag.model.domain.FunctionCall
import com.alfredoalpizar.rag.model.domain.Message
import com.alfredoalpizar.rag.model.domain.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * ModelProvider implementation for Qwen API.
 * Wraps QwenClient with the unified provider interface.
 *
 * Supports:
 * - Thinking models (qwen-max with reasoning traces)
 * - Instruct models (qwen-plus for fast execution)
 * - Reasoning stream extraction
 */
@Component
class QwenModelProvider(
    private val client: QwenClient
) : ModelProvider<QwenChatRequest, QwenChatResponse, QwenStreamChunk> {

    private val logger = KotlinLogging.logger {}

    override suspend fun chat(request: QwenChatRequest): QwenChatResponse {
        return client.chat(request).awaitSingle()
    }

    override fun chatStream(request: QwenChatRequest): Flow<QwenStreamChunk> {
        return client.chatStream(request).asFlow()
    }

    override fun buildRequest(
        messages: List<Message>,
        tools: List<*>,
        config: RequestConfig
    ): QwenChatRequest {
        // Determine model based on configuration
        val model = when {
            config.extraParams["useThinkingModel"] as? Boolean == true -> Environment.QWEN_THINKING_MODEL
            config.extraParams["useInstructModel"] as? Boolean == true -> Environment.QWEN_INSTRUCT_MODEL
            else -> Environment.QWEN_THINKING_MODEL  // Default to thinking model
        }

        // Enable thinking for thinking models
        val enableThinking = config.extraParams["enableThinking"] as? Boolean
            ?: (model == Environment.QWEN_THINKING_MODEL)

        logger.debug {
            "Building Qwen request: model=$model, " +
                    "streaming=${config.streamingEnabled}, " +
                    "thinking=$enableThinking"
        }

        // Convert tool definitions to Qwen format
        val qwenTools = if (tools.isNotEmpty()) {
            tools.map { tool ->
                val toolDef = tool as com.alfredoalpizar.rag.service.tool.ToolDefinition
                QwenTool(
                    type = "function",
                    function = QwenFunction(
                        name = toolDef.name,
                        description = toolDef.description,
                        parameters = toolDef.parameters
                    )
                )
            }
        } else null

        return QwenChatRequest(
            model = model,
            messages = messages.map { it.toQwenMessage() },
            temperature = config.temperature ?: Environment.LOOP_TEMPERATURE,
            maxTokens = config.maxTokens ?: Environment.LOOP_MAX_TOKENS,
            stream = config.streamingEnabled,
            tools = qwenTools,
            enableThinking = enableThinking
        )
    }

    override fun extractMessage(response: QwenChatResponse): ProviderMessage {
        val choice = response.choices.firstOrNull()
            ?: throw IllegalStateException("No choices in Qwen response")

        val message = choice.message

        return ProviderMessage(
            content = message.content,
            toolCalls = message.toolCalls?.map { it.toToolCall() },
            tokensUsed = response.usage?.totalTokens ?: 0,
            reasoningContent = message.reasoningContent  // Qwen-specific reasoning
        )
    }

    override fun extractStreamChunk(chunk: QwenStreamChunk): ProviderStreamChunk {
        val choice = chunk.choices.firstOrNull()

        return ProviderStreamChunk(
            contentDelta = choice?.delta?.content,
            reasoningDelta = choice?.delta?.reasoningContent,  // Qwen thinking stream
            toolCalls = choice?.delta?.toolCalls?.map { it.toToolCall() },
            finishReason = choice?.finishReason,
            role = choice?.delta?.role,
            tokensUsed = chunk.usage?.totalTokens  // Extract token usage from final chunk
        )
    }

    override fun getProviderInfo(): ProviderInfo {
        return ProviderInfo(
            name = "Qwen",
            supportsStreaming = true,
            supportsReasoningStream = true,  // Qwen thinking models support reasoning stream
            supportsToolCalling = true
        )
    }

    // ===== Conversion Methods =====

    private fun Message.toQwenMessage(): QwenMessage {
        return QwenMessage(
            role = this.role.name.lowercase(),
            content = this.content,
            toolCallId = this.toolCallId,
            toolCalls = this.toolCalls?.map { it.toQwenToolCall() }
        )
    }

    private fun ToolCall.toQwenToolCall(): QwenToolCall {
        return QwenToolCall(
            id = this.id,
            type = this.type,
            function = QwenFunctionCall(
                name = this.function.name,
                arguments = this.function.arguments
            )
        )
    }

    private fun QwenToolCall.toToolCall(): ToolCall {
        return ToolCall(
            id = this.id,
            type = this.type,
            function = FunctionCall(
                name = this.function.name,
                arguments = this.function.arguments
            )
        )
    }
}
