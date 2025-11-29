package com.alfredoalpizar.rag.service.ingestion.embedding

import com.alfredoalpizar.rag.client.deepseek.DeepSeekClient
import com.alfredoalpizar.rag.config.Environment
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging

/**
 * DeepSeek embedding provider implementation.
 * Wraps DeepSeekClient for generating embeddings.
 */
class DeepSeekEmbeddingProvider(
    private val client: DeepSeekClient
) : EmbeddingProvider {

    private val logger = KotlinLogging.logger {}

    override suspend fun embed(texts: List<String>): List<List<Float>> {
        logger.debug { "Generating DeepSeek embeddings for ${texts.size} texts" }
        return client.embed(texts).awaitSingle()
    }

    override fun getDimensions(): Int {
        // DeepSeek embedding-v2 produces 1536-dimensional vectors
        return 1536
    }

    override fun getModelName(): String {
        return Environment.DEEPSEEK_EMBEDDING_MODEL
    }

    override fun getProviderName(): String {
        return "deepseek"
    }
}
