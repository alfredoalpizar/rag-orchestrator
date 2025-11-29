package com.alfredoalpizar.rag.service.chromadb

import com.alfredoalpizar.rag.config.Environment
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages ChromaDB collections, handling UUID resolution and caching.
 *
 * ChromaDB v2 requires collection UUIDs in API paths, not collection names.
 * This service handles:
 * - Creating collections if they don't exist
 * - Resolving collection names to UUIDs
 * - Caching UUIDs to avoid repeated API calls
 */
@Component
class ChromaDBCollectionManager {

    // Create WebClient configured for ChromaDB
    private val webClient = WebClient.builder()
        .baseUrl(Environment.CHROMADB_BASE_URL)
        .build()
    private val logger = KotlinLogging.logger {}
    private val collectionUuidCache = ConcurrentHashMap<String, String>()

    /**
     * Initialize ChromaDB database on application startup.
     * This prevents repeated database creation attempts on every operation.
     */
    @PostConstruct
    fun initializeDatabase() {
        runBlocking {
            try {
                logger.info("Initializing ChromaDB database: ${Environment.CHROMADB_DATABASE}")
                createDatabaseIfNotExists()
                logger.info("ChromaDB database initialized successfully")
            } catch (e: Exception) {
                logger.warn("Database initialization completed with: ${e.message}")
            }
        }
    }

    /**
     * Get or create the collection and return its UUID.
     * Results are cached to avoid repeated API calls.
     */
    suspend fun getCollectionUuid(collectionName: String = Environment.CHROMADB_COLLECTION_NAME): String? {
        // Database is created on startup via @PostConstruct - no need to check repeatedly

        // Check cache first
        collectionUuidCache[collectionName]?.let { return it }

        try {
            // List existing collections to find UUID
            val existingUuid = findCollectionUuid(collectionName)
            if (existingUuid != null) {
                collectionUuidCache[collectionName] = existingUuid
                logger.debug("Found existing collection '$collectionName' with UUID: $existingUuid")
                return existingUuid
            }

            // Collection doesn't exist, create it
            val createdUuid = createCollection(collectionName)
            if (createdUuid != null) {
                collectionUuidCache[collectionName] = createdUuid
                logger.info("Created collection '$collectionName' with UUID: $createdUuid")
                return createdUuid
            }

            logger.error("Failed to get or create collection: $collectionName")
            return null

        } catch (e: Exception) {
            logger.error("Error getting collection UUID for: $collectionName", e)
            return null
        }
    }

    /**
     * Create the ChromaDB database if it doesn't exist.
     * This is required for ChromaDB v2 API before creating collections.
     *
     * Returns silently if database already exists (409 Conflict is expected).
     */
    private suspend fun createDatabaseIfNotExists(
        tenant: String = Environment.CHROMADB_TENANT,
        database: String = Environment.CHROMADB_DATABASE
    ) {
        try {
            logger.debug("Ensuring database exists: $database in tenant: $tenant")

            val createRequest = mapOf("name" to database)

            webClient.post()
                .uri("/api/v2/tenants/$tenant/databases")
                .bodyValue(createRequest)
                .retrieve()
                .bodyToMono(Void::class.java)
                .timeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
                .awaitSingleOrNull()

            logger.debug("Created/verified database: $database in tenant: $tenant")

        } catch (e: Exception) {
            // Database might already exist (409 Conflict) - that's OK
            // Just log at debug level and continue
            logger.debug("Database creation attempt completed: ${e.message}")
        }
    }

    /**
     * Find the UUID of an existing collection by name.
     */
    private suspend fun findCollectionUuid(collectionName: String): String? {
        logger.debug("Looking for existing collection: $collectionName")

        val response = webClient.get()
            .uri("/api/v2/tenants/${Environment.CHROMADB_TENANT}/databases/${Environment.CHROMADB_DATABASE}/collections")
            .retrieve()
            .bodyToMono(List::class.java)
            .timeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
            .onErrorResume {
                logger.debug("Failed to list collections: ${it.message}")
                Mono.just(emptyList<Map<String, Any>>())
            }
            .awaitSingleOrNull() ?: emptyList<Any>()

        @Suppress("UNCHECKED_CAST")
        val collections = response as? List<Map<String, Any>> ?: emptyList()

        return collections
            .find { it["name"] == collectionName }
            ?.get("id") as? String
    }

    /**
     * Create a new collection and return its UUID.
     */
    private suspend fun createCollection(collectionName: String): String? {
        logger.debug("Creating collection: $collectionName")

        val createRequest = mapOf(
            "name" to collectionName,
            "get_or_create" to true
        )

        return try {
            val response = webClient.post()
                .uri("/api/v2/tenants/${Environment.CHROMADB_TENANT}/databases/${Environment.CHROMADB_DATABASE}/collections")
                .bodyValue(createRequest)
                .retrieve()
                .bodyToMono(Map::class.java)
                .timeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
                .awaitSingleOrNull()

            @Suppress("UNCHECKED_CAST")
            val collection = response as? Map<String, Any>
            collection?.get("id") as? String

        } catch (e: Exception) {
            logger.error("Failed to create collection: $collectionName", e)
            null
        }
    }

    /**
     * Clear the cache (useful for testing or if collection is deleted externally).
     */
    fun clearCache() {
        collectionUuidCache.clear()
        logger.debug("Cleared collection UUID cache")
    }
}