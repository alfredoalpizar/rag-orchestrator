# Tools Development Guide

**Project**: RAG Orchestrator Service
**Version**: 0.0.1-SNAPSHOT
**Last Updated**: 2025-11-23

---

## Table of Contents

1. [Overview](#overview)
2. [Tool Architecture](#tool-architecture)
3. [Creating Custom Tools](#creating-custom-tools)
4. [Built-in Tools](#built-in-tools)
5. [Tool Registry](#tool-registry)
6. [Best Practices](#best-practices)
7. [Testing Tools](#testing-tools)
8. [Examples](#examples)

---

## Overview

The RAG Orchestrator uses a **tool-calling architecture** where the LLM (DeepSeek) can automatically invoke tools to retrieve information, perform calculations, or execute actions during conversations.

### How Tools Work

```
User: "What's the weather in NYC?"
  ↓
Orchestrator → DeepSeek API
  ↓
DeepSeek decides: "I need to call weather_api tool"
  ↓
Tool Registry executes: weather_api(city="NYC")
  ↓
Result: "Sunny, 72°F"
  ↓
Orchestrator → DeepSeek API (with result)
  ↓
DeepSeek: "The weather in New York City is sunny and 72°F"
  ↓
User receives final response
```

### Key Concepts

- **Tool**: A function that can be called by the LLM
- **Tool Definition**: JSON schema describing the tool's name, description, and parameters
- **Tool Call**: Request from LLM to execute a tool
- **Tool Result**: Return value sent back to the LLM
- **Tool Registry**: Central catalog of available tools

---

## Tool Architecture

### Tool Interface

All tools implement the `Tool` interface:

```kotlin
package com.alfredoalpizar.rag.service.tool

interface Tool {
    /**
     * Unique tool identifier (used by LLM to invoke)
     */
    val name: String

    /**
     * Human-readable description of what the tool does
     * (LLM uses this to decide when to call the tool)
     */
    val description: String

    /**
     * JSON Schema describing the tool's parameters
     */
    val parameters: Map<String, Any>

    /**
     * Execute the tool with given arguments
     * @param arguments Parsed JSON arguments from LLM
     * @return ToolResult with success/failure and output
     */
    suspend fun execute(arguments: Map<String, Any>): ToolResult
}
```

### Tool Result

Tool execution returns a `ToolResult`:

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

### Tool Registry

The `ToolRegistry` manages all available tools:

```kotlin
@Service
class ToolRegistry(
    tools: List<Tool>  // Spring auto-injects all @Component classes implementing Tool
) {
    fun getTool(name: String): Tool?
    fun getAllTools(): List<Tool>
    fun getToolDefinitions(): List<DeepSeekTool>
    suspend fun executeTool(toolCall: ToolCall): ToolResult
}
```

---

## Creating Custom Tools

### Step 1: Implement Tool Interface

Create a new Kotlin class that implements `Tool`:

```kotlin
package com.alfredoalpizar.rag.service.tool

import org.springframework.stereotype.Component
import com.alfredoalpizar.rag.model.domain.ToolResult

@Component
class WeatherTool : Tool {

    override val name = "get_weather"

    override val description = "Get current weather for a city"

    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "city" to mapOf(
                "type" to "string",
                "description" to "City name (e.g., 'New York' or 'London')"
            ),
            "units" to mapOf(
                "type" to "string",
                "description" to "Temperature units: 'celsius' or 'fahrenheit'",
                "enum" to listOf("celsius", "fahrenheit")
            )
        ),
        "required" to listOf("city")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        // Extract parameters
        val city = arguments["city"] as? String
            ?: return ToolResult(
                toolCallId = "",
                toolName = name,
                result = "",
                success = false,
                error = "Missing required parameter: city"
            )

        val units = (arguments["units"] as? String) ?: "fahrenheit"

        // Perform the actual operation
        return try {
            val weather = fetchWeather(city, units)

            ToolResult(
                toolCallId = "",
                toolName = name,
                result = "Weather in $city: ${weather.condition}, ${weather.temperature}°${units[0].uppercaseChar()}",
                success = true,
                metadata = mapOf(
                    "city" to city,
                    "temperature" to weather.temperature,
                    "condition" to weather.condition
                )
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
        // Implementation: Call external weather API
        // For demo purposes:
        return WeatherData(
            condition = "Sunny",
            temperature = if (units == "celsius") 22 else 72
        )
    }

    private data class WeatherData(
        val condition: String,
        val temperature: Int
    )
}
```

### Step 2: Register Tool

That's it! Spring Boot automatically discovers `@Component` classes implementing `Tool` and registers them in the `ToolRegistry`.

### Step 3: Test Tool

Create a unit test:

```kotlin
@ExtendWith(MockKExtension::class)
class WeatherToolTest {

    private lateinit var tool: WeatherTool

    @BeforeEach
    fun setup() {
        tool = WeatherTool()
    }

    @Test
    fun `should return weather for valid city`() = runBlocking {
        val result = tool.execute(mapOf(
            "city" to "New York",
            "units" to "fahrenheit"
        ))

        assertTrue(result.success)
        assertTrue(result.result.contains("New York"))
        assertTrue(result.result.contains("°F"))
    }

    @Test
    fun `should fail without city parameter`() = runBlocking {
        val result = tool.execute(mapOf("units" to "celsius"))

        assertFalse(result.success)
        assertEquals("Missing required parameter: city", result.error)
    }

    @Test
    fun `should default to fahrenheit`() = runBlocking {
        val result = tool.execute(mapOf("city" to "London"))

        assertTrue(result.success)
        assertTrue(result.result.contains("°F"))
    }
}
```

---

## Built-in Tools

### RAG Tool

The **RAG Search Tool** queries the vector database for relevant documents.

**File**: `src/main/kotlin/com/alfredoalpizar/rag/service/tool/RAGTool.kt`

```kotlin
@Component
class RAGTool(
    private val chromaDBClient: ChromaDBClient
) : Tool {

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
                "description" to "Maximum number of results to return (default: 5)"
            )
        ),
        "required" to listOf("query")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val query = arguments["query"] as? String
            ?: return ToolResult(
                toolCallId = "",
                toolName = name,
                result = "",
                success = false,
                error = "Missing required parameter: query"
            )

        val maxResults = (arguments["max_results"] as? Int) ?: 5

        return try {
            val results = chromaDBClient.query(query, maxResults).awaitSingle()

            val formattedResults = results.joinToString("\n\n") { result ->
                "Document: ${result.document}\n(Relevance: ${1 - result.distance})"
            }

            ToolResult(
                toolCallId = "",
                toolName = name,
                result = formattedResults,
                success = true,
                metadata = mapOf("resultsCount" to results.size)
            )
        } catch (e: Exception) {
            ToolResult(
                toolCallId = "",
                toolName = name,
                result = "",
                success = false,
                error = "RAG search failed: ${e.message}"
            )
        }
    }
}
```

**When it's called**:
- User asks questions about knowledge base content
- LLM needs factual information not in its training data
- Follow-up questions requiring document retrieval

**Example invocation**:
```
User: "What are the company policies on remote work?"
  ↓
LLM calls: rag_search(query="remote work policies", max_results=3)
  ↓
Returns: 3 most relevant policy documents
```

---

## Tool Registry

### Auto-Discovery

The `ToolRegistry` automatically discovers all tools:

```kotlin
@Service
class ToolRegistry(
    tools: List<Tool>  // Spring injects all Tool implementations
) {
    private val toolMap: Map<String, Tool> = tools.associateBy { it.name }

    fun getTool(name: String): Tool? = toolMap[name]

    fun getAllTools(): List<Tool> = toolMap.values.toList()

    fun getToolDefinitions(): List<DeepSeekTool> {
        return toolMap.values.map { tool ->
            DeepSeekTool(
                type = "function",
                function = DeepSeekFunction(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            )
        }
    }
}
```

### Listing Tools

**Endpoint**: `GET /api/v1/agent/tools`

Returns all available tools with their schemas.

---

## Best Practices

### 1. Clear Naming

Use descriptive names that indicate the tool's purpose:

✅ **Good**:
- `get_weather`
- `search_documents`
- `calculate_mortgage`
- `send_email`

❌ **Bad**:
- `tool1`
- `do_stuff`
- `helper`
- `func`

### 2. Detailed Descriptions

The LLM uses descriptions to decide when to call tools:

✅ **Good**:
```kotlin
override val description = "Searches the company knowledge base for documents related to the query. Returns up to 5 most relevant documents with their content and relevance scores."
```

❌ **Bad**:
```kotlin
override val description = "Searches stuff"
```

### 3. Parameter Validation

Always validate parameters before execution:

```kotlin
override suspend fun execute(arguments: Map<String, Any>): ToolResult {
    // Validate required parameters
    val requiredParam = arguments["param"] as? String
        ?: return ToolResult(
            toolCallId = "",
            toolName = name,
            result = "",
            success = false,
            error = "Missing required parameter: param"
        )

    // Validate parameter types and ranges
    val count = (arguments["count"] as? Int)?.takeIf { it in 1..100 }
        ?: return ToolResult(
            toolCallId = "",
            toolName = name,
            result = "",
            success = false,
            error = "Parameter 'count' must be between 1 and 100"
        )

    // Proceed with execution
    // ...
}
```

### 4. Error Handling

Return descriptive errors that help the LLM recover:

```kotlin
return try {
    val result = performOperation()
    ToolResult(
        toolCallId = "",
        toolName = name,
        result = result,
        success = true
    )
} catch (e: TimeoutException) {
    ToolResult(
        toolCallId = "",
        toolName = name,
        result = "",
        success = false,
        error = "Operation timed out after 30 seconds. Please try with a smaller query."
    )
} catch (e: NetworkException) {
    ToolResult(
        toolCallId = "",
        toolName = name,
        result = "",
        success = false,
        error = "Network error: Could not reach external service. Please retry later."
    )
} catch (e: Exception) {
    ToolResult(
        toolCallId = "",
        toolName = name,
        result = "",
        success = false,
        error = "Unexpected error: ${e.message}"
    )
}
```

### 5. Structured Results

Return results in a format the LLM can easily understand:

✅ **Good** (structured):
```kotlin
val result = """
Search Results:
1. Document: "Remote Work Policy 2025"
   Summary: Employees may work remotely up to 3 days per week.
   Relevance: 0.95

2. Document: "Office Guidelines"
   Summary: Office space available Monday-Friday 8am-6pm.
   Relevance: 0.87
""".trimIndent()
```

❌ **Bad** (unstructured):
```kotlin
val result = "doc1:remote policy 2025,0.95;doc2:office guide,0.87"
```

### 6. Metadata for Debugging

Include metadata for observability:

```kotlin
ToolResult(
    toolCallId = "",
    toolName = name,
    result = "Found 5 documents",
    success = true,
    metadata = mapOf(
        "resultsCount" to 5,
        "queryTime" to "123ms",
        "source" to "chromadb",
        "collection" to "knowledge_base"
    )
)
```

### 7. Async/Suspend

Use `suspend` for I/O operations:

```kotlin
override suspend fun execute(arguments: Map<String, Any>): ToolResult {
    return withContext(Dispatchers.IO) {
        // Database calls
        // HTTP requests
        // File operations
    }
}
```

### 8. Idempotency

Tools should be idempotent when possible:
- Read operations: Always idempotent ✅
- Write operations: Use unique IDs to prevent duplicates

### 9. Timeouts

Set reasonable timeouts:

```kotlin
override suspend fun execute(arguments: Map<String, Any>): ToolResult {
    return withTimeout(30_000) {  // 30 seconds
        // Perform operation
    }
}
```

### 10. Testing

Write comprehensive unit tests:

```kotlin
@Test
fun `should handle all parameter combinations`() = runBlocking {
    // Test with all parameters
    // Test with optional parameters omitted
    // Test with invalid parameters
    // Test with edge cases (empty strings, max values, etc.)
}

@Test
fun `should handle errors gracefully`() = runBlocking {
    // Test network failures
    // Test timeouts
    // Test invalid responses
}
```

---

## Testing Tools

### Unit Tests

Test tools in isolation:

```kotlin
@ExtendWith(MockKExtension::class)
class RAGToolTest {

    @MockK
    private lateinit var chromaDBClient: ChromaDBClient

    private lateinit var tool: RAGTool

    @BeforeEach
    fun setup() {
        tool = RAGTool(chromaDBClient)
    }

    @Test
    fun `should return search results`() = runBlocking {
        // Given
        val mockResults = listOf(
            ChromaDBResult("id1", "Document content 1", 0.1f, emptyMap()),
            ChromaDBResult("id2", "Document content 2", 0.2f, emptyMap())
        )
        coEvery { chromaDBClient.query(any(), any()) } returns Mono.just(mockResults)

        // When
        val result = tool.execute(mapOf(
            "query" to "test query",
            "max_results" to 2
        ))

        // Then
        assertTrue(result.success)
        assertTrue(result.result.contains("Document content 1"))
        assertEquals(2, result.metadata["resultsCount"])
    }
}
```

### Integration Tests

Test tools with real dependencies:

```kotlin
@SpringBootTest
@TestPropertySource(properties = [
    "chromadb.base-url=http://localhost:8000",
    "chromadb.collection-name=test_collection"
])
class RAGToolIntegrationTest {

    @Autowired
    private lateinit var tool: RAGTool

    @Test
    fun `should query real ChromaDB`() = runBlocking {
        val result = tool.execute(mapOf("query" to "test"))

        assertTrue(result.success)
        assertNotNull(result.result)
    }
}
```

---

## Examples

### Example 1: Calculator Tool

```kotlin
@Component
class CalculatorTool : Tool {

    override val name = "calculator"

    override val description = "Perform basic mathematical calculations"

    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "operation" to mapOf(
                "type" to "string",
                "description" to "Mathematical operation",
                "enum" to listOf("add", "subtract", "multiply", "divide")
            ),
            "a" to mapOf(
                "type" to "number",
                "description" to "First operand"
            ),
            "b" to mapOf(
                "type" to "number",
                "description" to "Second operand"
            )
        ),
        "required" to listOf("operation", "a", "b")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val operation = arguments["operation"] as? String ?: return error("Missing operation")
        val a = (arguments["a"] as? Number)?.toDouble() ?: return error("Missing parameter a")
        val b = (arguments["b"] as? Number)?.toDouble() ?: return error("Missing parameter b")

        val result = when (operation) {
            "add" -> a + b
            "subtract" -> a - b
            "multiply" -> a * b
            "divide" -> if (b != 0.0) a / b else return error("Division by zero")
            else -> return error("Unknown operation: $operation")
        }

        return ToolResult(
            toolCallId = "",
            toolName = name,
            result = "Result: $result",
            success = true,
            metadata = mapOf("operation" to operation, "result" to result)
        )
    }

    private fun error(message: String) = ToolResult(
        toolCallId = "",
        toolName = name,
        result = "",
        success = false,
        error = message
    )
}
```

### Example 2: Database Query Tool

```kotlin
@Component
class DatabaseQueryTool(
    private val jdbcTemplate: JdbcTemplate
) : Tool {

    override val name = "query_database"

    override val description = "Query the product database for information"

    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query_type" to mapOf(
                "type" to "string",
                "description" to "Type of query",
                "enum" to listOf("product_info", "inventory_check", "price_lookup")
            ),
            "product_id" to mapOf(
                "type" to "string",
                "description" to "Product identifier"
            )
        ),
        "required" to listOf("query_type", "product_id")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val queryType = arguments["query_type"] as? String ?: return@withContext error("Missing query_type")
        val productId = arguments["product_id"] as? String ?: return@withContext error("Missing product_id")

        try {
            val result = when (queryType) {
                "product_info" -> getProductInfo(productId)
                "inventory_check" -> checkInventory(productId)
                "price_lookup" -> lookupPrice(productId)
                else -> return@withContext error("Unknown query type: $queryType")
            }

            ToolResult(
                toolCallId = "",
                toolName = name,
                result = result,
                success = true
            )
        } catch (e: Exception) {
            error("Database error: ${e.message}")
        }
    }

    private fun getProductInfo(productId: String): String {
        val product = jdbcTemplate.queryForMap(
            "SELECT name, description, category FROM products WHERE product_id = ?",
            productId
        )
        return "Product: ${product["name"]}\nDescription: ${product["description"]}\nCategory: ${product["category"]}"
    }

    private fun checkInventory(productId: String): String {
        val count = jdbcTemplate.queryForObject(
            "SELECT quantity FROM inventory WHERE product_id = ?",
            Int::class.java,
            productId
        )
        return "Inventory: $count units available"
    }

    private fun lookupPrice(productId: String): String {
        val price = jdbcTemplate.queryForObject(
            "SELECT price FROM products WHERE product_id = ?",
            Double::class.java,
            productId
        )
        return "Price: $$price"
    }

    private fun error(message: String) = ToolResult(
        toolCallId = "",
        toolName = name,
        result = "",
        success = false,
        error = message
    )
}
```

### Example 3: HTTP API Tool

```kotlin
@Component
class GitHubTool(
    private val webClient: WebClient
) : Tool {

    override val name = "github_search"

    override val description = "Search GitHub repositories"

    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf(
                "type" to "string",
                "description" to "Search query"
            ),
            "language" to mapOf(
                "type" to "string",
                "description" to "Programming language filter (optional)"
            )
        ),
        "required" to listOf("query")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val query = arguments["query"] as? String ?: return error("Missing query")
        val language = arguments["language"] as? String

        val searchQuery = buildString {
            append(query)
            if (language != null) append(" language:$language")
        }

        return try {
            val response = webClient.get()
                .uri("https://api.github.com/search/repositories?q={query}", searchQuery)
                .retrieve()
                .bodyToMono<GitHubSearchResponse>()
                .awaitSingle()

            val results = response.items.take(5).joinToString("\n\n") { repo ->
                """
                Repository: ${repo.fullName}
                Description: ${repo.description ?: "No description"}
                Stars: ${repo.stargazersCount}
                URL: ${repo.htmlUrl}
                """.trimIndent()
            }

            ToolResult(
                toolCallId = "",
                toolName = name,
                result = results,
                success = true,
                metadata = mapOf("totalCount" to response.totalCount)
            )
        } catch (e: Exception) {
            error("GitHub API error: ${e.message}")
        }
    }

    private fun error(message: String) = ToolResult(
        toolCallId = "",
        toolName = name,
        result = "",
        success = false,
        error = message
    )

    data class GitHubSearchResponse(
        val totalCount: Int,
        val items: List<GitHubRepo>
    )

    data class GitHubRepo(
        val fullName: String,
        val description: String?,
        val stargazersCount: Int,
        val htmlUrl: String
    )
}
```

---

## Advanced Topics

### Conditional Tool Availability

Only expose tools based on context:

```kotlin
@Service
class ConditionalToolRegistry(
    private val allTools: List<Tool>
) {
    fun getToolsForUser(userId: String): List<Tool> {
        val user = getUserPermissions(userId)

        return allTools.filter { tool ->
            when (tool.name) {
                "admin_tool" -> user.isAdmin
                "premium_tool" -> user.isPremium
                else -> true  // Public tools
            }
        }
    }
}
```

### Tool Composition

Chain multiple tools:

```kotlin
@Component
class CompositeSearchTool(
    private val ragTool: RAGTool,
    private val webSearchTool: WebSearchTool
) : Tool {

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val query = arguments["query"] as String

        // Try RAG first
        val ragResult = ragTool.execute(arguments)
        if (ragResult.success && ragResult.result.isNotEmpty()) {
            return ragResult
        }

        // Fall back to web search
        return webSearchTool.execute(arguments)
    }
}
```

---

**Next**: See [CONTEXT_MANAGEMENT_GUIDE.md](CONTEXT_MANAGEMENT_GUIDE.md) for conversation context strategies.
