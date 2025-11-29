package com.alfredoalpizar.rag.config

/**
 * Centralized environment variable configuration for document storage.
 *
 * Uses environment variables instead of application.yml for runtime configuration,
 * following 12-factor app principles for cloud-native deployments.
 *
 * Benefits:
 * - Change configuration without redeployment
 * - Per-environment configs via Kubernetes ConfigMaps / ECS task definitions
 * - No sensitive data in source code or config files
 *
 * Configuration Scope:
 * - ✅ Document Storage: Configured here via environment variables
 * - ❌ Conversation Storage: Configured in application.yml via ConversationProperties
 *
 * This separation maintains backward compatibility while allowing document storage
 * to be easily switched between LOCAL and S3 via environment variables.
 */
object Environment {

    // ============================================
    // Ingestion Storage Configuration
    // ============================================

    /**
     * Document storage type: LOCAL | S3
     * Default: LOCAL
     */
    val INGESTION_STORAGE_TYPE: StorageType = StorageType.valueOf(
        System.getenv("INGESTION_STORAGE_TYPE") ?: "LOCAL"
    )

    /**
     * Local filesystem path for documents.
     * Used when INGESTION_STORAGE_TYPE = LOCAL
     * Default: ./docs/samples
     */
    val INGESTION_LOCAL_PATH: String =
        System.getenv("INGESTION_LOCAL_PATH") ?: "./docs/samples"

    /**
     * S3 bucket name for document storage.
     * Used when INGESTION_STORAGE_TYPE = S3
     */
    val INGESTION_S3_BUCKET: String =
        System.getenv("INGESTION_S3_BUCKET") ?: ""

    /**
     * S3 key prefix for documents (e.g., "documents/").
     * Used when INGESTION_STORAGE_TYPE = S3
     * Default: documents/
     */
    val INGESTION_S3_PREFIX: String =
        System.getenv("INGESTION_S3_PREFIX") ?: "documents/"

    /**
     * AWS region for S3.
     * Used when INGESTION_STORAGE_TYPE = S3
     * Default: us-east-1
     */
    val INGESTION_S3_REGION: String =
        System.getenv("INGESTION_S3_REGION") ?: "us-east-1"

    // ============================================
    // DynamoDB Lock Configuration
    // ============================================

    /**
     * DynamoDB endpoint URL.
     * For local DynamoDB: http://localhost:8000
     * For AWS DynamoDB: leave empty or set to null (uses AWS default endpoints)
     * Default: http://localhost:8000 (local development)
     */
    val DYNAMODB_ENDPOINT: String? =
        System.getenv("DYNAMODB_ENDPOINT")?.takeIf { it.isNotBlank() } ?: "http://localhost:8000"

    /**
     * DynamoDB table name for document locks.
     * Default: rag-document-locks
     */
    val DYNAMODB_LOCK_TABLE: String =
        System.getenv("DYNAMODB_LOCK_TABLE") ?: "rag-document-locks"

    /**
     * AWS region for DynamoDB.
     * Default: us-east-1
     */
    val DYNAMODB_REGION: String =
        System.getenv("DYNAMODB_REGION") ?: "us-east-1"

    /**
     * Lock lease duration in seconds.
     * Locks expire after this duration to prevent deadlocks.
     * Default: 300 seconds (5 minutes)
     */
    val LOCK_LEASE_DURATION_SECONDS: Long =
        System.getenv("LOCK_LEASE_DURATION_SECONDS")?.toLongOrNull() ?: 300

    /**
     * Instance ID for lock ownership tracking.
     * Defaults to hostname or random UUID.
     */
    val INSTANCE_ID: String =
        System.getenv("INSTANCE_ID")
            ?: System.getenv("HOSTNAME")
            ?: java.util.UUID.randomUUID().toString()

    /**
     * Number of worker threads for parallel reindex operations.
     * Default: 10
     */
    val REINDEX_WORKER_THREADS: Int =
        System.getenv("REINDEX_WORKER_THREADS")?.toIntOrNull() ?: 10

    // ============================================
    // Fireworks/Qwen API Configuration
    // ============================================

    val FIREWORKS_BASE_URL: String =
        System.getenv("FIREWORKS_BASE_URL") ?: "https://api.fireworks.ai/inference/v1"

    val FIREWORKS_API_KEY: String =
        System.getenv("FIREWORKS_API_KEY") ?: ""

    val QWEN_THINKING_MODEL: String =
        System.getenv("QWEN_THINKING_MODEL") ?: "accounts/fireworks/models/qwen3-235b-a22b-instruct-2507"

    val QWEN_INSTRUCT_MODEL: String =
        System.getenv("QWEN_INSTRUCT_MODEL") ?: "accounts/fireworks/models/qwen3-235b-a22b-instruct-2507"

    val QWEN_EMBEDDING_MODEL: String =
        System.getenv("QWEN_EMBEDDING_MODEL") ?: "accounts/fireworks/models/qwen3-embedding-8b"

    val QWEN_TIMEOUT_SECONDS: Long =
        System.getenv("QWEN_TIMEOUT_SECONDS")?.toLongOrNull() ?: 60

    val QWEN_MAX_RETRIES: Int =
        System.getenv("QWEN_MAX_RETRIES")?.toIntOrNull() ?: 3

    // ============================================
    // DeepSeek API Configuration
    // ============================================

    val DEEPSEEK_BASE_URL: String =
        System.getenv("DEEPSEEK_BASE_URL") ?: "https://api.deepseek.com"

    val DEEPSEEK_API_KEY: String =
        System.getenv("DEEPSEEK_API_KEY") ?: ""

    val DEEPSEEK_CHAT_MODEL: String =
        System.getenv("DEEPSEEK_CHAT_MODEL") ?: "deepseek-chat"

    val DEEPSEEK_REASONING_MODEL: String =
        System.getenv("DEEPSEEK_REASONING_MODEL") ?: "deepseek-reasoner"

    val DEEPSEEK_EMBEDDING_MODEL: String =
        System.getenv("DEEPSEEK_EMBEDDING_MODEL") ?: "deepseek-embedding-v2"

    val DEEPSEEK_TIMEOUT_SECONDS: Long =
        System.getenv("DEEPSEEK_TIMEOUT_SECONDS")?.toLongOrNull() ?: 60

    val DEEPSEEK_MAX_RETRIES: Int =
        System.getenv("DEEPSEEK_MAX_RETRIES")?.toIntOrNull() ?: 3

    // ============================================
    // ChromaDB Configuration
    // ============================================

    val CHROMADB_BASE_URL: String =
        System.getenv("CHROMADB_BASE_URL") ?: "http://localhost:8001"

    val CHROMADB_TENANT: String =
        System.getenv("CHROMADB_TENANT") ?: "default"

    val CHROMADB_DATABASE: String =
        System.getenv("CHROMADB_DATABASE") ?: "default"

    val CHROMADB_COLLECTION_NAME: String =
        System.getenv("CHROMADB_COLLECTION") ?: "knowledge_base"

    val CHROMADB_MAX_RESULTS: Int =
        System.getenv("CHROMADB_MAX_RESULTS")?.toIntOrNull() ?: 5

    val CHROMADB_TIMEOUT_SECONDS: Long =
        System.getenv("CHROMADB_TIMEOUT_SECONDS")?.toLongOrNull() ?: 30

    // ============================================
    // Loop/Orchestrator Configuration
    // ============================================

    val LOOP_MAX_ITERATIONS: Int =
        System.getenv("LOOP_MAX_ITERATIONS")?.toIntOrNull() ?: 10

    val LOOP_MAX_TOKENS: Int =
        System.getenv("LOOP_MAX_TOKENS")?.toIntOrNull() ?: 4096

    val LOOP_TEMPERATURE: Double =
        System.getenv("LOOP_TEMPERATURE")?.toDoubleOrNull() ?: 0.7

    val LOOP_MODEL_STRATEGY: String =
        System.getenv("LOOP_MODEL_STRATEGY") ?: "qwen_single_instruct"

    val LOOP_USE_REASONING_MODEL: Boolean =
        System.getenv("LOOP_USE_REASONING_MODEL")?.toBoolean() ?: false

    val LOOP_THINKING_TIMEOUT_SECONDS: Long =
        System.getenv("LOOP_THINKING_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120

    val LOOP_THINKING_SHOW_REASONING: Boolean =
        System.getenv("LOOP_THINKING_SHOW_REASONING")?.toBoolean() ?: true

    val LOOP_INSTRUCT_TIMEOUT_SECONDS: Long =
        System.getenv("LOOP_INSTRUCT_TIMEOUT_SECONDS")?.toLongOrNull() ?: 30

    val LOOP_INSTRUCT_MAX_RETRIES: Int =
        System.getenv("LOOP_INSTRUCT_MAX_RETRIES")?.toIntOrNull() ?: 3

    val LOOP_STREAMING_MODE: String =
        System.getenv("LOOP_STREAMING_MODE") ?: "status_only"

    val LOOP_STREAMING_SHOW_TOOL_CALLS: Boolean =
        System.getenv("LOOP_STREAMING_SHOW_TOOL_CALLS")?.toBoolean() ?: true

    val LOOP_STREAMING_SHOW_RAG_QUERIES: Boolean =
        System.getenv("LOOP_STREAMING_SHOW_RAG_QUERIES")?.toBoolean() ?: true

    val LOOP_STREAMING_SHOW_REASONING_TRACES: Boolean =
        System.getenv("LOOP_STREAMING_SHOW_REASONING_TRACES")?.toBoolean() ?: false

    val LOOP_STREAMING_BUFFER_SIZE: Int =
        System.getenv("LOOP_STREAMING_BUFFER_SIZE")?.toIntOrNull() ?: 256

    // ============================================
    // Conversation Management Configuration
    // ============================================

    val CONVERSATION_STORAGE_MODE: String =
        System.getenv("CONVERSATION_STORAGE_MODE") ?: "in-memory"

    val CONVERSATION_MAX_CONTEXT_TOKENS: Int =
        System.getenv("CONVERSATION_MAX_CONTEXT_TOKENS")?.toIntOrNull() ?: 100000

    val CONVERSATION_COMPRESSION_THRESHOLD: Int =
        System.getenv("CONVERSATION_COMPRESSION_THRESHOLD")?.toIntOrNull() ?: 80000

    val CONVERSATION_ROLLING_WINDOW_SIZE: Int =
        System.getenv("CONVERSATION_ROLLING_WINDOW_SIZE")?.toIntOrNull() ?: 20

    val CONVERSATION_TTL_HOURS: Long =
        System.getenv("CONVERSATION_TTL_HOURS")?.toLongOrNull() ?: 720

    val CONVERSATION_CLEANUP_INTERVAL_MINUTES: Long =
        System.getenv("CONVERSATION_CLEANUP_INTERVAL_MINUTES")?.toLongOrNull() ?: 60

    val CONVERSATION_ARCHIVE_ENABLED: Boolean =
        System.getenv("CONVERSATION_ARCHIVE_ENABLED")?.toBoolean() ?: false

    val CONVERSATION_ARCHIVE_S3_BUCKET: String =
        System.getenv("ARCHIVE_S3_BUCKET") ?: ""

    val CONVERSATION_ARCHIVE_AFTER_DAYS: Int =
        System.getenv("CONVERSATION_ARCHIVE_AFTER_DAYS")?.toIntOrNull() ?: 90

    // ============================================
    // RAG Ingestion Configuration
    // ============================================

    val INGESTION_DOCS_PATH: String =
        System.getenv("INGESTION_DOCS_PATH") ?: "./docs/samples"

    val INGESTION_AUTO_INGEST_ON_STARTUP: Boolean =
        System.getenv("INGESTION_AUTO_INGEST_ON_STARTUP")?.toBoolean() ?: false

    val INGESTION_BATCH_SIZE: Int =
        System.getenv("INGESTION_BATCH_SIZE")?.toIntOrNull() ?: 100

    val INGESTION_EMBEDDING_PROVIDER: String =
        System.getenv("INGESTION_EMBEDDING_PROVIDER") ?: "qwen"

    val INGESTION_EMBEDDING_NORMALIZE: Boolean =
        System.getenv("INGESTION_EMBEDDING_NORMALIZE")?.toBoolean() ?: true

    // ============================================
    // Enums
    // ============================================

    enum class StorageType {
        LOCAL,  // Local filesystem
        S3      // AWS S3 or S3-compatible storage
    }

    enum class ModelStrategy {
        DEEPSEEK_SINGLE,      // Use DeepSeek (existing logic)
        QWEN_SINGLE_THINKING, // Use Qwen-Thinking for all
        QWEN_SINGLE_INSTRUCT  // Use Qwen-Instruct for all
    }

    enum class StreamingMode {
        STATUS_ONLY,
        FULL_REASONING
    }
}
