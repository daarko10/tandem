package dev.daarkolabs.tandem.ui.rows

import androidx.compose.runtime.MonotonicFrameClock
import dev.daarkolabs.tandem.chat.WorkingIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shimmer's loop ([runShimmer]) on a fake frame clock: when it sweeps, that it rests between sweeps and that it draws at most about
 * 30 frames a second ([WorkingIndicator.SHIMMER_FRAME_MS]). [WorkingRowItemTest] checks it composed, `MotionPixelsTest` drawn.
 */
class MotionLoopsTest {
    /** The values the loop set, each with the fake's time when it was set. */
    private data class Set(val atMs: Long, val value: Float?)

    @Test
    fun `the shimmer loop sweeps once in 3 s`() {
        val values = run(active = true, limitMs = 3_000)
        assertTrue(values.filter { it.atMs < WorkingIndicator.SHIMMER_START_MS }.all { it.value == null }, "at rest before 600 ms: $values")
        assertTrue(values.any { it.value != null }, "a sweep: $values")
        assertNull(values.last().value, "at rest again after the sweep, until 3 600 ms: $values")
    }

    @Test
    fun `the shimmer loop stays at rest when inactive`() {
        val values = run(active = false, limitMs = 3_000)
        assertTrue(values.all { it.value == null }, "$values")
    }

    @Test
    fun `the shimmer loop draws at most about 30 frames a second`() {
        val sweep = run(active = true, limitMs = 3_000).filter { it.value != null }
        assertTrue(sweep.size > 1, "$sweep")
        sweep.zipWithNext().forEach { (a, b) ->
            assertTrue(b.atMs - a.atMs >= WorkingIndicator.SHIMMER_FRAME_MS, "frames $a and $b closer than ${WorkingIndicator.SHIMMER_FRAME_MS} ms")
        }
    }

    @Test
    fun `the fake clock moves on its frames and its pauses`() {
        val clock = FakeClock(limitMs = 100)
        val times = ArrayList<Long>()
        runBlocking(clock) {
            withTimeout(10_000) {
                times += clock.withFrameNanos { it / 1_000_000 }
                clock.pause(50)
                times += clock.withFrameNanos { it / 1_000_000 }
            }
        }
        assertEquals(listOf(16L, 82L), times)
    }

    /** [runShimmer] on a [FakeClock] until its time passes [limitMs] or the loop returns. */
    private fun run(active: Boolean, limitMs: Long): List<Set> {
        val clock = FakeClock(limitMs)
        val values = ArrayList<Set>()
        runBlocking(clock) {
            withTimeout(10_000) {
                launch { runShimmer(active, { values += Set(clock.nowMs, it) }, clock::pause) }.join()
            }
        }
        return values
    }
}

/**
 * A frame clock for tests whose time moves only on its own frames (16 ms each) and pauses ([pause]); once the time passes [limitMs] the
 * coroutine that waits on it is cancelled, so a loop that lost its pause between frames still moves the time and ends instead of hanging.
 */
internal class FakeClock(private val limitMs: Long) : MonotonicFrameClock {
    var nowMs: Long = 0
        private set

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        advance(16)
        return onFrame(nowMs * 1_000_000)
    }

    /** The composables' and the loop's `pause`: moves the time by [ms]. */
    suspend fun pause(ms: Long) {
        advance(ms)
    }

    /** The test's own step: moves the time by 1 ms and yields, never cancelling. */
    suspend fun step() {
        nowMs += 1
        yield()
    }

    /** Moves the time by [ms] and yields; past the limit, cancels the calling coroutine. */
    private suspend fun advance(ms: Long) {
        nowMs += ms
        yield()
        if (nowMs > limitMs) throw CancellationException("past the test's limit of $limitMs ms")
    }
}
