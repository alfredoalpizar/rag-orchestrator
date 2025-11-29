package com.alfredoalpizar.rag.service.finalizer

import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

@Component
@Primary
class DirectFinalizerStrategy : FinalizerStrategy {

    override fun format(response: String, metadata: Map<String, Any>): String {
        // Return response as-is, no modification
        return response
    }
}
