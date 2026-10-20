package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.daarkolabs.tandem.chat.FollowState

/**
 * What one session's chat remembers on screen (spec 5.6): which rows are open, whether the list follows its bottom, the prompt
 * being typed and the list's state. One per session, kept by [ChatPresenters] until the session closes, so switching sessions and
 * scrolling rows out of view lose none of the open rows or the prompt (until T9's autoscroll, showing a session again scrolls it to
 * the bottom). Used on the EDT only.
 */
internal class ChatUiState {
    // One snapshot state per key, so that opening a row recomposes that row only (a SnapshotStateMap records reads against the map).
    private val expanded = HashMap<String, MutableState<Boolean>>()

    /** Whether the row (or group sub-row `c:<call id>`) keyed [key] is open; closed until opened. */
    fun expanded(key: String): MutableState<Boolean> = expanded.getOrPut(key) { mutableStateOf(false) }

    /** Asks for the parsed Markdown of the body keyed [key]: [ChatPresenter.expandBody]. It never parses on the calling thread. */
    var onExpand: (String) -> Unit = {}

    val follow: MutableState<FollowState> = mutableStateOf(FollowState())

    val draft: TextFieldState = TextFieldState()

    val listState: LazyListState = LazyListState()
}
