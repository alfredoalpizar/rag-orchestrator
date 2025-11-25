package com.alfredoalpizar.rag.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "conversation")
data class ConversationProperties(
    val storageMode: StorageMode,
    val maxContextTokens: Int,
    val compressionThreshold: Int,
    val rollingWindowSize: Int,
    val ttlHours: Long,
    val cleanupIntervalMinutes: Long,
    val archive: ArchiveProperties
) {
    enum class StorageMode {
        IN_MEMORY,
        DATABASE
    }

    data class ArchiveProperties(
        val enabled: Boolean,
        val s3Bucket: String?,
        val archiveAfterDays: Int
    )
}
