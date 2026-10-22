package dev.daarkolabs.tandem.ui.rows

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import dev.daarkolabs.tandem.chat.WorkingRow
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The working row's motion as composed: [LocalReducedMotion], which the tool window provides from `TandemSessions.reduceMotion`, then
 * [reducedMotion] and [workingView], whose `animated` moves the spark.
 */
class WorkingRowItemTest {
    private val now = 1_000_000L
    private val row = WorkingRow(turnUuid = "u1", startedAtMillis = now - 3_000, step = "Pondering", outputTokens = 0, waiting = false)

    @Test
    fun `the spark moves when nothing provides the setting`() {
        assertFalse(composed { reducedMotion() }, "not the IDE's animateWindows, which is off by default")
        assertTrue(composed { workingView(row, now, now, agentExpanded = false).animated })
    }

    @Test
    fun `the spark follows the Reduce motion the tool window provides`() {
        assertTrue(composed { providing(true) { reducedMotion() } })
        assertFalse(composed { providing(true) { workingView(row, now, now, agentExpanded = false).animated } }, "Reduce motion on")
        assertFalse(composed { providing(false) { reducedMotion() } })
        assertTrue(composed { providing(false) { workingView(row, now, now, agentExpanded = false).animated } }, "Reduce motion off")
    }

    @Composable
    private fun providing(reduce: Boolean, read: @Composable () -> Boolean): Boolean {
        var value = false
        CompositionLocalProvider(LocalReducedMotion provides reduce) { value = read() }
        return value
    }

    /** Composes [content] once, with no UI, and returns what it read. */
    private fun composed(content: @Composable () -> Boolean): Boolean {
        var value: Boolean? = null
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(NoApplier, recomposer)
        try {
            composition.setContent { value = content() }
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
        return checkNotNull(value) { "the content was not composed" }
    }

    private object NoApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit

        override fun insertBottomUp(index: Int, instance: Unit) = Unit

        override fun remove(index: Int, count: Int) = Unit

        override fun move(from: Int, to: Int, count: Int) = Unit

        override fun onClear() = Unit
    }
}
