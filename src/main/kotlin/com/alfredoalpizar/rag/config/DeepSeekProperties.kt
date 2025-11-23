package com.alfredoalpizar.rag.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "deepseek.api")
data class DeepSeekProperties(
    val baseUrl: String,
    val apiKey: String,
    val chatModel: String,
    val reasoningModel: String,
    val embeddingModel: String,
    val timeoutSeconds: Long,
    val maxRetries: Int
)
