package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.session.transcript.SessionSummary
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals

class SessionSidebarTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now: Long = millis(2026, 10, 17, 15, 30)

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun ago(then: Long): String = relativeTime(then, now, zone, Locale.US)

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
}
