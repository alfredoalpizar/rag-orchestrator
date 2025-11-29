package com.alfredoalpizar.rag.service.ingestion.embedding

/**
 * Interface for embedding providers.
 *
 * Supports multiple embedding models (DeepSeek, Qwen, OpenAI, Ollama, etc.)
 * through a common interface for generating text embeddings.
 */
interface EmbeddingProvider {
    /**
     * Generate embeddings for multiple texts in batch.
     * Returns embeddings in the same order as input texts.
     *
     * @param texts List of texts to embed
     * @return List of embedding vectors (each vector is a list of floats)
     */
    suspend fun embed(texts: List<String>): List<List<Float>>

    /**
     * Generate embedding for a single text.
     *
     * @param text Text to embed
     * @return Embedding vector as a list of floats
     */
    suspend fun embed(text: String): List<Float> {
        return embed(listOf(text)).first()
    }

    /**
     * Get the embedding dimensions for this provider's model.
     *
     * @return Number of dimensions in the embedding vector
     */
    fun getDimensions(): Int

    /**
     * Get the model name for this provider.
     *
     * @return Model name (e.g., "deepseek-embedding-v2", "text-embedding-v2")
     */
    fun getModelName(): String

    /**
     * Get the provider name.
     *
     * @return Provider name (e.g., "deepseek", "qwen")
     */
    fun getProviderName(): String
}

/**
 * Metadata about an embedding model.
 */
data class EmbeddingModelInfo(
    val provider: String,
    val model: String,
    val dimensions: Int
)
