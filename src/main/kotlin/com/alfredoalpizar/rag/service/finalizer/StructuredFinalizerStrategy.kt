package com.alfredoalpizar.rag.service.finalizer

import org.springframework.stereotype.Component

@Component
class StructuredFinalizerStrategy : FinalizerStrategy {

    override fun format(response: String, metadata: Map<String, Any>): String {
        val builder = StringBuilder()

        // Add header
        builder.append("## Response\n\n")

        // Add main content
        builder.append(response)

        // Add metadata if present
        if (metadata.isNotEmpty()) {
            builder.append("\n\n---\n\n")
            builder.append("### Metadata\n\n")

            metadata.forEach { (key, value) ->
                builder.append("- **$key**: $value\n")
            }
        }

        return builder.toString()
    }
}
