package com.alfredoalpizar.rag.model.domain

data class ToolResult(
    val toolCallId: String,
    val toolName: String,
    val result: String,
    val success: Boolean = true,
    val error: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)
