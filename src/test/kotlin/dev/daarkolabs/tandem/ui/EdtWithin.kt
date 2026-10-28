package dev.daarkolabs.tandem.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

/**
 * Runs [work] on the EDT and returns what it returned, or throws what it threw. The test thread waits on a future, not on the EDT, so
 * if the EDT is still busy after [timeout] the test fails at once with every thread's stack instead of hanging, which a
 * `withContext(Dispatchers.EDT)` inside `timeoutRunBlocking` cannot do: its timeout waits for the EDT's blocking work to end.
 */
internal fun <T> onEdtWithin(timeout: Duration, work: () -> T): T {
    val result = CompletableFuture<T>()
    ApplicationManager.getApplication().invokeLater({
        try {
            result.complete(work())
        } catch (t: Throwable) {
            result.completeExceptionally(t)
        }
    }, ModalityState.any())
    try {
        return result.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: TimeoutException) {
        throw AssertionError("the EDT did not finish the test's work within $timeout\n${threadDump()}", e)
    }
}

private fun threadDump(): String = Thread.getAllStackTraces().entries.joinToString("\n") { (thread, stack) ->
    "\"${thread.name}\" ${thread.state}\n" + stack.joinToString("") { "\tat $it\n" }
}
