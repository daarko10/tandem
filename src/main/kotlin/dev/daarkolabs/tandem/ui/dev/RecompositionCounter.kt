package dev.daarkolabs.tandem.ui.dev

import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val LOG = logger<RecompositionCounter>()

/**
 * Development-only (spec 9.4): counts how often each chat row recomposes while the rendering spike runs, and logs a
 * `TANDEM-SPIKE recompose` line with a row key and its count at most once a second. Logs row keys only, never row text.
 */
internal object RecompositionCounter {
    private val counts = ConcurrentHashMap<String, Int>()
    private val lastLog = AtomicLong(0L)

    fun hit(key: String) {
        val n = counts.merge(key, 1, Int::plus) ?: 1
        val now = System.currentTimeMillis()
        val last = lastLog.get()
        if (now - last >= 1_000 && lastLog.compareAndSet(last, now)) LOG.warn("TANDEM-SPIKE recompose key=$key n=$n")
    }
}
