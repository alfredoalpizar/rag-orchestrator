package com.alfredoalpizar.rag.service.tool

import com.alfredoalpizar.rag.client.deepseek.DeepSeekFunction
import com.alfredoalpizar.rag.client.deepseek.DeepSeekTool
import com.alfredoalpizar.rag.model.domain.ToolCall
import com.alfredoalpizar.rag.model.domain.ToolResult
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class ToolRegistry(
    tools: List<Tool>,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}
    private val toolMap: Map<String, Tool> = tools.associateBy { it.name }

    init {
        logger.info { "Registered ${toolMap.size} tools: ${toolMap.keys.joinToString(", ")}" }
    }

    fun getTool(name: String): Tool? = toolMap[name]

    fun getAllTools(): List<Tool> = toolMap.values.toList()

    fun getToolDefinitions(): List<DeepSeekTool> {
        return toolMap.values.map { tool ->
            DeepSeekTool(
                type = "function",
                function = DeepSeekFunction(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            )
        }
    }

    suspend fun executeTool(toolCall: ToolCall): ToolResult {
        val tool = getTool(toolCall.function.name)
            ?: return ToolResult(
                toolCallId = toolCall.id,
                toolName = toolCall.function.name,
                result = "",
                success = false,
                error = "Tool not found: ${toolCall.function.name}"
            )

        return try {
            logger.debug { "Executing tool: ${tool.name} with arguments: ${toolCall.function.arguments}" }

            val arguments = parseArguments(toolCall.function.arguments)
            val result = tool.execute(arguments).copy(toolCallId = toolCall.id)

            logger.debug { "Tool ${tool.name} executed successfully: ${result.success}" }
            result

        } catch (e: Exception) {
            logger.error(e) { "Tool execution failed for ${tool.name}" }
            ToolResult(
                toolCallId = toolCall.id,
                toolName = tool.name,
                result = "",
                success = false,
                error = "Tool execution failed: ${e.message}"
            )
        }
    }

    private fun parseArguments(json: String): Map<String, Any> {
        return try {
            objectMapper.readValue(json, object : TypeReference<Map<String, Any>>() {})
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse tool arguments: $json" }
            emptyMap()
        }
    }
}
