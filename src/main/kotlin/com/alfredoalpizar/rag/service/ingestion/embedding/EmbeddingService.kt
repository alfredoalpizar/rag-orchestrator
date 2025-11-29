package com.alfredoalpizar.rag.service.ingestion.embedding

import com.alfredoalpizar.rag.client.deepseek.DeepSeekClient
import com.alfredoalpizar.rag.client.qwen.QwenClient
import com.alfredoalpizar.rag.config.Environment
import mu.KotlinLogging
import org.springframework.stereotype.Service
import kotlin.math.sqrt

/**
 * Main embedding service that routes to the configured embedding provider.
 *
 * Supports:
 * - Multiple providers (DeepSeek, Qwen, extensible to others)
 * - L2 normalization for cosine similarity
 * - Batch processing
 * - Model metadata tracking
 */
@Service
class EmbeddingService(
    private val deepSeekClient: DeepSeekClient,
    private val qwenClient: QwenClient
) {
    private val logger = KotlinLogging.logger {}

    private val provider: EmbeddingProvider by lazy {
        when (Environment.INGESTION_EMBEDDING_PROVIDER.lowercase()) {
            "deepseek" -> {
                logger.info { "Using DeepSeek embedding provider: ${Environment.DEEPSEEK_EMBEDDING_MODEL}" }
                DeepSeekEmbeddingProvider(deepSeekClient)
            }
            "qwen" -> {
                logger.info { "Using Qwen embedding provider: ${Environment.QWEN_EMBEDDING_MODEL}" }
                QwenEmbeddingProvider(qwenClient)
            }
            else -> throw IllegalArgumentException(
                "Unknown embedding provider: ${Environment.INGESTION_EMBEDDING_PROVIDER}. " +
                "Supported providers: deepseek, qwen"
            )
        }
    }

    /**
     * Generate embeddings for multiple texts in batch.
     * Applies L2 normalization if configured.
     *
     * @param texts List of texts to embed
     * @return List of embedding vectors (normalized if configured)
     */
    suspend fun embedBatch(texts: List<String>): List<List<Float>> {
        if (texts.isEmpty()) {
            logger.warn { "Empty text list provided for embedding" }
            return emptyList()
        }

        logger.debug { "Generating embeddings for ${texts.size} texts using ${provider.getProviderName()}" }

        val embeddings = provider.embed(texts)

        return if (Environment.INGESTION_EMBEDDING_NORMALIZE) {
            logger.debug { "Applying L2 normalization to embeddings" }
            embeddings.map { normalizeL2(it) }
        } else {
            embeddings
        }
    }

    /**
     * Generate embedding for a single text.
     * Applies L2 normalization if configured.
     *
     * @param text Text to embed
     * @return Embedding vector (normalized if configured)
     */
    suspend fun embedSingle(text: String): List<Float> {
        return embedBatch(listOf(text)).first()
    }

    /**
     * Get information about the current embedding model.
     *
     * @return Embedding model metadata
     */
    fun getModelInfo(): EmbeddingModelInfo {
        return EmbeddingModelInfo(
            provider = provider.getProviderName(),
            model = provider.getModelName(),
            dimensions = provider.getDimensions()
        )
    }

    /**
     * Apply L2 normalization to an embedding vector.
     * Normalized vectors have a magnitude of 1.0, which enables
     * cosine similarity to be computed via dot product.
     *
     * @param embedding Input embedding vector
     * @return Normalized embedding vector
     */
    private fun normalizeL2(embedding: List<Float>): List<Float> {
        val norm = sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()

        return if (norm > 0f) {
            embedding.map { it / norm }
        } else {
            logger.warn { "Zero-norm embedding encountered, returning original" }
            embedding
        }
    }
}
