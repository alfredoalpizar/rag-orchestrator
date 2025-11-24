package com.alfredoalpizar.rag.service.orchestrator.provider

import com.alfredoalpizar.rag.client.deepseek.*
import com.alfredoalpizar.rag.config.DeepSeekProperties
import com.alfredoalpizar.rag.config.LoopProperties
import com.alfredoalpizar.rag.model.domain.FunctionCall
import com.alfredoalpizar.rag.model.domain.Message
import com.alfredoalpizar.rag.model.domain.MessageRole
import com.alfredoalpizar.rag.model.domain.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactor.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * ModelProvider implementation for DeepSeek API.
 * Wraps DeepSeekClient with the unified provider interface.
 */
@Component
class DeepSeekModelProvider(
    private val client: DeepSeekClient,
    private val deepSeekProperties: DeepSeekProperties,
    private val loopProperties: LoopProperties
) : ModelProvider<DeepSeekChatRequest, DeepSeekChatResponse, DeepSeekStreamChunk> {

    private val logger = KotlinLogging.logger {}

    override suspend fun chat(request: DeepSeekChatRequest): DeepSeekChatResponse {
        return client.chat(request).awaitSingle()
    }

    override fun chatStream(request: DeepSeekChatRequest): Flow<DeepSeekStreamChunk> {
        return client.chatStream(request).asFlow()
    }

    override fun buildRequest(
        messages: List<Message>,
        tools: List<*>,
        config: RequestConfig
    ): DeepSeekChatRequest {
        // Determine model based on configuration
        val model = if (config.extraParams["useReasoningModel"] as? Boolean == true) {
            deepSeekProperties.reasoningModel
        } else {
            deepSeekProperties.chatModel
        }

        logger.debug { "Building DeepSeek request: model=$model, streaming=${config.streamingEnabled}" }

        // Convert tool definitions to DeepSeek format
        val deepSeekTools = if (tools.isNotEmpty()) {
            tools.map { tool ->
                val toolDef = tool as com.alfredoalpizar.rag.service.tool.ToolDefinition
                DeepSeekTool(
                    type = "function",
                    function = DeepSeekFunction(
                        name = toolDef.name,
                        description = toolDef.description,
                        parameters = toolDef.parameters
                    )
                )
            }
        } else null

        return DeepSeekChatRequest(
            model = model,
            messages = messages.map { it.toDeepSeekMessage() },
            temperature = config.temperature ?: loopProperties.temperature,
            maxTokens = config.maxTokens ?: loopProperties.maxTokens,
            stream = config.streamingEnabled,
            tools = deepSeekTools
        )
    }

    override fun extractMessage(response: DeepSeekChatResponse): ProviderMessage {
        val choice = response.choices.firstOrNull()
            ?: throw IllegalStateException("No choices in DeepSeek response")

        val message = choice.message

        return ProviderMessage(
            content = message.content,
            toolCalls = message.toolCalls?.map { it.toToolCall() },
            tokensUsed = response.usage?.totalTokens ?: 0,
            reasoningContent = null // DeepSeek doesn't expose reasoning in regular response
        )
    }

    override fun extractStreamChunk(chunk: DeepSeekStreamChunk): ProviderStreamChunk {
        val choice = chunk.choices.firstOrNull()

        return ProviderStreamChunk(
            contentDelta = choice?.delta?.content,
            reasoningDelta = null, // DeepSeek doesn't stream reasoning separately
            toolCalls = choice?.delta?.toolCalls?.map { it.toToolCall() },
            finishReason = choice?.finishReason,
            role = choice?.delta?.role,
            tokensUsed = chunk.usage?.totalTokens  // Extract token usage from final chunk
        )
    }

    override fun getProviderInfo(): ProviderInfo {
        return ProviderInfo(
            name = "DeepSeek",
            supportsStreaming = true,
            supportsReasoningStream = false, // DeepSeek reasoner doesn't expose reasoning stream
            supportsToolCalling = true
        )
    }

    // ===== Conversion Methods =====

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
}
