package dev.daarkolabs.tandem.ui.dev

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.delay
import javax.swing.SwingUtilities

/**
 * Development-only: measures how long a runnable waits before the EDT runs it. A wait over
 * [stallThresholdMs] means the EDT was busy (or blocked) that long. Logs each stall and a summary.
 */
internal class EdtStallMonitor(private val probeIntervalMs: Long = 20, private val stallThresholdMs: Long = 100) {
    private val latenciesMs = ArrayList<Long>()
    private val stalls = ArrayList<Long>()

    /** Runs until cancelled. Call from a background coroutine. */
    suspend fun run() {
        while (true) {
            val posted = System.nanoTime()
            SwingUtilities.invokeLater {
                val waitedMs = (System.nanoTime() - posted) / 1_000_000
                synchronized(this) {
                    latenciesMs += waitedMs
                    if (waitedMs > stallThresholdMs) {
                        stalls += waitedMs
                        thisLogger().warn("TANDEM-SPIKE EDT stall ${waitedMs} ms")
                    }
                }
            }
            delay(probeIntervalMs)
        }
    }

    fun summary(): String = synchronized(this) {
        val sorted = latenciesMs.sorted()
        fun pct(p: Double): Long = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p).toInt()]
        "probes=${sorted.size} stalls>${stallThresholdMs}ms=${stalls.size} max=${sorted.lastOrNull() ?: 0}ms " +
            "p50=${pct(0.5)}ms p99=${pct(0.99)}ms stallValues=$stalls"
    }
}
