package com.alfredoalpizar.rag.client.chromadb

import com.fasterxml.jackson.annotation.JsonProperty

// Request Models
data class ChromaDBQueryRequest(
    @JsonProperty("query_texts")
    val queryTexts: List<String>,
    @JsonProperty("n_results")
    val nResults: Int = 5
)

data class ChromaDBAddRequest(
    val documents: List<String>,
    val ids: List<String>,
    val metadatas: List<Map<String, Any>>? = null
)

// Response Models
data class ChromaDBQueryResponse(
    val ids: List<List<String>>,
    val documents: List<List<String>>,
    val distances: List<List<Float>>,
    val metadatas: List<List<Map<String, Any>>>?
) {
    fun toResults(): List<ChromaDBResult> {
        if (ids.isEmpty() || ids[0].isEmpty()) {
            return emptyList()
        }

        return ids[0].mapIndexed { index, id ->
            ChromaDBResult(
                id = id,
                document = documents[0][index],
                distance = distances[0][index],
                metadata = metadatas?.get(0)?.get(index) ?: emptyMap()
            )
        }
    }
}

data class ChromaDBResult(
    val id: String,
    val document: String,
    val distance: Float,
    val metadata: Map<String, Any>
)

data class ChromaDBDocument(
    val id: String,
    val document: String,
    val metadata: Map<String, Any> = emptyMap()
)

data class ChromaDBCollection(
    val name: String,
    val count: Int
)

data class ChromaDBCollectionResponse(
    val name: String,
    val metadata: Map<String, Any>?
)
