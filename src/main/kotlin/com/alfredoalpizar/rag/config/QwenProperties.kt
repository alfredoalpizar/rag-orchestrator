package com.alfredoalpizar.rag.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "qwen.api")
data class QwenProperties(
    val baseUrl: String,
    val apiKey: String,
    val thinkingModel: String,
    val instructModel: String,
    val embeddingModel: String,
    val timeoutSeconds: Long,
    val maxRetries: Int
)
