package com.alfredoalpizar.rag.client.chromadb

import reactor.core.publisher.Mono

interface ChromaDBClient {
    fun query(text: String, nResults: Int = 5): Mono<List<ChromaDBResult>>
    fun add(documents: List<ChromaDBDocument>): Mono<Unit>
    fun getCollectionInfo(): Mono<ChromaDBCollection>
}
