package com.alfredoalpizar.rag.service.ingestion.embedding

import com.alfredoalpizar.rag.client.qwen.QwenClient
import com.alfredoalpizar.rag.config.Environment
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging

/**
 * Qwen embedding provider implementation.
 * Wraps QwenClient for generating embeddings.
 */
class QwenEmbeddingProvider(
    private val client: QwenClient
) : EmbeddingProvider {

    private val logger = KotlinLogging.logger {}

    override suspend fun embed(texts: List<String>): List<List<Float>> {
        logger.debug { "Generating Qwen embeddings for ${texts.size} texts" }
        return client.embed(texts).awaitSingle()
    }

    override fun getDimensions(): Int {
        // Qwen text-embedding-v2 produces 1536-dimensional vectors
        return 1536
    }

    override fun getModelName(): String {
        return Environment.QWEN_EMBEDDING_MODEL
    }

    override fun getProviderName(): String {
        return "qwen"
    }
}
