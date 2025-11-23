package com.alfredoalpizar.rag.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chromadb")
data class ChromaDBProperties(
    val baseUrl: String,
    val collectionName: String,
    val maxResults: Int,
    val timeoutSeconds: Long
)
