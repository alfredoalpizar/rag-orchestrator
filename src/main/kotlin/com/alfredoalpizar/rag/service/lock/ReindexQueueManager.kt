package com.alfredoalpizar.rag.service.lock

import com.alfredoalpizar.rag.config.Environment
import com.alfredoalpizar.rag.coroutine.currentMDCContext
import com.alfredoalpizar.rag.model.document.Document
import com.alfredoalpizar.rag.service.ingestion.IngestionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.slf4j.MDC
import org.springframework.stereotype.Component
import jakarta.annotation.PreDestroy
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages asynchronous document reindexing with DynamoDB distributed locks.
 *
 * Architecture:
 * - HTTP requests return immediately after saving to storage
 * - Reindex operations (delete vectors + re-embed + upsert) happen in background
 * - Multi-threaded executor (configurable workers) allows parallel processing
 * - DynamoDB locks prevent race conditions when multiple instances update same document
 *
 * Features:
 * - ✅ Distributed locking (works across multiple instances)
 * - ✅ Parallel processing (different documents processed simultaneously)
 * - ✅ Fast HTTP responses (~50ms instead of ~5s)
 * - ✅ Automatic lock expiration (prevents deadlocks)
 * - ⚠️ Vectors lag storage by 5-10 seconds (acceptable for knowledge base)
 *
 * Lock Behavior:
 * - Each document gets a distributed lock before reindexing
 * - If lock cannot be acquired (another instance processing), operation is skipped
 * - Locks expire after 5 minutes to prevent deadlocks
 * - Lock acquisition failures are tracked in metrics
 *
 * Configuration (Environment Variables):
 * - REINDEX_WORKER_THREADS: Number of parallel workers (default: 10)
 * - DYNAMODB_ENDPOINT: DynamoDB endpoint (default: http://localhost:8000)
 * - DYNAMODB_LOCK_TABLE: Lock table name (default: rag-document-locks)
 * - LOCK_LEASE_DURATION_SECONDS: Lock expiration (default: 300)
 */
@Component
class ReindexQueueManager(
    private val ingestionService: IngestionService,
    private val documentLock: DocumentLock
) {
    private val logger = KotlinLogging.logger {}

    // Multi-threaded executor - allows parallel processing of different documents
    private val reindexExecutor = Executors.newFixedThreadPool(Environment.REINDEX_WORKER_THREADS) { runnable ->
        Thread(runnable, "reindex-worker").apply {
            isDaemon = false // Ensure tasks complete before JVM shutdown
        }
    }

    // Coroutine scope for async operations
    private val reindexScope = CoroutineScope(
        SupervisorJob() + reindexExecutor.asCoroutineDispatcher()
    )

    // Metrics
    private val queuedCount = AtomicInteger(0)
    private val completedCount = AtomicInteger(0)
    private val failedCount = AtomicInteger(0)
    private val lockAcquisitionFailures = AtomicInteger(0)
    private val failuresByDocId = ConcurrentHashMap<String, Int>()

    init {
        logger.info("ReindexQueueManager initialized with ${Environment.REINDEX_WORKER_THREADS} worker threads")
    }

    /**
     * Queue a document for reindexing (async).
     *
     * This method returns immediately. The actual reindex operation
     * (delete vectors + chunk + embed + upsert) happens in the background
     * with distributed locking to prevent race conditions.
     *
     * @param docId Document ID
     * @param document Document to reindex
     * @param operationType Type of operation (for logging)
     */
    fun queueReindex(docId: String, document: Document, operationType: String = "update") {
        queuedCount.incrementAndGet()

        // Capture MDC context from HTTP request thread
        val mdcContext = currentMDCContext()
        val requestId = MDC.get("requestId") ?: "NO-REQ-ID"

        logger.info("Queuing reindex for document: $docId (operation: $operationType, requestId: $requestId, queue size: ${queuedCount.get() - completedCount.get() - failedCount.get()})")

        reindexScope.launch(mdcContext) {
            try {
                // MDC context automatically propagated via mdcContext parameter

                // Acquire distributed lock before processing
                documentLock.withLock(docId, Duration.ofMinutes(5)) {
                    logger.debug("Lock acquired for document: $docId, starting reindex")

                    // Re-ingest (chunk + embed + upsert)
                    // Note: ingestDocument() internally deletes old chunks before upserting new ones
                    val result = ingestionService.ingestDocument(document)

                    if (result.status == com.alfredoalpizar.rag.service.ingestion.IngestionStatus.SUCCESS) {
                        completedCount.incrementAndGet()
                        failuresByDocId.remove(docId) // Clear failure count on success
                        logger.info("✅ Successfully reindexed document: $docId (${result.chunksCreated} chunks)")
                    } else {
                        failedCount.incrementAndGet()
                        val failureCount = failuresByDocId.compute(docId) { _, count -> (count ?: 0) + 1 } ?: 1
                        logger.error("❌ Failed to reindex document: $docId (status: ${result.status}, error: ${result.error}, failures: $failureCount)")

                        if (failureCount >= 3) {
                            logger.error("Document $docId has failed $failureCount times - consider manual intervention")
                        }
                    }
                }
            } catch (e: LockAcquisitionException) {
                // Another instance is already processing this document
                lockAcquisitionFailures.incrementAndGet()
                logger.warn("Could not acquire lock for document: $docId (held by: ${e.lockedBy}) - skipping reindex (another instance processing)")
                // Don't count as failure - another instance is handling it
            } catch (e: Exception) {
                failedCount.incrementAndGet()
                val failureCount = failuresByDocId.compute(docId) { _, count -> (count ?: 0) + 1 } ?: 1
                logger.error("❌ Exception during reindex for document: $docId (failures: $failureCount)", e)

                if (failureCount >= 3) {
                    logger.error("Document $docId has failed $failureCount times - consider manual intervention")
                }
            }
        }
    }

    /**
     * Get current queue metrics.
     *
     * @return Map of metric name to value
     */
    fun getMetrics(): Map<String, Any> {
        val queued = queuedCount.get()
        val completed = completedCount.get()
        val failed = failedCount.get()
        val lockFailures = lockAcquisitionFailures.get()
        val pending = queued - completed - failed

        return mapOf(
            "queued_total" to queued,
            "completed" to completed,
            "failed" to failed,
            "pending" to pending,
            "lock_acquisition_failures" to lockFailures,
            "failure_rate" to if (queued > 0) (failed.toDouble() / queued * 100) else 0.0,
            "documents_with_failures" to failuresByDocId.size,
            "worker_threads" to Environment.REINDEX_WORKER_THREADS
        )
    }

    /**
     * Shutdown the reindex queue gracefully.
     * Waits for pending operations to complete.
     */
    @PreDestroy
    fun shutdown() {
        logger.info("Shutting down reindex queue (pending: ${queuedCount.get() - completedCount.get() - failedCount.get()})")
        reindexExecutor.shutdown()
        // Executor will wait for pending tasks to complete before JVM shutdown (isDaemon = false)
    }
}
