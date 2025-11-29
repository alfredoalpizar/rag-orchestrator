package com.alfredoalpizar.rag.filter

import mu.KotlinLogging
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.*
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlin.system.measureTimeMillis

/**
 * Request tracing filter that adds a unique request ID to each HTTP request
 * and logs the start/end of request processing with timing information.
 *
 * The request ID is added to the MDC (Mapped Diagnostic Context) so it appears
 * in all logs during the request processing.
 */
@Component
@Order(1) // Execute early in the filter chain
class RequestTracingFilter : OncePerRequestFilter() {

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-ID"
        const val REQUEST_ID_MDC_KEY = "requestId"
        private val logger = KotlinLogging.logger {}
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // Generate or extract request ID
        val requestId = request.getHeader(REQUEST_ID_HEADER) ?: UUID.randomUUID().toString().substring(0, 8)

        // Add request ID to MDC for logging
        MDC.put(REQUEST_ID_MDC_KEY, requestId)

        // Add request ID to response headers
        response.setHeader(REQUEST_ID_HEADER, requestId)

        try {
            val method = request.method
            val uri = request.requestURI
            val queryString = request.queryString?.let { "?$it" } ?: ""
            val fullUrl = "$method $uri$queryString"

            logger.info("Starting request: $fullUrl")

            val processingTime = measureTimeMillis {
                filterChain.doFilter(request, response)
            }

            val status = response.status
            logger.info("Finished request: $fullUrl (${processingTime}ms) -> $status")

        } finally {
            // Always clear MDC to prevent memory leaks
            MDC.remove(REQUEST_ID_MDC_KEY)
        }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        // Skip filtering for health checks and static resources
        return path.startsWith("/actuator/health") ||
               path.startsWith("/static/") ||
               path.startsWith("/favicon.ico")
    }
}