package com.alfredoalpizar.rag.model.document

/**
 * Main document class with simplified schema.
 *
 * A document contains metadata and content that will be chunked
 * and ingested into ChromaDB for RAG retrieval.
 */
data class Document(
    val metadata: DocumentMetadata,
    val content: DocumentContent
) {
    /**
     * Validate the document structure and return list of validation errors.
     * Empty list means the document is valid.
     */
    fun validate(): DocumentValidationResult {
        val errors = mutableListOf<String>()
        errors.addAll(metadata.validate())
        errors.addAll(content.validate())

        return if (errors.isEmpty()) {
            DocumentValidationResult.Valid
        } else {
            DocumentValidationResult.Invalid(errors)
        }
    }

    /**
     * Check if this document should be indexed.
     * Only documents with status "published" are indexed.
     */
    fun shouldIndex(): Boolean = metadata.isPublished()
}

sealed class DocumentValidationResult {
    object Valid : DocumentValidationResult()
    data class Invalid(val errors: List<String>) : DocumentValidationResult()

    fun isValid(): Boolean = this is Valid

    fun errorsOrEmpty(): List<String> = when (this) {
        is Valid -> emptyList()
        is Invalid -> errors
    }
}
