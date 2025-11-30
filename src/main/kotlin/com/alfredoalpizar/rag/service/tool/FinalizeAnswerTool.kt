package com.alfredoalpizar.rag.service.tool

import com.alfredoalpizar.rag.model.domain.ToolResult
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Special tool that signals the model is ready to provide a final answer.
 *
 * When the model calls this tool, the orchestrator:
 * 1. Does NOT execute it like a normal tool
 * 2. Makes a NEW streaming call to Fireworks AI with reasoning_effort: "none"
 * 3. Streams the clean response as the final answer to the user
 *
 * This ensures we know exactly when to stream the final answer vs intermediate content.
 */
@Component
class FinalizeAnswerTool : Tool {

    private val logger = KotlinLogging.logger {}

    override val name = "finalize_answer"

    override val description = """
        Call this tool when you have gathered enough information to provide a final answer to the user.
        Pass all relevant context and the original user question.
        This will generate a well-formatted final response.

        IMPORTANT: Only call this when you are ready to give your final answer.
        Do not call this if you still need to search for more information or use other tools.
    """.trimIndent()

    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "context" to mapOf(
                "type" to "string",
                "description" to "All relevant information gathered from previous tool calls, search results, and your reasoning. Include key facts, document excerpts, and any important details."
            ),
            "user_question" to mapOf(
                "type" to "string",
                "description" to "The original user question that needs to be answered"
            ),
            "answer_style" to mapOf(
                "type" to "string",
                "description" to "How to format the answer: 'concise' for brief answers, 'detailed' for comprehensive explanations, 'step_by_step' for instructions",
                "enum" to listOf("concise", "detailed", "step_by_step")
            )
        ),
        "required" to listOf("context", "user_question")
    )

    /**
     * This execute method returns a marker result.
     * The actual finalization is handled specially by the OrchestratorService,
     * which intercepts this tool call and makes a streaming API call instead.
     */
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val context = arguments["context"] as? String ?: ""
        val userQuestion = arguments["user_question"] as? String ?: ""
        val answerStyle = arguments["answer_style"] as? String ?: "detailed"

        logger.info {
            "FinalizeAnswerTool called: " +
                    "userQuestion='${userQuestion.take(100)}...', " +
                    "contextLength=${context.length}, " +
                    "answerStyle=$answerStyle"
        }

        // Return a marker that tells the orchestrator this is the finalize_answer call
        // The orchestrator will handle this specially by making a streaming call
        return ToolResult(
            toolCallId = "",
            toolName = name,
            result = "__FINALIZE_ANSWER__",  // Marker for orchestrator
            success = true,
            metadata = mapOf(
                "context" to context,
                "user_question" to userQuestion,
                "answer_style" to answerStyle,
                "is_finalize_answer" to true
            )
        )
    }

    companion object {
        const val FINALIZE_MARKER = "__FINALIZE_ANSWER__"
    }
}
