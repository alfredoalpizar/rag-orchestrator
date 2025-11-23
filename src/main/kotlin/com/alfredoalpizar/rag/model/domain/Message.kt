package com.alfredoalpizar.rag.model.domain

data class Message(
    val role: MessageRole,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null
)

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

data class FunctionCall(
    val name: String,
    val arguments: String // JSON string
)
