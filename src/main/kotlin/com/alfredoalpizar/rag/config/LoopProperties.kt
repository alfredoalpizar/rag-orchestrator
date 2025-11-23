package com.alfredoalpizar.rag.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "loop")
data class LoopProperties(
    val maxIterations: Int,
    val maxTokens: Int,
    val temperature: Double,
    val useReasoningModel: Boolean,
    val streaming: StreamingProperties
) {
    data class StreamingProperties(
        val mode: StreamingMode,
        val showToolCalls: Boolean,
        val showRagQueries: Boolean,
        val bufferSize: Int
    )

    enum class StreamingMode {
        STATUS_ONLY,
        FULL_REASONING
    }
}
