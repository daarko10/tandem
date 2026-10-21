package dev.daarkolabs.tandem.chat

import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals

/** The hover row's time (polish spec 2.6): relative under the reply, absolute in its tooltip. */
class RelativeTimeTest {
    @Test
    fun `relative time thresholds`() {
        assertEquals("just now", relativeTime(0, 59_000))
        assertEquals("1 minute ago", relativeTime(0, 60_000))
        assertEquals("59 minutes ago", relativeTime(0, 59 * 60_000L + 59_000))
        assertEquals("1 hour ago", relativeTime(0, 3_600_000))
        assertEquals("23 hours ago", relativeTime(0, 23 * 3_600_000L))
        assertEquals("1 day ago", relativeTime(0, 86_400_000))
        assertEquals("6 days ago", relativeTime(0, 6 * 86_400_000L))
    }

    // Review Focus 4: a reply stamped later than the host's clock (another machine's transcript, clock skew).
    @Test
    fun `a time in the future reads just now`() {
        assertEquals("just now", relativeTime(10_000, 0))
    }

    @Test
    fun `absolute time is the localized medium date and short time`() {
        // 2026-10-15T20:40:00Z; JDK 21's CLDR data puts a narrow no-break space before PM.
        assertEquals("Oct 15, 2026, 8:40 PM", absoluteTime(1_792_096_800_000L, ZoneOffset.UTC, Locale.US))
    }
}
