package com.alfredoalpizar.rag.service.storage

import com.alfredoalpizar.rag.model.document.Document
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component

/**
 * Serializes and deserializes documents to/from YAML format.
 *
 * Handles bidirectional conversion:
 * - Deserialize: YAML bytes → Document object (for reading)
 * - Serialize: Document object → YAML bytes (for writing)
 *
 * Note: Jackson YAML round-trips will not preserve:
 * - Comments in original YAML
 * - Field ordering (uses alphabetical)
 * - Custom formatting/indentation
 *
 * This is acceptable for programmatic document management,
 * but users should be aware edits may reformat the file.
 */
@Component
class DocumentSerializer {

    private val yamlMapper = ObjectMapper(YAMLFactory()).apply {
        // Register Kotlin module for proper data class support
        registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())

        // Don't fail on unknown properties (forward compatibility)
        configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        // Pretty print YAML output
        configure(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT, true)
    }

    /**
     * Deserialize YAML bytes into a Document object.
     *
     * @param bytes Raw YAML content
     * @return Parsed Document
     * @throws com.fasterxml.jackson.core.JsonProcessingException if YAML is invalid
     */
    fun deserialize(bytes: ByteArray): Document {
        return yamlMapper.readValue(bytes)
    }

    /**
     * Serialize a Document object into YAML bytes.
     *
     * @param document Document to serialize
     * @return YAML-formatted bytes
     */
    fun serialize(document: Document): ByteArray {
        return yamlMapper.writeValueAsBytes(document)
    }
}
