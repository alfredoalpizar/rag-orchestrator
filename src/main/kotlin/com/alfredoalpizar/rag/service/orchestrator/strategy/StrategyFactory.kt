package com.alfredoalpizar.rag.service.orchestrator.strategy

import com.alfredoalpizar.rag.config.Environment
import com.alfredoalpizar.rag.service.orchestrator.strategy.impl.DeepSeekSingleStrategy
import com.alfredoalpizar.rag.service.orchestrator.strategy.impl.QwenSingleInstructStrategy
import com.alfredoalpizar.rag.service.orchestrator.strategy.impl.QwenSingleThinkingStrategy
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Factory for selecting the appropriate ModelStrategyExecutor based on Environment configuration.
 *
 * Reads from Environment.LOOP_MODEL_STRATEGY to determine which strategy to use.
 * This replaces Spring's @ConditionalOnProperty mechanism with a simpler, more explicit approach.
 *
 * Default: qwen_single_thinking
 */
@Component
class StrategyFactory(
    private val deepSeekStrategy: DeepSeekSingleStrategy,
    private val qwenThinkingStrategy: QwenSingleThinkingStrategy,
    private val qwenInstructStrategy: QwenSingleInstructStrategy
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Get the configured strategy from Environment.kt
     */
    fun getStrategy(): ModelStrategyExecutor {
        val strategyName = Environment.LOOP_MODEL_STRATEGY

        logger.info { "Selecting strategy from Environment.LOOP_MODEL_STRATEGY: $strategyName" }

        return when (strategyName) {
            "qwen_single_thinking" -> qwenThinkingStrategy
            "qwen_single_instruct" -> qwenInstructStrategy
            "deepseek_single" -> deepSeekStrategy
            else -> {
                logger.warn {
                    "Unknown strategy '$strategyName', defaulting to qwen_single_thinking. " +
                            "Valid options: qwen_single_thinking, qwen_single_instruct, deepseek_single"
                }
                qwenThinkingStrategy
            }
        }
    }
}
