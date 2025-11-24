# Tools Guide

How to create custom tools for the RAG Orchestrator.

---

## Tool Interface

All tools implement the `Tool` interface:

```kotlin
interface Tool {
    val name: String
    val description: String
    val parameters: Map<String, Any>  // JSON Schema

    suspend fun execute(arguments: Map<String, Any>): ToolResult
}
```

### ToolResult

```kotlin
data class ToolResult(
    val toolCallId: String,
    val toolName: String,
    val result: String,
    val success: Boolean = true,
    val error: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)
```

---

## Creating a Tool

### Step 1: Implement Tool Interface

```kotlin
package com.alfredoalpizar.rag.service.tool

import org.springframework.stereotype.Component

@Component
class WeatherTool : Tool {

    override val name = "get_weather"

    override val description = "Get current weather for a city"

    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "city" to mapOf(
                "type" to "string",
                "description" to "City name (e.g., 'New York')"
            ),
            "units" to mapOf(
                "type" to "string",
                "enum" to listOf("celsius", "fahrenheit")
            )
        ),
        "required" to listOf("city")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val city = arguments["city"] as? String
            ?: return ToolResult(
                toolCallId = "",
                toolName = name,
                result = "",
                success = false,
                error = "Missing required parameter: city"
            )

        val units = (arguments["units"] as? String) ?: "fahrenheit"

        return try {
            val weather = fetchWeather(city, units)
            ToolResult(
                toolCallId = "",
                toolName = name,
                result = "Weather in $city: ${weather.condition}, ${weather.temp}°",
                success = true
            )
        } catch (e: Exception) {
            ToolResult(
                toolCallId = "",
                toolName = name,
                result = "",
                success = false,
                error = "Weather API error: ${e.message}"
            )
        }
    }

    private suspend fun fetchWeather(city: String, units: String): WeatherData {
        // Call external API here
        return WeatherData("Sunny", 72)
    }

    private data class WeatherData(val condition: String, val temp: Int)
}
```

### Step 2: Done

Spring Boot automatically discovers `@Component` classes implementing `Tool` and registers them.

---

## Built-in: RAG Tool

The built-in RAG tool queries ChromaDB:

```kotlin
@Component
class RAGTool(private val chromaDBClient: ChromaDBClient) : Tool {

    override val name = "rag_search"
    override val description = "Search the knowledge base for relevant information"

    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf(
                "type" to "string",
                "description" to "The search query"
            ),
            "max_results" to mapOf(
                "type" to "integer",
                "description" to "Maximum results (default: 5)"
            )
        ),
        "required" to listOf("query")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val query = arguments["query"] as String
        val maxResults = (arguments["max_results"] as? Int) ?: 5

        val results = chromaDBClient.query(query, maxResults).awaitSingle()
        val formatted = results.joinToString("\n\n") {
            "Document: ${it.document}\n(Relevance: ${1 - it.distance})"
        }

        return ToolResult(
            toolCallId = "",
            toolName = name,
            result = formatted,
            success = true
        )
    }
}
```

---

## Tool Registry

Tools are auto-discovered and managed by `ToolRegistry`:

```kotlin
@Service
class ToolRegistry(tools: List<Tool>) {
    private val toolMap = tools.associateBy { it.name }

    fun getTool(name: String): Tool?
    fun getAllTools(): List<Tool>
    suspend fun executeTool(toolCall: ToolCall): ToolResult
}
```

### List Tools via API

```bash
curl http://localhost:8080/api/v1/agent/tools
```

---

## Guidelines

1. **Clear names**: `get_weather`, `search_documents` not `tool1`, `helper`
2. **Detailed descriptions**: LLM uses these to decide when to call tools
3. **Validate parameters**: Always check required params before execution
4. **Return descriptive errors**: Help the LLM recover from failures
5. **Use suspend**: For I/O operations (HTTP, database)

```kotlin
override suspend fun execute(arguments: Map<String, Any>): ToolResult {
    val param = arguments["param"] as? String
        ?: return ToolResult(..., success = false, error = "Missing: param")

    return withContext(Dispatchers.IO) {
        // I/O operations here
    }
}
```
