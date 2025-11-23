package com.alfredoalpizar.rag.service.finalizer

interface FinalizerStrategy {
    fun format(response: String, metadata: Map<String, Any> = emptyMap()): String
}
