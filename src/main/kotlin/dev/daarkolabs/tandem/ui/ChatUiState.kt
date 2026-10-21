package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.daarkolabs.tandem.chat.ChatRow
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

    // The row keys, the row models and the list's height at the list's last emission, and whether the list has had an emission
    // since it last showed this session. Kept here, not in the panel, so that rows added while another session is shown count when
    // this one is shown again (spec 4.16, 7.8). Null keys until the first emission.
    private var seenKeys: HashSet<String>? = null
    private var seenRows: List<ChatRow> = emptyList()
    private var seenHeight: Int = 0
    private var shown: Boolean = false

    /** The list stops showing this session: its next emission pins the bottom while following ([seen]). */
    fun hidden() {
        shown = false
    }

    /**
     * Records [rows], drawn in a list [height] px tall, as what the list has seen, and says what changed since the last call
     * (spec 4.16). Rows are compared by their models ([RenderedRow.row]), so a body's Markdown arriving for an opened row
     * ([RenderedRow.bodies]) changes nothing: opening a row never scrolls.
     */
    fun seen(rows: List<RenderedRow>, height: Int): ListChange {
        val keys = seenKeys
        val models = rows.map { it.row }
        val rowsChanged = models != seenRows
        // Counted by key, so that grouping (a lone call joining a group shrinks the list) never miscounts.
        val added = if (keys == null) 0 else rows.count { it.row.key !in keys }
        val tailChanged = models.lastOrNull() != seenRows.lastOrNull()
        val pin = !shown || rowsChanged || height != seenHeight
        seenKeys = rows.mapTo(HashSet(rows.size)) { it.row.key }
        seenRows = models
        seenHeight = height
        shown = true
        return ListChange(pin = pin, appended = if (keys != null && (added > 0 || tailChanged)) added else null)
    }
}

/**
 * What one emission of the chat list changed since the list last saw it (spec 4.16): whether a following list pins its bottom
 * ([pin]: the first emission since the session was shown, a changed row model or a changed height), and for a paused list how many
 * rows were added ([appended]: 0 when only the last row changed, as a streamed delta grows it; null when no row was added and the
 * last row did not change, and on the very first emission, which only records).
 */
internal data class ListChange(val pin: Boolean, val appended: Int?)
