package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.session.SessionStatus
import dev.daarkolabs.tandem.session.SessionStatus.IDLE
import dev.daarkolabs.tandem.session.SessionStatus.RUNNING
import dev.daarkolabs.tandem.session.SessionStatus.STOPPED
import dev.daarkolabs.tandem.session.SessionStatus.WAITING
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which sessions get a tab and what it shows (desktop-parity spec 18.1, 18.2, 9.8). */
class SessionTabsTest {
    private fun source(id: String, status: SessionStatus = IDLE, attention: Boolean = false, title: String = id.uppercase()): TabSource =
        TabSource(id, title, status, attention)

    // spec: kept while live, selected or with attention, dropped when stopped otherwise
    @Test
    fun `kept while live, selected or with attention, dropped when stopped otherwise`() {
        val open = listOf(
            source("a", RUNNING),
            source("b", STOPPED),
            source("c", STOPPED, attention = true),
            source("d", IDLE),
            source("e", STOPPED),
            source("f", WAITING),
        )
        assertEquals(listOf("a", "c", "d", "e", "f"), sessionTabs(open, selected = "e").map { it.id })
    }

    // spec: the sessions' order is kept when a tab leaves and comes back
    @Test
    fun `the sessions' order is kept when a tab leaves and comes back`() {
        val a = source("a")
        val c = source("c")
        assertEquals(listOf("a", "b", "c"), sessionTabs(listOf(a, source("b", IDLE), c), null).map { it.id })
        assertEquals(listOf("a", "c"), sessionTabs(listOf(a, source("b", STOPPED), c), null).map { it.id })
        assertEquals(listOf("a", "b", "c"), sessionTabs(listOf(a, source("b", RUNNING), c), null).map { it.id })
    }

    // spec: New session while the title is the id
    @Test
    fun `New session while the title is the id`() {
        val tab = sessionTabs(listOf(TabSource("s-1", "s-1", IDLE, false)), null).single()
        assertEquals("New session", tab.label)
        assertEquals("New session · idle", tab.tooltip)
    }

    // spec: a 40-character title is cut at 32 with an ellipsis
    @Test
    fun `a 40-character title is cut at 32 with an ellipsis`() {
        val long = "x".repeat(40)
        val tab = sessionTabs(listOf(source("a", title = long)), null).single()
        assertEquals("x".repeat(32) + "…", tab.label)
        assertTrue(tab.tooltip.startsWith("$long · "), tab.tooltip)
        val exact = "y".repeat(32)
        assertEquals(exact, sessionTabs(listOf(source("b", title = exact)), null).single().label)
    }

    // spec: the tooltip names the status and attention
    @Test
    fun `the tooltip names the status and attention`() {
        assertEquals("t · waiting for permission · needs you", sessionTabs(listOf(source("a", WAITING, true, "t")), null).single().tooltip)
        assertEquals("t · running", sessionTabs(listOf(source("a", RUNNING, title = "t")), null).single().tooltip)
        assertEquals("t · stopped", sessionTabs(listOf(source("a", STOPPED, title = "t")), "a").single().tooltip)
        val tab = sessionTabs(listOf(source("a", WAITING, true, "t")), null).single()
        assertEquals(SessionTab("a", "t", "t · waiting for permission · needs you", WAITING, true), tab)
    }

    // spec: tabNeighbour takes the next, else the previous, else none
    @Test
    fun `tabNeighbour takes the next, else the previous, else none`() {
        assertEquals("c", tabNeighbour(listOf("a", "b", "c"), "b"))
        assertEquals("b", tabNeighbour(listOf("a", "b", "c"), "c"))
        assertNull(tabNeighbour(listOf("a"), "a"))
        assertNull(tabNeighbour(listOf("a", "b"), "x"))
    }

    // spec: tabNeighbour skips the tabs that are closing
    @Test
    fun `tabNeighbour skips the tabs that are closing`() {
        assertEquals("c", tabNeighbour(listOf("a", "b", "c"), "b", setOf("a", "b")))
        assertNull(tabNeighbour(listOf("a", "b", "c"), "c", setOf("a", "b", "c")))
        assertEquals("d", tabNeighbour(listOf("a", "b", "c", "d"), "b", setOf("b", "c")))
        assertEquals("a", tabNeighbour(listOf("a", "b", "c"), "c", setOf("b")))
    }

    @Test
    fun `every status has the sidebar's word`() {
        assertEquals(listOf("running", "waiting for permission", "idle", "stopped"), SessionStatus.entries.map(::statusWord))
    }
}
