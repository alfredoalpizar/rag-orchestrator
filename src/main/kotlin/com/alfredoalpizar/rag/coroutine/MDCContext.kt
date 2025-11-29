package com.alfredoalpizar.rag.coroutine

import kotlinx.coroutines.ThreadContextElement
import org.slf4j.MDC
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element that propagates SLF4J MDC (Mapped Diagnostic Context)
 * across coroutine suspensions and thread switches.
 *
 * This solves the problem where MDC is thread-local, and Kotlin coroutines can switch
 * threads (especially with Dispatchers.IO), causing MDC context to be lost.
 *
 * Usage examples:
 * ```kotlin
 * // In coroutine launch
 * launch(currentMDCContext()) {
 *     // MDC is available here even on different thread
 * }
 *
 * // With context switching
 * withContext(Dispatchers.IO + currentMDCContext()) {
 *     // MDC propagated to IO dispatcher thread
 * }
 * ```
 */
class MDCContext(
    private val contextMap: Map<String, String> = MDC.getCopyOfContextMap() ?: emptyMap()
) : ThreadContextElement<Map<String, String>?> {

    companion object Key : CoroutineContext.Key<MDCContext>

    override val key: CoroutineContext.Key<MDCContext> = Key

    /**
     * Called when coroutine resumes on a thread.
     * Restores MDC from captured context and returns old state for later restoration.
     */
    override fun updateThreadContext(context: CoroutineContext): Map<String, String>? {
        val oldState = MDC.getCopyOfContextMap()
        setCopyOfContextMap(contextMap)
        return oldState
    }

    /**
     * Called when coroutine suspends or completes.
     * Restores MDC to the state before this coroutine resumed.
     */
    override fun restoreThreadContext(context: CoroutineContext, oldState: Map<String, String>?) {
        setCopyOfContextMap(oldState)
    }

    private fun setCopyOfContextMap(contextMap: Map<String, String>?) {
        if (contextMap == null) {
            MDC.clear()
        } else {
            MDC.setContextMap(contextMap)
        }
    }
}

/**
 * Convenience function to capture current MDC context.
 *
 * Example:
 * ```kotlin
 * suspend fun myFunction() = withContext(Dispatchers.IO + currentMDCContext()) {
 *     // Your code with MDC preserved
 * }
 * ```
 */
fun currentMDCContext(): MDCContext = MDCContext(
    MDC.getCopyOfContextMap() ?: emptyMap()
)
