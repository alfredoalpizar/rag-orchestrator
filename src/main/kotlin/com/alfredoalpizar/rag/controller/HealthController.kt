package com.alfredoalpizar.rag.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
class HealthController {

    @GetMapping("/ping")
    fun ping(): Map<String, String> {
        return mapOf(
            "status" to "ok",
            "service" to "rag-orchestrator",
            "timestamp" to Instant.now().toString()
        )
    }
}
