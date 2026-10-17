package dev.daarkolabs.tandem.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.daarkolabs.tandem.session.transcript.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

class SessionSidebarTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now: Long = millis(2026, 10, 17, 15, 30)

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun ago(then: Long): String = relativeTime(then, now, zone, Locale.US)

    @Test
    fun `a session list that cannot be read again is logged, not thrown at the composition`() = runBlocking<Unit> {
        var tried = false
        refreshLogged {
            tried = true
            throw IllegalStateException("a bug in the index")
        }
        assertTrue(tried)
        // A cancellation is no failure: the sidebar leaving the screen cancels the refresh, and that must go through.
        assertFailsWith<CancellationException> { refreshLogged { throw CancellationException("left the screen") } }
    }

    @Test
    fun `under a minute is just now, and so is a time after now`() {
        assertEquals("just now", ago(now - 59_000))
        assertEquals("just now", ago(now + 5_000))
    }

    @Test
    fun `minutes under an hour`() {
        assertEquals("1 min ago", ago(now - 60_000))
        assertEquals("3 min ago", ago(now - 3 * 60_000 - 30_000))
        assertEquals("59 min ago", ago(now - 59 * 60_000))
    }

    @Test
    fun `hours on the same day`() {
        assertEquals("1 h ago", ago(now - 60 * 60_000))
        assertEquals("2 h ago", ago(millis(2026, 10, 17, 13, 0)))
        assertEquals("15 h ago", ago(millis(2026, 10, 17, 0, 1)))
    }

    @Test
    fun `the day before is yesterday, even less than a day ago`() {
        assertEquals("yesterday", ago(millis(2026, 10, 16, 23, 59)))
        assertEquals("yesterday", ago(millis(2026, 10, 16, 0, 0)))
    }

    @Test
    fun `earlier days are a short date`() {
        assertEquals("10/15/26", ago(millis(2026, 10, 15, 12, 0)))
        assertEquals("3/1/25", ago(millis(2025, 3, 1, 12, 0)))
    }

    @Test
    fun `minutes win over the day boundary`() {
        val justAfterMidnight = millis(2026, 10, 17, 0, 20)
        assertEquals("30 min ago", relativeTime(millis(2026, 10, 16, 23, 50), justAfterMidnight, zone, Locale.US))
    }

    @Test
    fun `the filter keeps the titles that contain it, ignoring case and surrounding blanks`() {
        val sessions = listOf("Fix the build", "Reply with pong", "BUILD the docs").map { title ->
            SessionSummary(id = title, title = title, lastModified = 0, firstPrompt = null, file = null)
        }
        assertEquals(listOf("Fix the build", "BUILD the docs"), sessions.filteredBy("  build ").map { it.title })
        assertEquals(sessions, sessions.filteredBy(""))
        assertEquals(sessions, sessions.filteredBy("   "))
        assertEquals(emptyList(), sessions.filteredBy("nothing"))
    }

    /**
     * What a focused row in [mode] makes of the key with AWT code [code] going down, the way the IDE reports it, on macOS when [mac]
     * and elsewhere when not: every key but Backspace means the same on both.
     */
    private fun press(code: Int, mode: RowMode = RowMode.NORMAL, numPad: Boolean = false, mac: Boolean = false): RowKey? =
        rowKey(key(code, numPad), KeyEventType.KeyDown, mode, mac)

    private fun key(code: Int, numPad: Boolean = false): Key =
        Key(code, if (numPad) AwtKeyEvent.KEY_LOCATION_NUMPAD else AwtKeyEvent.KEY_LOCATION_STANDARD)

    @Test
    fun `Enter, the keypad's Enter and Space show a focused row's session`() {
        assertEquals(RowKey.SHOW, press(AwtKeyEvent.VK_ENTER))
        assertEquals(RowKey.SHOW, press(AwtKeyEvent.VK_ENTER, numPad = true))
        assertEquals(RowKey.SHOW, press(AwtKeyEvent.VK_SPACE))
    }

    @Test
    fun `Down and Up move to the next and the previous row, Home and End to the first and the last`() {
        assertEquals(RowKey.NEXT, press(AwtKeyEvent.VK_DOWN))
        assertEquals(RowKey.PREVIOUS, press(AwtKeyEvent.VK_UP))
        assertEquals(RowKey.FIRST, press(AwtKeyEvent.VK_HOME))
        assertEquals(RowKey.LAST, press(AwtKeyEvent.VK_END))
    }

    @Test
    fun `Enter, the keypad's Enter and Space open the menu of a focused row's ⋯ as they go down`() {
        for (key in listOf(key(AwtKeyEvent.VK_ENTER), key(AwtKeyEvent.VK_ENTER, numPad = true), key(AwtKeyEvent.VK_SPACE))) {
            assertTrue(opensMenu(key, KeyEventType.KeyDown), key.toString())
            assertFalse(opensMenu(key, KeyEventType.KeyUp), key.toString())
        }
        assertFalse(opensMenu(key(AwtKeyEvent.VK_DELETE), KeyEventType.KeyDown))
        assertFalse(opensMenu(key(AwtKeyEvent.VK_CONTEXT_MENU), KeyEventType.KeyDown))
    }

    @Test
    fun `the IDE's keys are left to it, the context menu key among them`() {
        // ShowPopupMenu: it clicks the focused row's middle, which the row gives it as CONTEXT_MENU_POINT.
        assertNull(press(AwtKeyEvent.VK_CONTEXT_MENU))
        // Shift+F10 is Run: the row leaves F10 alone, with Shift or without.
        assertNull(press(AwtKeyEvent.VK_F10))
        assertNull(press(AwtKeyEvent.VK_LEFT))
        assertNull(press(AwtKeyEvent.VK_TAB))
    }

    @Test
    fun `Delete asks whether to delete, and Escape takes the question back`() {
        assertEquals(RowKey.DELETE, press(AwtKeyEvent.VK_DELETE))
        // The Mac's "delete" key is Backspace, alone or with Command, as in Finder; elsewhere Backspace is left to the IDE.
        assertEquals(RowKey.DELETE, press(AwtKeyEvent.VK_BACK_SPACE, mac = true))
        assertNull(press(AwtKeyEvent.VK_BACK_SPACE, mac = false))
        assertNull(press(AwtKeyEvent.VK_BACK_SPACE, RowMode.CONFIRMING_DELETE, mac = true))
        assertEquals(RowKey.CANCEL_DELETE, press(AwtKeyEvent.VK_ESCAPE, RowMode.CONFIRMING_DELETE))
        // Escape on a row that asks nothing is left to the sidebar over the chat, which it closes.
        assertNull(press(AwtKeyEvent.VK_ESCAPE))
    }

    @Test
    fun `on macOS every key but Backspace means what it means elsewhere`() {
        val codes = listOf(
            AwtKeyEvent.VK_ENTER, AwtKeyEvent.VK_SPACE, AwtKeyEvent.VK_DOWN, AwtKeyEvent.VK_UP, AwtKeyEvent.VK_HOME, AwtKeyEvent.VK_END,
            AwtKeyEvent.VK_DELETE, AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.VK_CONTEXT_MENU, AwtKeyEvent.VK_F10, AwtKeyEvent.VK_TAB,
        )
        for (mode in RowMode.entries) {
            for (code in codes) assertEquals(press(code, mode), press(code, mode, mac = true), "${AwtKeyEvent.getKeyText(code)} in $mode")
        }
    }

    @Test
    fun `while it asks or deletes, a row takes no other key, and no key coming up`() {
        for (code in listOf(AwtKeyEvent.VK_ENTER, AwtKeyEvent.VK_DELETE, AwtKeyEvent.VK_DOWN, AwtKeyEvent.VK_HOME)) {
            assertNull(press(code, RowMode.CONFIRMING_DELETE), AwtKeyEvent.getKeyText(code))
        }
        for (code in listOf(AwtKeyEvent.VK_ENTER, AwtKeyEvent.VK_DELETE, AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.VK_DOWN)) {
            assertNull(press(code, RowMode.DELETING), AwtKeyEvent.getKeyText(code))
        }
        assertNull(rowKey(key(AwtKeyEvent.VK_ENTER), KeyEventType.KeyUp, RowMode.NORMAL))
        assertNull(rowKey(key(AwtKeyEvent.VK_DOWN), KeyEventType.KeyUp, RowMode.NORMAL))
    }

    @Test
    fun `the open forks that a delete leaves unable to start are those without a transcript of their own`() {
        val forkOf = mapOf("fork-new" to "original", "fork-written" to "original", "other-fork" to "other", "plain" to null)
        val written = setOf("original", "fork-written")
        assertEquals(listOf("fork-new"), forksStartingFrom("original", forkOf) { it in written })
        assertEquals(listOf("other-fork"), forksStartingFrom("other", forkOf) { it in written })
        assertEquals(emptyList(), forksStartingFrom("plain", forkOf) { it in written })
    }

    @Test
    fun `deleting a session that open forks start from warns about them`() {
        assertNull(forkWarning(0))
        assertEquals("An open fork of it has not started yet, and could not start without it.", forkWarning(1))
        assertEquals("2 open forks of it have not started yet, and could not start without it.", forkWarning(2))
    }
}
