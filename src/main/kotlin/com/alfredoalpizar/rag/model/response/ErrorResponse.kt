package com.alfredoalpizar.rag.model.response

import java.time.Instant

data class ErrorResponse(
    val error: String,
    val message: String?,
    val timestamp: Instant = Instant.now(),
    val path: String? = null
)
