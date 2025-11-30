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

        // Note: enable_thinking parameter removed - not supported by Fireworks AI API
        // The qwen3-235b-a22b-thinking model handles reasoning automatically

        logger.debug {
            "Building Qwen request: model=$model, " +
                    "streaming=${config.streamingEnabled}, " +
                    "reasoningEffort=${config.reasoningEffort ?: "default"}"
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
            reasoningEffort = config.reasoningEffort  // Pass through for finalize_answer (use "none")
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

        // Tool calls are already accumulated by QwenClientImpl - just convert them
        val toolCalls = choice?.delta?.toolCalls?.mapNotNull { it.toToolCallOrNull() }

        return ProviderStreamChunk(
            contentDelta = choice?.delta?.content,
            reasoningDelta = choice?.delta?.reasoningContent,  // Qwen thinking stream
            toolCalls = if (toolCalls.isNullOrEmpty()) null else toolCalls,
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

    /**
     * Convert QwenToolCall to domain ToolCall.
     * Throws if required fields are missing (for non-streaming responses).
     */
    private fun QwenToolCall.toToolCall(): ToolCall {
        return ToolCall(
            id = this.id ?: throw IllegalStateException("Tool call missing id"),
            type = this.type ?: "function",
            function = FunctionCall(
                name = this.function?.name ?: throw IllegalStateException("Tool call missing function name"),
                arguments = this.function?.arguments ?: ""
            )
        )
    }

    /**
     * Convert QwenToolCall to domain ToolCall, returning null if required fields are missing.
     * Used for streaming responses where tool calls are accumulated.
     */
    private fun QwenToolCall.toToolCallOrNull(): ToolCall? {
        val id = this.id ?: return null
        val name = this.function?.name ?: return null

        return ToolCall(
            id = id,
            type = this.type ?: "function",
            function = FunctionCall(
                name = name,
                arguments = this.function?.arguments ?: ""
            )
        )
    }
}
