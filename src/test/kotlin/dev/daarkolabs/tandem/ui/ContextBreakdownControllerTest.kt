package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.fixtureLine
import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ContextBreakdown
import dev.daarkolabs.tandem.protocol.ControlPayloads
import dev.daarkolabs.tandem.session.ContextControls
import dev.daarkolabs.tandem.session.ContextUsage
import dev.daarkolabs.tandem.session.ControlResult
import dev.daarkolabs.tandem.session.McpError
import dev.daarkolabs.tandem.session.McpResult
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The context popover's controller (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 3.1, 4.4, 9.4) against a recording
 * [ContextControls] and plain flows, under `runBlocking` with `Dispatchers.Default` injected (plan deviation 1: no
 * `kotlinx-coroutines-test` beside the platform's coroutines).
 */
class ContextBreakdownControllerTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val breakdown = MutableStateFlow<ContextBreakdown?>(null)
    private val ring = MutableStateFlow<ContextUsage?>(null)
    private val turnsEnded = MutableStateFlow(0)
    private val status = MutableStateFlow(SessionStatus.IDLE)
    private val controls = RecordingControls()

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(): ContextBreakdownController = ContextBreakdownController(
        SESSION, breakdown, ring, turnsEnded, status, controls, scope, home = null, projectRoot = null, dispatcher = Dispatchers.Default,
    )

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    /** Waits until [count] calls have been made, then long enough for one more to have shown, had there been one. */
    private suspend fun settleAt(count: Int) {
        waitUntil { controls.calls.get() >= count }
        delay(SETTLE_MS)
        assertEquals(count, controls.calls.get())
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        while (!condition()) delay(5)
    }

    @Test
    fun `one request on start`() = test {
        controller()
        settleAt(1)
    }

    @Test
    fun `one request per turn end while open`() = test {
        controller()
        settleAt(1)
        turnsEnded.value = 1
        settleAt(2)
        turnsEnded.value = 2
        settleAt(3)
    }

    @Test
    fun `none after close`() = test {
        val controller = controller()
        settleAt(1)
        controller.close()
        turnsEnded.value = 1
        controller.retry()
        settleAt(1)
    }

    @Test
    fun `an error shows the CLI's text`() = test {
        controls.answers += McpResult.Failed(McpError.Rejected("Unsupported control request subtype: get_context_usage"))
        val controller = controller()
        assertEquals(
            FetchState.Failed("Could not load the breakdown: Unsupported control request subtype: get_context_usage"),
            controller.fetch.first { it is FetchState.Failed },
        )
        controls.answers += McpResult.Failed(McpError.TimedOut(30))
        controller.retry()
        assertEquals(FetchState.Failed("The CLI did not answer within 30 s."), controller.fetch.first { it == FetchState.Failed("The CLI did not answer within 30 s.") })
        controls.answers += McpResult.Failed(McpError.Rejected("the CLI's answer had no categories"))
        controller.retry()
        val noCategories = FetchState.Failed("Could not load the breakdown: the CLI's answer had no categories")
        assertEquals(noCategories, controller.fetch.first { it == noCategories })
        controls.answers += McpResult.Failed(McpError.NotRunning)
        controller.retry()
        assertEquals(FetchState.Stopped, controller.fetch.first { it == FetchState.Stopped })
        controls.answers += McpResult.Failed(McpError.Ended)
        controller.retry()
        settleAt(5)
        assertEquals(FetchState.Stopped, controller.fetch.value)
    }

    @Test
    fun `stopped while the session is stopped, and a request when it starts`() = test {
        status.value = SessionStatus.STOPPED
        val controller = controller()
        assertEquals(FetchState.Stopped, controller.fetch.value)
        settleAt(0)
        assertEquals(FetchState.Stopped, controller.fetch.value)
        status.value = SessionStatus.IDLE
        settleAt(1)
        assertEquals(FetchState.Idle, controller.fetch.first { it == FetchState.Idle })
        status.value = SessionStatus.STOPPED
        assertEquals(FetchState.Stopped, controller.fetch.first { it == FetchState.Stopped })
        settleAt(1)
    }

    @Test
    fun `a refresh during a request runs once after it`() = test {
        val gate = controls.hold()
        controller()
        waitUntil { controls.calls.get() == 1 }
        turnsEnded.value = 1
        delay(SETTLE_MS)
        turnsEnded.value = 2
        delay(SETTLE_MS)
        assertEquals(1, controls.calls.get())
        gate.complete(Unit)
        settleAt(2)
    }

    @Test
    fun `retry asks again`() = test {
        val controller = controller()
        settleAt(1)
        controller.retry()
        settleAt(2)
    }

    @Test
    fun `the fetch is loading while a request runs`() = test {
        var gate = controls.hold()
        val controller = controller()
        assertEquals(FetchState.Loading, controller.fetch.first { it == FetchState.Loading })
        gate.complete(Unit)
        assertEquals(FetchState.Idle, controller.fetch.first { it == FetchState.Idle })
        controls.answers += McpResult.Failed(McpError.Rejected("x"))
        controller.retry()
        assertEquals(FetchState.Failed("Could not load the breakdown: x"), controller.fetch.first { it is FetchState.Failed })
        gate = controls.hold()
        controller.retry()
        assertEquals(FetchState.Loading, controller.fetch.first { it == FetchState.Loading })
        gate.complete(Unit)
        assertEquals(FetchState.Idle, controller.fetch.first { it == FetchState.Idle })
    }

    @Test
    fun `the category list opens collapsed and toggles`() = test {
        val controller = controller()
        assertFalse(controller.rowsOpen.value)
        controller.toggleRows()
        assertTrue(controller.rowsOpen.value)
        controller.toggleRows()
        assertFalse(controller.rowsOpen.value)
    }

    @Test
    fun `the model follows the breakdown and the ring`() = test {
        val b17 = ControlPayloads.contextUsage((fixtureLine("parity-context", 17) as CliMessage.ControlResponse).payload)!!
        val controller = controller()
        breakdown.value = b17
        val full = controller.model.first { it != null && it.rows.isNotEmpty() }!!
        assertEquals("38.0k / 1M (4%)", full.value)
        assertEquals(8, full.rows.size)
        breakdown.value = null
        ring.value = ContextUsage(38032, 1_000_000)
        val ringOnly = controller.model.first { it != null && it.rows.isEmpty() && it.value != null }!!
        assertEquals("38.0k / 1M (4%)", ringOnly.value)
        assertTrue(ringOnly.rows.isEmpty())
    }

    @Test
    fun `a reopened popover has no model until its first build, then the kept answer`() = test {
        val b17 = ControlPayloads.contextUsage((fixtureLine("parity-context", 17) as CliMessage.ControlResponse).payload)!!
        breakdown.value = b17
        // The build waits behind a held task on a single thread, so the seed is all the popover could draw meanwhile.
        val executor = Executors.newSingleThreadExecutor()
        val held = CountDownLatch(1)
        executor.execute { held.await() }
        try {
            val controller = ContextBreakdownController(
                SESSION, breakdown, ring, turnsEnded, status, controls, scope, home = null, projectRoot = null,
                dispatcher = executor.asCoroutineDispatcher(),
            )
            assertNull(controller.model.value, "no frame is drawn from a seed without the kept rows")
            held.countDown()
            val first = controller.model.first { it != null }!!
            assertEquals(8, first.rows.size)
            assertEquals("38.0k / 1M (4%)", first.value)
            controller.close()
        } finally {
            held.countDown()
            executor.shutdownNow()
        }
    }

    /**
     * Answers each call with the next of [answers] (`Ok` once they run out), counting it in [calls] when it starts; [hold] makes the
     * next call wait until the returned gate completes.
     */
    private class RecordingControls : ContextControls {
        val calls = AtomicInteger()
        val answers = ConcurrentLinkedQueue<ControlResult<ContextBreakdown>>()

        @Volatile
        private var gate: CompletableDeferred<Unit>? = null

        fun hold(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gate = it }

        override suspend fun contextBreakdown(id: SessionId): ControlResult<ContextBreakdown> {
            calls.incrementAndGet()
            gate?.let { held ->
                gate = null
                held.await()
            }
            return answers.poll() ?: McpResult.Ok(EMPTY)
        }
    }

    private companion object {
        const val SESSION: SessionId = "s1"

        /** Long enough for a coalesced or stray request to have started, had there been one. */
        const val SETTLE_MS = 300L

        val EMPTY = ContextBreakdown(
            totalTokens = 0, maxTokens = 1, rawMaxTokens = null, percentage = 0, model = null, categories = emptyList(),
            memoryFiles = emptyList(), mcpTools = emptyList(), mcpInstructions = emptyList(), agents = emptyList(), skills = emptyList(),
        )
    }
}
