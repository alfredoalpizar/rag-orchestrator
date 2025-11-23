package com.alfredoalpizar.rag.model.request

import jakarta.validation.constraints.NotBlank

data class CreateConversationRequest(
    @field:NotBlank(message = "Caller ID is required")
    val callerId: String,

    val userId: String? = null,
    val accountId: String? = null,
    val initialMessage: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
