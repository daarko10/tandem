package dev.daarkolabs.tandem.ui.rows

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import dev.daarkolabs.tandem.chat.RowGap
import dev.daarkolabs.tandem.chat.ThinkingRow
import dev.daarkolabs.tandem.chat.WorkingRow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The working row's motion as composed: [LocalReducedMotion], which the tool window provides from `TandemSessions.reduceMotion`, then
 * [reducedMotion] and [workingView], whose `animated` moves the orbs, and the shimmer ([rememberShimmer], [thinkingShimmer]) on a
 * running recomposer with [FakeClock].
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

    @Test
    fun `the composed shimmer sweeps`() {
        val values = running(3_000) { pause -> rememberShimmer(workingView(row, now, now, agentExpanded = false).shimmerEnd > 0, pause) }
        assertTrue(values.any { it != null }, "the band crosses the step within 3 s: $values")
    }

    @Test
    fun `under Reduce motion the composed shimmer stays at rest`() {
        val values = running(3_000) { pause ->
            providing(true) { rememberShimmer(workingView(row, now, now, agentExpanded = false).shimmerEnd > 0, pause) }
        }
        assertTrue(values.isNotEmpty())
        assertTrue(values.all { it == null }, "$values")
    }

    @Test
    fun `the thinking row shimmers only while it streams`() {
        val streaming = ThinkingRow("think:1", "Thinking… · 50 tokens", summary = "", streaming = true, gap = RowGap.ROW)
        val done = streaming.copy(label = "Thought for 2s", streaming = false)
        assertTrue(composed { thinkingShimmer(streaming) })
        assertFalse(composed { thinkingShimmer(done) })
        assertFalse(composed { providing(true) { thinkingShimmer(streaming) } }, "Reduce motion on")
    }

    @Composable
    private fun <T> providing(reduce: Boolean, read: @Composable () -> T): T {
        var value: T? = null
        CompositionLocalProvider(LocalReducedMotion provides reduce) { value = read() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /**
     * Composes [content] with no UI under a running [Recomposer] on [FakeClock], whose time moves on the effects' frames and on the
     * `pause` given to [content], until it passes [limitMs]; returns the values the shimmer's state held, read after every step.
     */
    private fun running(limitMs: Long, content: @Composable (pause: suspend (Long) -> Unit) -> State<Float?>): List<Float?> {
        val clock = FakeClock(limitMs)
        val values = ArrayList<Float?>()
        runBlocking(clock) {
            withTimeout(10_000) {
                val recomposer = Recomposer(coroutineContext)
                val recomposing = launch { recomposer.runRecomposeAndApplyChanges() }
                val composition = Composition(NoApplier, recomposer)
                var state: State<Float?>? = null
                try {
                    composition.setContent { state = content(clock::pause) }
                    val shimmer = checkNotNull(state) { "the content was not composed" }
                    // The effects move the time; the test moves it too, 1 ms a step, so that an effect that ended cannot stall it.
                    while (clock.nowMs <= limitMs) {
                        values += shimmer.value
                        clock.step()
                    }
                } finally {
                    composition.dispose()
                    recomposer.cancel()
                    recomposing.cancel()
                }
            }
        }
        return values
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
