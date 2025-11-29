package com.alfredoalpizar.rag.service.lock

import com.alfredoalpizar.rag.config.Environment
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * DynamoDB-based distributed document locking.
 *
 * Features:
 * - Works across multiple app instances (true distributed lock)
 * - Automatic expiration via DynamoDB TTL (prevents deadlocks)
 * - Optimistic lock acquisition (uses DynamoDB conditional writes)
 * - Exponential backoff retry (3 attempts)
 * - Token-based ownership verification
 *
 * Lock Flow:
 * 1. Try to insert lock record with condition: "doc_id must not exist OR lock expired"
 * 2. If successful → lock acquired
 * 3. Execute operation
 * 4. Delete lock record (or let TTL clean up)
 *
 * Configuration (Environment Variables):
 * - DYNAMODB_ENDPOINT: http://localhost:8000 (local) or empty (AWS)
 * - DYNAMODB_LOCK_TABLE: Table name (default: rag-document-locks)
 * - DYNAMODB_REGION: AWS region (default: us-east-1)
 * - INSTANCE_ID: Instance identifier for debugging
 * - LOCK_LEASE_DURATION_SECONDS: Lock expiration (default: 300)
 *
 * Table Schema:
 * - Primary Key: doc_id (String)
 * - Attributes: locked_by, lock_token, locked_at, expires_at
 * - TTL: expires_at (automatic cleanup)
 */
@Component
class DynamoDBDocumentLock : DocumentLock {

    private val logger = KotlinLogging.logger {}

    private val tableName = Environment.DYNAMODB_LOCK_TABLE
    private val instanceId = Environment.INSTANCE_ID

    private val dynamoDb: DynamoDbAsyncClient = run {
        val clientBuilder = DynamoDbAsyncClient.builder()
            .region(Region.of(Environment.DYNAMODB_REGION))
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofSeconds(10))
                    .apiCallAttemptTimeout(Duration.ofSeconds(5))
                    .build()
            )

        // If DYNAMODB_ENDPOINT is set, use it (local DynamoDB)
        // Otherwise, use default AWS endpoints (production)
        if (Environment.DYNAMODB_ENDPOINT != null) {
            logger.info { "Initializing DynamoDB client with endpoint: ${Environment.DYNAMODB_ENDPOINT}" }
            clientBuilder.endpointOverride(URI.create(Environment.DYNAMODB_ENDPOINT))
        } else {
            logger.info { "Initializing DynamoDB client with AWS endpoints (region: ${Environment.DYNAMODB_REGION})" }
        }

        clientBuilder.build().also {
            logger.info { "DynamoDB document locking initialized (table: $tableName, instance: $instanceId)" }
        }
    }

    override suspend fun <T> withLock(
        docId: String,
        leaseDuration: Duration,
        block: suspend () -> T
    ): T {
        val token = acquireLockWithRetry(docId, leaseDuration, maxRetries = 3)

        return try {
            logger.debug { "Acquired lock for document: $docId (token: ${token.value}, instance: $instanceId)" }
            block()
        } finally {
            releaseLock(docId, token)
        }
    }

    /**
     * Try to acquire a lock once (no retry).
     *
     * @return LockToken if successful, null if lock held by another instance
     */
    private suspend fun tryAcquireLock(
        docId: String,
        leaseDuration: Duration
    ): LockToken? {
        val token = UUID.randomUUID().toString()
        val now = Instant.now()
        val expiresAt = now.plus(leaseDuration)

        return try {
            val request = PutItemRequest.builder()
                .tableName(tableName)
                .item(mapOf(
                    "doc_id" to AttributeValue.builder().s(docId).build(),
                    "locked_by" to AttributeValue.builder().s(instanceId).build(),
                    "lock_token" to AttributeValue.builder().s(token).build(),
                    "locked_at" to AttributeValue.builder().n(now.toEpochMilli().toString()).build(),
                    "expires_at" to AttributeValue.builder().n(expiresAt.epochSecond.toString()).build()
                ))
                // Conditional write: only succeed if lock doesn't exist OR expired
                .conditionExpression("attribute_not_exists(doc_id) OR expires_at < :now")
                .expressionAttributeValues(mapOf(
                    ":now" to AttributeValue.builder().n(now.epochSecond.toString()).build()
                ))
                .build()

            suspendCoroutine { continuation ->
                dynamoDb.putItem(request).whenComplete { _, error ->
                    when {
                        error == null -> {
                            logger.debug { "Lock acquired for $docId (token: $token)" }
                            continuation.resume(LockToken(token))
                        }
                        error.cause is ConditionalCheckFailedException -> {
                            logger.debug { "Lock already held for $docId" }
                            continuation.resume(null)
                        }
                        else -> {
                            logger.error(error) { "Failed to acquire lock for $docId" }
                            continuation.resumeWithException(error)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e.cause is ConditionalCheckFailedException) {
                null // Lock held by another instance
            } else {
                throw e
            }
        }
    }

    /**
     * Release a lock.
     *
     * Only succeeds if we still own the lock (token matches).
     * If lock expired or already released, this is a no-op (safe).
     */
    private suspend fun releaseLock(docId: String, token: LockToken) {
        try {
            val request = DeleteItemRequest.builder()
                .tableName(tableName)
                .key(mapOf(
                    "doc_id" to AttributeValue.builder().s(docId).build()
                ))
                // Only delete if we still own the lock (prevent releasing someone else's lock)
                .conditionExpression("lock_token = :token")
                .expressionAttributeValues(mapOf(
                    ":token" to AttributeValue.builder().s(token.value).build()
                ))
                .build()

            suspendCoroutine<Unit> { continuation ->
                dynamoDb.deleteItem(request).whenComplete { _, error ->
                    when {
                        error == null -> {
                            logger.debug { "Released lock for document: $docId" }
                            continuation.resume(Unit)
                        }
                        error.cause is ConditionalCheckFailedException -> {
                            // Lock already released/expired or stolen (both OK - not an error)
                            logger.debug { "Lock for $docId already released or expired" }
                            continuation.resume(Unit)
                        }
                        else -> {
                            // Non-fatal error - TTL will clean up eventually
                            logger.warn(error) { "Failed to release lock for $docId (non-fatal, TTL will clean up)" }
                            continuation.resume(Unit)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Non-fatal - TTL will clean up the lock
            logger.warn(e) { "Error releasing lock for $docId (non-fatal, TTL will clean up)" }
        }
    }

    /**
     * Acquire lock with exponential backoff retry.
     *
     * Useful when multiple instances try to lock the same document simultaneously.
     * Retries with increasing delays: 100ms, 200ms, 400ms
     */
    private suspend fun acquireLockWithRetry(
        docId: String,
        leaseDuration: Duration,
        maxRetries: Int
    ): LockToken {
        var attempt = 0

        while (attempt < maxRetries) {
            val token = tryAcquireLock(docId, leaseDuration)
            if (token != null) {
                return token
            }

            attempt++
            if (attempt < maxRetries) {
                val backoffMs = (100 * Math.pow(2.0, (attempt - 1).toDouble())).toLong()
                logger.debug { "Lock acquisition failed for $docId, retrying in ${backoffMs}ms (attempt $attempt/$maxRetries)" }
                delay(backoffMs)
            }
        }

        // All retries exhausted - try to get current lock holder info for debugging
        val lockHolder = getCurrentLockHolder(docId)
        throw LockAcquisitionException(
            "Could not acquire lock for document $docId after $maxRetries attempts (held by: ${lockHolder ?: "unknown"})",
            docId = docId,
            lockedBy = lockHolder
        )
    }

    /**
     * Get the current lock holder for debugging.
     * Returns instance ID if lock exists, null otherwise.
     */
    private suspend fun getCurrentLockHolder(docId: String): String? {
        return try {
            val request = GetItemRequest.builder()
                .tableName(tableName)
                .key(mapOf(
                    "doc_id" to AttributeValue.builder().s(docId).build()
                ))
                .build()

            suspendCoroutine { continuation ->
                dynamoDb.getItem(request).whenComplete { response, error ->
                    if (error == null && response.hasItem()) {
                        val lockedBy = response.item()["locked_by"]?.s()
                        continuation.resume(lockedBy)
                    } else {
                        continuation.resume(null)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get lock holder for $docId" }
            null
        }
    }
}
