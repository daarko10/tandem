package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** What a tab is made from: an open session's id, its current title, status and attention (desktop-parity spec 18.4). */
data class TabSource(val id: SessionId, val title: String, val status: SessionStatus, val attention: Boolean)

/** A session's tab in the tool window's header: its [label], its [tooltip] (the content's description), and what its icon shows. */
data class SessionTab(val id: SessionId, val label: String, val tooltip: String, val status: SessionStatus, val attention: Boolean)

/** A tab's label while the session's title is still its id (a new session before its first prompt). */
const val NEW_SESSION_LABEL: String = "New session"

/** A tab's label is cut at this many characters, then `…`. */
const val TAB_LABEL_MAX: Int = 32

/** The word of the sidebar's status dot, which a tab's tooltip repeats (spec 18.2). */
fun statusWord(status: SessionStatus): String = when (status) {
    SessionStatus.RUNNING -> "running"
    SessionStatus.WAITING -> "waiting for permission"
    SessionStatus.IDLE -> "idle"
    SessionStatus.STOPPED -> "stopped"
}

/**
 * The tabs of the [open] sessions, in their order (spec 18.1): a session has one while its process runs or starts, while it is
 * [selected], or while it needs the user (its attention), so a tab that leaves and comes back takes its old place.
 */
fun sessionTabs(open: List<TabSource>, selected: SessionId?): ImmutableList<SessionTab> =
    open.filter { it.status != SessionStatus.STOPPED || it.id == selected || it.attention }.map { source ->
        val name = if (source.title == source.id) NEW_SESSION_LABEL else source.title
        val label = if (name.length > TAB_LABEL_MAX) name.take(TAB_LABEL_MAX) + "…" else name
        val tooltip = buildString {
            append(name).append(" · ").append(statusWord(source.status))
            if (source.attention) append(" · needs you")
        }
        SessionTab(source.id, label, tooltip, source.status, source.attention)
    }.toImmutableList()

/**
 * The tab to select when [closing], the selected one, closes (spec 18.2): the nearest one after it in [tabs] that is not in
 * [exclude] (tabs whose own close is under way), else the nearest one before it, else none. None when [closing] has no tab.
 */
fun tabNeighbour(tabs: List<SessionId>, closing: SessionId, exclude: Set<SessionId> = emptySet()): SessionId? {
    val position = tabs.indexOf(closing)
    if (position < 0) return null
    return tabs.drop(position + 1).firstOrNull { it !in exclude } ?: tabs.take(position).lastOrNull { it !in exclude }
}
