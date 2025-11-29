package com.alfredoalpizar.rag.service.lock

import java.time.Duration

/**
 * Abstraction for distributed document locking.
 *
 * Provides exclusive access to a document during reindex operations,
 * preventing race conditions when multiple instances try to update the same document.
 */
interface DocumentLock {
    /**
     * Acquire a lock on a document and execute the given block.
     *
     * If lock cannot be acquired (another instance holds it), throws LockAcquisitionException.
     * Lock is automatically released after block executes (success or failure).
     *
     * @param docId Document ID to lock
     * @param leaseDuration How long the lock is valid (prevents deadlocks if holder crashes)
     * @param block Code to execute while holding the lock
     * @return Result of block execution
     * @throws LockAcquisitionException if lock cannot be acquired after retries
     */
    suspend fun <T> withLock(
        docId: String,
        leaseDuration: Duration = Duration.ofMinutes(5),
        block: suspend () -> T
    ): T
}

/**
 * Token proving lock ownership.
 * Must be provided to release the lock.
 */
data class LockToken(val value: String)

/**
 * Exception thrown when lock cannot be acquired.
 *
 * Indicates another instance is currently processing this document,
 * or lock acquisition failed due to network/service issues.
 */
class LockAcquisitionException(
    message: String,
    val docId: String,
    val lockedBy: String? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)
