package com.alfredoalpizar.rag.controller.agent

import com.alfredoalpizar.rag.service.tool.ToolRegistry
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/agent")
class AgentController(
    private val toolRegistry: ToolRegistry
) {
    private val logger = KotlinLogging.logger {}

    @GetMapping("/tools")
    fun listTools(): ResponseEntity<List<ToolInfo>> {
        logger.debug { "Listing available tools" }

        val tools = toolRegistry.getAllTools().map { tool ->
            ToolInfo(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters
            )
        }

        return ResponseEntity.ok(tools)
    }

    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, Any>> {
        logger.debug { "Health check" }

        return ResponseEntity.ok(mapOf(
            "status" to "healthy",
            "service" to "agent-orchestrator",
            "timestamp" to Instant.now().toString(),
            "tools" to toolRegistry.getAllTools().size
        ))
    }
}

data class ToolInfo(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)
