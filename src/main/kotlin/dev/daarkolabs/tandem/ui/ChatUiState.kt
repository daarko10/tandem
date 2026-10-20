package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.daarkolabs.tandem.chat.FollowState

/**
 * What one session's chat remembers on screen (spec 5.6): which rows are open, whether the list follows its bottom, the prompt
 * being typed, the list's state and the rows it last saw. One per session, kept by [ChatPresenters] until the session closes, so
 * switching sessions and scrolling rows out of view lose none of the open rows, the prompt or the scroll position. Used on the EDT
 * only.
 */
internal class ChatUiState {
    // One snapshot state per key, so that opening a row recomposes that row only (a SnapshotStateMap records reads against the map).
    private val expanded = HashMap<String, MutableState<Boolean>>()

    /** Whether the row (or group sub-row `c:<call id>`) keyed [key] is open; closed until opened. */
    fun expanded(key: String): MutableState<Boolean> = expanded.getOrPut(key) { mutableStateOf(false) }

    /** Asks for the parsed Markdown of the body keyed [key]: [ChatPresenter.expandBody]. It never parses on the calling thread. */
    var onExpand: (String) -> Unit = {}

    /** Whether the list follows its bottom, and how many rows were added since it stopped (spec 4.16). */
    val follow: MutableState<FollowState> = mutableStateOf(FollowState())

    /** The prompt being typed in the composer (spec 4.15). */
    val draft: TextFieldState = TextFieldState()

    val listState: LazyListState = LazyListState()

    // The row keys and the last row the list saw at its last emission; null until the first. Kept here, not in the panel, so that
    // rows added while another session is shown count when this one is shown again (spec 4.16, 7.8).
    private var seenKeys: HashSet<String>? = null
    private var seenLast: RenderedRow? = null

    /**
     * Records [rows] as the rows the list has seen and returns how many of them were added since the last call, counted by key so
     * that grouping (a lone call joining a group shrinks the list) never miscounts, or 0 when only the last row changed (a streamed
     * delta grows it). Null when nothing changed, and on the first call, which only records.
     */
    fun appendedSinceSeen(rows: List<RenderedRow>): Int? {
        val keys = seenKeys
        val added = if (keys == null) 0 else rows.count { it.row.key !in keys }
        val tailChanged = rows.lastOrNull() !== seenLast
        seenKeys = rows.mapTo(HashSet(rows.size)) { it.row.key }
        seenLast = rows.lastOrNull()
        return if (keys != null && (added > 0 || tailChanged)) added else null
    }
}
