package com.alfredoalpizar.rag.model.request

import jakarta.validation.constraints.NotBlank

data class ChatRequest(
    @field:NotBlank(message = "Message cannot be blank")
    val message: String,

    val conversationId: String? = null,
    val callerId: String? = null,
    val userId: String? = null,
    val accountId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
