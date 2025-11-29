package com.alfredoalpizar.rag.model.document

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDate

/**
 * Simplified document metadata.
 *
 * Required fields:
 * - id: Unique identifier
 * - title: Human-readable title
 * - category: Document type (workflow, faq, reference, troubleshooting)
 * - status: Lifecycle state (published, draft, archived)
 *
 * Optional fields:
 * - lastUpdated: ISO date of last update
 * - tags: List of search tags
 */
data class DocumentMetadata(
    // Required fields
    val id: String,
    val title: String,
    val category: String,
    val status: String,

    // Optional fields
    @JsonProperty("lastUpdated")
    val lastUpdated: LocalDate? = null,
    val tags: List<String>? = null
) {
    companion object {
        val VALID_CATEGORIES = listOf("workflow", "faq", "reference", "troubleshooting")
        val VALID_STATUSES = listOf("published", "draft", "archived")
    }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (id.isBlank()) errors.add("id is required and cannot be blank")
        if (title.isBlank()) errors.add("title is required and cannot be blank")
        if (category.isBlank()) {
            errors.add("category is required and cannot be blank")
        } else if (category !in VALID_CATEGORIES) {
            errors.add("category must be one of: ${VALID_CATEGORIES.joinToString()}")
        }
        if (status.isBlank()) {
            errors.add("status is required and cannot be blank")
        } else if (status !in VALID_STATUSES) {
            errors.add("status must be one of: ${VALID_STATUSES.joinToString()}")
        }

        return errors
    }

    fun isPublished(): Boolean = status == "published"
}
