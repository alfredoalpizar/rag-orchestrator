package com.alfredoalpizar.rag.config

import com.alfredoalpizar.rag.service.storage.DocumentSerializer
import com.alfredoalpizar.rag.service.storage.DocumentStore
import com.alfredoalpizar.rag.service.storage.LocalDocumentStore
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.io.path.Path

/**
 * Spring configuration for document storage.
 *
 * Creates the appropriate DocumentStore bean based on environment variables.
 * Supports switching between storage implementations without code changes.
 *
 * Storage Types:
 * - LOCAL: Filesystem-based storage (default)
 * - S3: AWS S3 storage (future implementation)
 *
 * Configuration:
 * Set environment variable: INGESTION_STORAGE_TYPE=LOCAL|S3
 */
@Configuration
class DocumentStoreConfig {

    private val logger = KotlinLogging.logger {}

    @Bean
    fun documentStore(serializer: DocumentSerializer): DocumentStore {
        return when (Environment.INGESTION_STORAGE_TYPE) {
            Environment.StorageType.LOCAL -> {
                logger.info { "Using LOCAL document storage at: ${Environment.INGESTION_LOCAL_PATH}" }
                LocalDocumentStore(
                    basePath = Path(Environment.INGESTION_LOCAL_PATH),
                    serializer = serializer
                )
            }
            Environment.StorageType.S3 -> {
                logger.warn { "S3 storage not yet implemented, falling back to LOCAL" }
                // TODO: Implement S3DocumentStore in future phase
                // For now, use local storage with a warning
                LocalDocumentStore(
                    basePath = Path(Environment.INGESTION_LOCAL_PATH),
                    serializer = serializer
                )
                // Future implementation:
                // S3DocumentStore(
                //     bucket = Environment.INGESTION_S3_BUCKET,
                //     prefix = Environment.INGESTION_S3_PREFIX,
                //     region = Environment.INGESTION_S3_REGION,
                //     serializer = serializer
                // )
            }
        }
    }
}
