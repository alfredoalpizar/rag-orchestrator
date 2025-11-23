package com.alfredoalpizar.rag.service.finalizer

import org.springframework.stereotype.Component

@Component
class DirectFinalizerStrategy : FinalizerStrategy {

    override fun format(response: String, metadata: Map<String, Any>): String {
        // Return response as-is, no modification
        return response
    }
}
