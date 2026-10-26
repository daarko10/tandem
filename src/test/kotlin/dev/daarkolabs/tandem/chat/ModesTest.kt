package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.PermissionMode.ACCEPT_EDITS
import dev.daarkolabs.tandem.protocol.PermissionMode.AUTO
import dev.daarkolabs.tandem.protocol.PermissionMode.BYPASS_PERMISSIONS
import dev.daarkolabs.tandem.protocol.PermissionMode.DEFAULT
import dev.daarkolabs.tandem.protocol.PermissionMode.DONT_ASK
import dev.daarkolabs.tandem.protocol.PermissionMode.PLAN
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.OFFERED_MODES
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.reduce
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The permission modes the footer offers, with their labels, descriptions and digits (spec 2.3), and the effort labels. */
class ModesTest {
    @Test
    fun `offered modes, labels, descriptions and digits`() {
        val offered = Modes.offered(null, autoSupported = true)
        assertEquals(listOf(AUTO, DEFAULT, ACCEPT_EDITS, PLAN), offered)
        assertEquals(listOf("Auto", "Manual", "Accept edits", "Plan"), offered.map(Modes::label))
        assertEquals(
            listOf(
                "Claude handles permission decisions",
                "Always ask before making changes",
                "Automatically accept all file edits",
                "Create a plan before making changes",
            ),
            offered.map(Modes::description),
        )
        assertEquals(listOf(1, 2, 3, 4), offered.map(Modes::digit))

        val bypass = Modes.offered(BYPASS_PERMISSIONS, autoSupported = true)
        assertEquals(BYPASS_PERMISSIONS, bypass.last())
        assertEquals("Bypass permissions", Modes.label(BYPASS_PERMISSIONS))
        assertNull(Modes.description(BYPASS_PERMISSIONS))
        assertNull(Modes.digit(BYPASS_PERMISSIONS))
        assertEquals("Don't ask", Modes.label(DONT_ASK))
        assertNull(Modes.description(DONT_ASK))
        assertNull(Modes.digit(DONT_ASK))
    }

    @Test
    fun `auto is hidden for a model without supportsAutoMode`() {
        assertEquals(listOf(DEFAULT, ACCEPT_EDITS, PLAN), Modes.offered(DEFAULT, autoSupported = false))
        assertEquals(listOf(AUTO, DEFAULT, ACCEPT_EDITS, PLAN), Modes.offered(AUTO, autoSupported = false))
    }

    @Test
    fun `digits stay with their modes`() {
        for (autoSupported in listOf(true, false)) {
            val offered: List<PermissionMode> = Modes.offered(null, autoSupported)
            assertEquals(4, Modes.digit(offered.last()), "Plan is 4 whether or not Auto is offered")
        }
        assertEquals(4, Modes.digit(PLAN))
    }

    @Test
    fun `effort labels`() {
        assertEquals("Extra high", Modes.effortLabel("xhigh"))
        assertEquals("Max", Modes.effortLabel("max"))
        assertEquals("Low", Modes.effortLabel("low"))
        assertEquals("Medium", Modes.effortLabel("medium"))
        assertEquals("High", Modes.effortLabel("high"))
        assertEquals("Ultra", Modes.effortLabel("ultra"), "an unknown level, its first letter upper-cased")
    }

    @Test
    fun `next walks the CLI's cycle`() {
        assertEquals(ACCEPT_EDITS, Modes.next(DEFAULT, true))
        assertEquals(PLAN, Modes.next(ACCEPT_EDITS, true))
        assertEquals(AUTO, Modes.next(PLAN, true))
        assertEquals(DEFAULT, Modes.next(AUTO, true))
        assertEquals(DEFAULT, Modes.next(PLAN, false))
        assertEquals(ACCEPT_EDITS, Modes.next(null, true))
        assertEquals(DEFAULT, Modes.next(AUTO, false))
        assertEquals(AUTO, Modes.next(BYPASS_PERMISSIONS, true))
        assertEquals(DEFAULT, Modes.next(BYPASS_PERMISSIONS, false))
        assertEquals(DEFAULT, Modes.next(DONT_ASK, true))
    }

    @Test
    fun `a refused mode is skipped`() {
        assertEquals(DEFAULT, Modes.next(PLAN, true, rejected = setOf(AUTO)))
        assertEquals(PLAN, Modes.next(DEFAULT, true, setOf(ACCEPT_EDITS)))
    }

    @Test
    fun `next never offers bypass or dontAsk`() {
        val subsets = (0 until (1 shl Modes.CYCLE.size)).map { bits -> Modes.CYCLE.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toSet() }
        for (current in PermissionMode.entries + listOf<PermissionMode?>(null)) {
            for (autoSupported in listOf(true, false)) {
                for (rejected in subsets) {
                    val next = Modes.next(current, autoSupported, rejected)
                    assertTrue(next in OFFERED_MODES, "$current, $autoSupported, $rejected -> $next")
                    assertTrue(next != BYPASS_PERMISSIONS && next != DONT_ASK, "$current, $autoSupported, $rejected -> $next")
                }
            }
        }
    }

    /** fork-at's `initialize` models: `sonnet` resolves to claude-sonnet-5-5 with supportsAutoMode, claude-haiku-4-5-20251001 without it. */
    private val forkAtModels: List<ModelOption> = reduce(ChatState(), SessionEvent.Cli(fixtureLine("fork-at", 2))).models

    @Test
    fun `cycleFrom reads supportsAutoMode from the shown model`() {
        assertEquals(AUTO, Modes.cycleFrom(ChatState(models = forkAtModels, activeModel = "claude-sonnet-5-5", permissionMode = PLAN)))
        assertEquals(DEFAULT, Modes.cycleFrom(ChatState(models = forkAtModels, activeModel = "claude-haiku-4-5-20251001", permissionMode = PLAN)))
    }

    @Test
    fun `cycleFrom offers the modes the footer menu offers, known models included`() {
        // A restored session whose process has listed no models: the footer reads the known ones, and so does the cycle.
        val restored = ChatState(activeModel = "claude-sonnet-5-5", permissionMode = PLAN)
        assertTrue(composerModel(restored, forkAtModels).autoSupported)
        assertEquals(AUTO, Modes.cycleFrom(restored, forkAtModels))
        // With none known, the CLI's aliases say nothing of auto: the footer hides it and the cycle skips it.
        assertEquals(false, composerModel(restored).autoSupported)
        assertEquals(DEFAULT, Modes.cycleFrom(restored))
        // A refused mode is skipped as the state records it.
        assertEquals(DEFAULT, Modes.cycleFrom(restored.copy(rejectedPermissionModes = setOf(AUTO)), forkAtModels))
    }
}
