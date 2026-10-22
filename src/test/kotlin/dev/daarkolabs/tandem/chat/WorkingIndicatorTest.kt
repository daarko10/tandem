package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.TaskInfo
import dev.daarkolabs.tandem.session.TurnEnd
import dev.daarkolabs.tandem.session.reduce
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The working indicator and the turn footer (spec 4.9, 4.10). */
class WorkingIndicatorTest {
    private val now = 1_000_000L

    private fun row(startedAt: Long = now - 3_000, tokens: Int = 0, waiting: Boolean = false, step: String = "Pondering"): WorkingRow =
        WorkingRow(turnUuid = "u1", startedAtMillis = startedAt, step = step, outputTokens = tokens, waiting = waiting)

    @Test
    fun `verbs are the appendix list`() {
        val verbs = WorkingIndicator.VERBS
        assertEquals(90, verbs.size)
        assertEquals(verbs.sorted(), verbs)
        assertTrue("Pondering" in verbs)
        assertEquals(verbs.distinct(), verbs)
    }

    @Test
    fun `verb is stable per turn and re-rolls per tool call`() {
        assertEquals(WorkingIndicator.verb("u1", null), WorkingIndicator.verb("u1", null))
        assertNotEquals(WorkingIndicator.verb("u1", "t1"), WorkingIndicator.verb("u1", null))
        assertTrue(WorkingIndicator.verb("u1", "t1") in WorkingIndicator.VERBS)
        val all = (0 until 2_000).map { WorkingIndicator.verb("turn-$it", null) }
        assertTrue(all.all { it in WorkingIndicator.VERBS })
        // A hash of Int.MIN_VALUE never yields a negative index: abs(Int.MIN_VALUE) is negative, so the modulo comes first.
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        assertTrue(WorkingIndicator.verb("polygenelubricants", null) in WorkingIndicator.VERBS)
    }

    @Test
    fun `step prefers the agent progress then hidden thinking then the verb`() {
        val options = RowOptions(showThinking = true)
        var through37: String? = null
        var through41: String? = null
        replay("agent-sync") { line, state, rows ->
            when (line) {
                37 -> through37 = WorkingIndicator.step(state, rows.rows, options)
                41 -> {
                    assertEquals(null, state.tasks.values.single().status)
                    assertEquals("Bash", state.tasks.values.single().lastToolName)
                    through41 = WorkingIndicator.step(state, rows.rows, options)
                    assertEquals(through41, assertIs<WorkingRow>(rows.rows.last()).step)
                }
            }
        }
        assertTrue(assertNotNull(through37) in WorkingIndicator.VERBS, through37)
        assertEquals("Running wc -l seed.txt && tail -c1 seed.txt | xxd", through41)

        val hidden = RowOptions(showThinking = false)
        var thinkingSteps = 0
        replay("think-default", options = hidden) { _, state, rows ->
            val streamingThinking = state.entries.any { entry ->
                entry is ChatEntry.Assistant && entry.streaming && entry.blocks.any { it is AssistantBlock.Thinking && it.completedAtMillis == null }
            }
            if (state.turn != null && streamingThinking) {
                thinkingSteps++
                assertEquals("Thinking", WorkingIndicator.step(state, rows.rows, hidden))
                assertEquals("Thinking", assertIs<WorkingRow>(rows.rows.last()).step)
            }
        }
        assertTrue(thinkingSteps > 0)

        val plain = reduce(ChatState(), SessionEvent.UserSent("u1", "hi", atMillis = 0))
        assertEquals(WorkingIndicator.verb("u1", null), WorkingIndicator.step(plain, ChatRowsBuilder(options).build(plain).rows, options))
    }

    @Test
    fun `step skips the agent progress when asked`() {
        val options = RowOptions(showThinking = true)
        var checked = false
        replay("agent-sync") { line, state, rows ->
            if (line == 41) {
                assertEquals("Running wc -l seed.txt && tail -c1 seed.txt | xxd", WorkingIndicator.step(state, rows.rows, options))
                val turn = assertNotNull(state.turn)
                assertEquals(
                    WorkingIndicator.verb(turn.userUuid, turn.lastToolUseId),
                    WorkingIndicator.step(state, rows.rows, options, skipAgent = true),
                )
                checked = true
            }
        }
        assertTrue(checked)
    }

    @Test
    fun `a task of an earlier turn is not the step`() {
        val options = RowOptions(showThinking = true)
        val stale = TaskInfo("k1", "agent-old", "Count lines", "general-purpose", null, "Bash", null, null, null, null)
        val state = reduce(ChatState(tasks = mapOf("k1" to stale)), SessionEvent.UserSent("u1", "hi", atMillis = 0))
        assertEquals(WorkingIndicator.verb("u1", null), WorkingIndicator.step(state, ChatRowsBuilder(options).build(state).rows, options))
    }

    @Test
    fun `step text never repeats the row above`() {
        var checked = 0
        for (name in listOf("tools-read", "agent-sync", "think-default")) {
            replay(name) { line, _, rows ->
                val working = rows.rows.lastOrNull() as? WorkingRow ?: return@replay
                val above = rows.rows.getOrNull(rows.rows.size - 2)
                val label = when (above) {
                    is ToolRow -> above.label.text
                    is ThinkingRow -> above.label
                    else -> null
                }
                if (label != null) {
                    checked++
                    assertNotEquals(label, working.step, "$name:$line")
                    assertFalse(label.startsWith(working.step + "…"), "$name:$line")
                }
            }
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `a verb that would repeat the row above is replaced by the next one`() {
        val options = RowOptions(showThinking = true)
        val uuid = (0 until 10_000).map { "u$it" }.first { WorkingIndicator.verb(it, null) == "Thinking" }
        var state = reduce(ChatState(), SessionEvent.UserSent(uuid, "hi", atMillis = 0))
        val thinking = ChatEntry.Assistant(
            "a1", "a1", null, null, listOf(AssistantBlock.Thinking("", estimatedTokens = 50, startedAtMillis = 0)), 0, true, emptySet(),
        )
        state = state.copy(entries = state.entries + thinking, entryIndex = emptyMap())
        val rows = ChatRowsBuilder(options).build(state).rows
        val step = WorkingIndicator.step(state, rows, options)
        assertEquals("Thinking… · 50 tokens", assertIs<ThinkingRow>(rows[rows.size - 2]).label)
        assertNotEquals("Thinking", step)
        assertTrue(step in WorkingIndicator.VERBS)
    }

    @Test
    fun `formatDuration one decimal under ten seconds`() {
        assertEquals("5.8s", WorkingIndicator.formatDuration(5826))
        assertEquals("2.0s", WorkingIndicator.formatDuration(2006))
        assertEquals("12s", WorkingIndicator.formatDuration(12_000))
    }

    @Test
    fun `text omits tokens until known`() {
        val none = WorkingIndicator.view(row(tokens = 0), now, now)
        assertEquals("Pondering… · 3s", none.text)
        assertFalse(none.text.endsWith(" tokens"))
        val known = WorkingIndicator.view(row(tokens = 1200), now, now)
        assertTrue(known.text.endsWith(" · 1.2k tokens"), known.text)
        assertEquals(WorkingIndicator.Mode.RUNNING, known.mode)
        assertEquals("Claude is working", known.accessibleLabel)
        assertTrue(known.animated)
    }

    @Test
    fun `waiting while a card is pending`() {
        val view = WorkingIndicator.view(row(waiting = true), now, now)
        assertEquals(WorkingIndicator.Mode.WAITING, view.mode)
        assertEquals("Waiting for you · 3s", view.text)
        assertEquals("Claude is waiting for you", view.accessibleLabel)
        assertFalse(view.animated)
    }

    @Test
    fun `not responding after 60 s without a frame and back on the next`() {
        val working = row(startedAt = now - 120_000)
        val silent = WorkingIndicator.view(working, now, lastFrameAtMillis = now - 60_000)
        assertEquals(WorkingIndicator.Mode.NOT_RESPONDING, silent.mode)
        assertEquals("Not responding · 2m 0s", silent.text)
        assertEquals("Claude is not responding", silent.accessibleLabel)
        assertFalse(silent.animated)
        assertEquals(WorkingIndicator.Mode.RUNNING, WorkingIndicator.view(working, now, lastFrameAtMillis = now - 59_999).mode)
        assertEquals(WorkingIndicator.Mode.RUNNING, WorkingIndicator.view(working, now + 1_000, lastFrameAtMillis = now + 500).mode)
    }

    @Test
    fun `silence is measured from the send until the first frame arrives`() {
        assertEquals(WorkingIndicator.Mode.RUNNING, WorkingIndicator.view(row(startedAt = now - 5_000), now, now - 600_000).mode)
        assertEquals(WorkingIndicator.Mode.NOT_RESPONDING, WorkingIndicator.view(row(startedAt = now - 61_000), now, now - 600_000).mode)
    }

    @Test
    fun `animated by default`() {
        val view = WorkingIndicator.view(row(), now, now)
        assertEquals(WorkingIndicator.Mode.RUNNING, view.mode)
        assertTrue(view.animated)
    }

    @Test
    fun `not animated under reduced motion`() {
        val view = WorkingIndicator.view(row(), now, now, reducedMotion = true)
        assertEquals(WorkingIndicator.Mode.RUNNING, view.mode)
        assertFalse(view.animated)
    }

    @Test
    fun `formatElapsed seconds minutes hours`() {
        assertEquals("4s", WorkingIndicator.formatElapsed(4_000))
        assertEquals("43m 11s", WorkingIndicator.formatElapsed(2_591_000))
        assertEquals("1h 1m", WorkingIndicator.formatElapsed(3_660_000))
    }

    @Test
    fun `formatTokens k and m`() {
        assertEquals("312", WorkingIndicator.formatTokens(312))
        assertEquals("1.2k", WorkingIndicator.formatTokens(1_200))
        assertEquals("1.5m", WorkingIndicator.formatTokens(1_500_000))
    }

    @Test
    fun `footer strings`() {
        assertEquals("Worked for 4s", WorkingIndicator.footer(TurnEnd.COMPLETED, 4_000, null, false))
        assertEquals("Worked for 0s", WorkingIndicator.footer(TurnEnd.COMPLETED, null, null, false))
        assertEquals("Interrupted · What should Claude do instead?", WorkingIndicator.footer(TurnEnd.INTERRUPTED, 4_000, null, true))
    }

    @Test
    fun `FAILED mid-response`() {
        assertEquals(
            "Server error mid-response. The response above may be incomplete.",
            WorkingIndicator.footer(TurnEnd.FAILED, 4_000, "API Error: 500 overloaded", textStreamed = true),
        )
    }

    @Test
    fun `FAILED before text`() {
        assertEquals("API Error: 404 model_not_found", WorkingIndicator.footer(TurnEnd.FAILED, null, "API Error: 404 model_not_found", false))
        assertEquals("The turn failed (boom)", WorkingIndicator.footer(TurnEnd.FAILED, null, "boom", false))
        assertEquals("The turn failed", WorkingIndicator.footer(TurnEnd.FAILED, null, null, false))
    }
}
