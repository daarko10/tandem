package dev.daarkolabs.tandem.chat

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val MINUTE: Long = 60_000L
private const val HOUR: Long = 60 * MINUTE
private const val DAY: Long = 24 * HOUR

/**
 * How long before [nowMillis] a reply came at [atMillis], as the hover row says it (polish spec 2.6): `just now` under a minute,
 * then minutes, hours and days. A time after [nowMillis] (another machine's transcript, clock skew) reads `just now`.
 */
fun relativeTime(atMillis: Long, nowMillis: Long): String {
    val elapsed = maxOf(0L, nowMillis - atMillis)
    return when {
        elapsed < MINUTE -> "just now"
        elapsed < 2 * MINUTE -> "1 minute ago"
        elapsed < HOUR -> "${elapsed / MINUTE} minutes ago"
        elapsed < 2 * HOUR -> "1 hour ago"
        elapsed < DAY -> "${elapsed / HOUR} hours ago"
        elapsed < 2 * DAY -> "1 day ago"
        else -> "${elapsed / DAY} days ago"
    }
}

/** [atMillis] as the localized medium date and short time in [zone] and [locale] (`Oct 15, 2026, 8:40 PM` in en-US). */
fun absoluteTime(atMillis: Long, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        .format(Instant.ofEpochMilli(atMillis).atZone(zone))
