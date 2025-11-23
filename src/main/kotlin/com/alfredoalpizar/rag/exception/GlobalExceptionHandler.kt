package com.alfredoalpizar.rag.exception

import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import java.time.Instant

private val logger = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ConversationNotFoundException::class)
    fun handleConversationNotFound(ex: ConversationNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(
                error = "NOT_FOUND",
                message = ex.message ?: "Conversation not found",
                timestamp = Instant.now()
            ))
    }

    @ExceptionHandler(InvalidRequestException::class)
    fun handleInvalidRequest(ex: InvalidRequestException): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(
                error = "BAD_REQUEST",
                message = ex.message ?: "Invalid request",
                timestamp = Instant.now()
            ))
    }

    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidationErrors(ex: WebExchangeBindException): ResponseEntity<ErrorResponse> {
        val errors = ex.bindingResult.fieldErrors
            .map { "${it.field}: ${it.defaultMessage}" }
            .joinToString(", ")

        logger.warn { "Validation errors: $errors" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(
                error = "VALIDATION_ERROR",
                message = errors,
                timestamp = Instant.now()
            ))
    }

    @ExceptionHandler(RateLimitException::class)
    fun handleRateLimit(ex: RateLimitException): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .body(ErrorResponse(
                error = "RATE_LIMIT_EXCEEDED",
                message = ex.message ?: "Rate limit exceeded",
                timestamp = Instant.now()
            ))
    }

    @ExceptionHandler(MaxIterationsExceededException::class)
    fun handleMaxIterations(ex: MaxIterationsExceededException): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(
                error = "MAX_ITERATIONS_EXCEEDED",
                message = ex.message ?: "Maximum iterations exceeded",
                timestamp = Instant.now()
            ))
    }

    @ExceptionHandler(DeepSeekClientException::class, ChromaDBClientException::class)
    fun handleClientException(ex: RagException): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "External client error: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse(
                error = "EXTERNAL_SERVICE_ERROR",
                message = "External service error: ${ex.message}",
                timestamp = Instant.now()
            ))
    }

    @ExceptionHandler(ToolExecutionException::class)
    fun handleToolExecution(ex: ToolExecutionException): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "Tool execution error: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(
                error = "TOOL_EXECUTION_ERROR",
                message = ex.message ?: "Tool execution failed",
                timestamp = Instant.now()
            ))
    }

    @ExceptionHandler(ContextOverflowException::class)
    fun handleContextOverflow(ex: ContextOverflowException): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        return ResponseEntity
            .status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ErrorResponse(
                error = "CONTEXT_OVERFLOW",
                message = ex.message ?: "Context size exceeded",
                timestamp = Instant.now()
            ))
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "Unhandled exception: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(
                error = "INTERNAL_SERVER_ERROR",
                message = "An unexpected error occurred",
                timestamp = Instant.now()
            ))
    }

    data class ErrorResponse(
        val error: String,
        val message: String,
        val timestamp: Instant
    )
}
