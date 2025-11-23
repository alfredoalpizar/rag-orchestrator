package com.alfredoalpizar.rag.service.tool

import com.alfredoalpizar.rag.model.domain.ToolResult

interface Tool {
    /**
     * Unique tool identifier (used by LLM to invoke)
     */
    val name: String

    /**
     * Human-readable description of what the tool does
     * (LLM uses this to decide when to call the tool)
     */
    val description: String

    /**
     * JSON Schema describing the tool's parameters
     */
    val parameters: Map<String, Any>

    /**
     * Execute the tool with given arguments
     * @param arguments Parsed JSON arguments from LLM
     * @return ToolResult with success/failure and output
     */
    suspend fun execute(arguments: Map<String, Any>): ToolResult
}
