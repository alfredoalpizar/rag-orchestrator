package com.alfredoalpizar.rag.model.document

/**
 * Document content structure.
 *
 * Required:
 * - body: Main content in Markdown format
 *
 * Optional:
 * - summary: Brief 1-2 sentence description
 */
data class DocumentContent(
    val body: String,
    val summary: String? = null
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (body.isBlank()) {
            errors.add("content.body is required and cannot be blank")
        }
        return errors
    }
}
