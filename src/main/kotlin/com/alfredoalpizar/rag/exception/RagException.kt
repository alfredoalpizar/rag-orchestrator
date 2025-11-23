package com.alfredoalpizar.rag.exception

open class RagException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

class DeepSeekClientException(
    message: String,
    cause: Throwable? = null
) : RagException(message, cause)

class ChromaDBClientException(
    message: String,
    cause: Throwable? = null
) : RagException(message, cause)

class ConversationNotFoundException(
    conversationId: String
) : RagException("Conversation not found: $conversationId")

class MaxIterationsExceededException(
    maxIterations: Int
) : RagException("Maximum iterations exceeded: $maxIterations")

class ToolExecutionException(
    toolName: String,
    cause: Throwable? = null
) : RagException("Tool execution failed: $toolName", cause)

class ContextOverflowException(
    message: String
) : RagException(message)

class InvalidRequestException(
    message: String
) : RagException(message)

class RateLimitException(
    message: String = "Rate limit exceeded"
) : RagException(message)
