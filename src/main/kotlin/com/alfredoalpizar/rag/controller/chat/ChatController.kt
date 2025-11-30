package com.alfredoalpizar.rag.controller.chat

import com.alfredoalpizar.rag.model.request.ChatRequest
import com.alfredoalpizar.rag.model.request.CreateConversationRequest
import com.alfredoalpizar.rag.model.response.ConversationResponse
import com.alfredoalpizar.rag.model.response.StreamEvent
import com.alfredoalpizar.rag.model.response.toResponse
import com.alfredoalpizar.rag.service.context.ContextManager
import com.alfredoalpizar.rag.service.orchestrator.OrchestratorService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.flux
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

@RestController
@RequestMapping("/api/v1/chat")
class ChatController(
    private val orchestratorService: OrchestratorService,
    private val contextManager: ContextManager,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    @PostMapping("/conversations")
    suspend fun createConversation(
        @Valid @RequestBody request: CreateConversationRequest
    ): ResponseEntity<ConversationResponse> {
        logger.info { "Creating conversation for caller: ${request.callerId}" }

        val context = contextManager.createConversation(request)

        return ResponseEntity.ok(context.conversation.toResponse())
    }

    @GetMapping("/conversations/{conversationId}")
    suspend fun getConversation(
        @PathVariable conversationId: String
    ): ResponseEntity<ConversationResponse> {
        logger.debug { "Getting conversation: $conversationId" }

        val context = contextManager.loadConversation(conversationId)

        return ResponseEntity.ok(context.conversation.toResponse())
    }

    @PostMapping(
        path = ["/conversations/{conversationId}/messages/stream"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun sendMessageStream(
        @PathVariable conversationId: String,
        @Valid @RequestBody request: ChatRequest
    ): Flux<ServerSentEvent<String>> {
        logger.info { "Streaming message for conversation: $conversationId" }

        // Use flux{} builder for proper non-blocking Flow → Flux conversion
        // This enables true streaming where events are sent as they arrive
        return flux {
            orchestratorService.processMessageStream(conversationId, request.message)
                .collect { event ->
                    val sse = ServerSentEvent.builder<String>()
                        .event(event.javaClass.simpleName)
                        .data(objectMapper.writeValueAsString(event))
                        .build()
                    send(sse)
                }
        }
            .doOnError { error: Throwable ->
                logger.error(error) { "Error streaming for conversation: $conversationId" }
            }
            .doOnComplete {
                logger.info { "Streaming completed for conversation: $conversationId" }
            }
    }

    @GetMapping("/conversations")
    suspend fun listConversations(
        @RequestParam callerId: String,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<List<ConversationResponse>> {
        logger.debug { "Listing conversations for caller: $callerId, limit=$limit" }

        val conversations = contextManager.getRecentConversations(callerId, limit.coerceIn(1, 100))

        return ResponseEntity.ok(conversations.map { it.toResponse() })
    }
}
