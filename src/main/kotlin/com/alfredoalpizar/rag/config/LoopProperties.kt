package com.alfredoalpizar.rag.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "loop")
data class LoopProperties(
    val maxIterations: Int,
    val maxTokens: Int,
    val temperature: Double,

    // Model strategy selection
    val modelStrategy: ModelStrategy = ModelStrategy.DEEPSEEK_SINGLE,

    // Legacy: Use DeepSeek reasoner vs chat (for backward compatibility)
    val useReasoningModel: Boolean = false,

    // Thinking model settings
    val thinking: ThinkingModelProperties = ThinkingModelProperties(120, true),

    // Instruct model settings
    val instruct: InstructModelProperties = InstructModelProperties(30, 3),

    val streaming: StreamingProperties = StreamingProperties(
        StreamingMode.STATUS_ONLY,
        true,
        true,
        false,
        256
    )
) {
    data class ThinkingModelProperties(
        val timeoutSeconds: Long,
        val showReasoningTraces: Boolean
    )

    data class InstructModelProperties(
        val timeoutSeconds: Long,
        val maxRetries: Int
    )

    enum class ModelStrategy {
        DEEPSEEK_SINGLE,      // Use DeepSeek (existing logic)
        QWEN_SINGLE_THINKING, // Use Qwen-Thinking for all
        QWEN_SINGLE_INSTRUCT  // Use Qwen-Instruct for all
    }

    data class StreamingProperties(
        val mode: StreamingMode,
        val showToolCalls: Boolean,
        val showRagQueries: Boolean,
        val showReasoningTraces: Boolean,
        val bufferSize: Int
    )

    enum class StreamingMode {
        STATUS_ONLY,
        FULL_REASONING
    }
}
