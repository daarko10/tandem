package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.ImageBitmap
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

    // One snapshot state per body part of each call (`c:<call id>`), so that a click on `Show more` recomposes that body only and
    // a collapse writes the collapsed row's owners' states only (polish spec 2.2, 6).
    private val clamps = HashMap<String, HashMap<String, MutableState<Boolean>>>()

    /**
     * Whether the clamped body part [part] (`body`, `command`, `input` or `output`) of the call [owner] (`c:<call id>`, wherever
     * the body renders: a lone row, a group's sub-row, a row nested in an Agent body) shows in full; clamped until `Show more`.
     */
    fun clampOpen(owner: String, part: String): MutableState<Boolean> =
        clamps.getOrPut(owner) { HashMap() }.getOrPut(part) { mutableStateOf(false) }

    /**
     * Opens or closes the row keyed [key] (polish spec 2.1, 2.2). Opening it also opens [opensWith]: a lone row passes its call's
     * `c:<call id>`, so that when the next call joins it and it becomes a group under the same key, the call's sub-row is already
     * open. Closing it clamps again every body part of each of [owners] (`clampOwners(row)`), so the next open shows them clamped.
     */
    fun toggle(key: String, owners: Collection<String> = emptyList(), opensWith: String? = null) {
        val state = expanded(key)
        val open = !state.value
        state.value = open
        if (open) {
            if (opensWith != null) expanded(opensWith).value = true
        } else {
            owners.forEach { owner -> clamps[owner]?.values?.forEach { it.value = false } }
        }
    }

    /** Asks for the parsed Markdown of the body keyed [key]: [ChatPresenter.expandBody]. It never parses on the calling thread. */
    var onExpand: (String) -> Unit = {}

    /** Whether the list follows its bottom, and how many rows were added since it stopped (spec 4.16). */
    val follow: MutableState<FollowState> = mutableStateOf(FollowState())

    /** The prompt being typed in the composer (spec 4.15). */
    val draft: TextFieldState = TextFieldState()

    /** The images attached to the prompt being typed, in order (polish spec 2.7); sent and cleared with it. */
    val attachments: SnapshotStateList<PendingImage> = mutableStateListOf()

    /** Why the last image was not attached, shown above the composer until the next send or its close (polish spec 2.7). */
    val composerNotice: MutableState<String?> = mutableStateOf(null)

    val listState: LazyListState = LazyListState()

    /**
     * Whether the session's MCP panel is open over its chat (MCP spec 3.2, 4.4): [openMcpPanel] opens it. It stays open while another
     * session is selected, and shows again when this one is.
     */
    val mcpPanelOpen: MutableState<Boolean> = mutableStateOf(false)

    // Whether the panel was opened by a user action and has not been built since: only such a panel starts a stopped session.
    private var mcpStartOnOpen: Boolean = false

    /**
     * Opens the MCP panel on a user action (the header's button, `/mcp`, a notice's link): the panel built for it starts the session
     * if it has no process ([takeMcpStartOnOpen]). Nothing if it is open already.
     */
    fun openMcpPanel() {
        if (mcpPanelOpen.value) return
        mcpStartOnOpen = true
        mcpPanelOpen.value = true
    }

    /**
     * Whether the panel being built now was opened by a user action ([openMcpPanel]); read once. A panel shown again because its
     * session is selected again reads false, and starts nothing by itself (MCP spec 3.2).
     */
    fun takeMcpStartOnOpen(): Boolean = mcpStartOnOpen.also { mcpStartOnOpen = false }

    /**
     * The slash palette's selection (MCP spec 4.4): the committed draft it was made on and the row's index. It counts only while the
     * draft is that one (`paletteFor` reads a mismatch as 0), so an Enter right after a keystroke never picks by a stale index.
     */
    val paletteSelection: MutableState<Pair<String, Int>> = mutableStateOf("" to 0)

    /** The committed draft Esc closed the palette on: it stays closed until the draft changes (MCP spec 3.4, 4.4). */
    val paletteDismissedFor: MutableState<String?> = mutableStateOf(null)

    /**
     * The committed draft is now [draft]: a dismissal made on another draft ends, so the palette shows again. One made on this very
     * draft stays, so a late notice of the keystroke that an Esc already queued behind does not reopen it.
     */
    fun draftChanged(draft: String) {
        if (draft != paletteDismissedFor.value) paletteDismissedFor.value = null
    }

    /**
     * The uuids of the returned messages already put back in the composer and still listed by the session (desktop-parity spec 12.3,
     * 12.5): kept here, per session, so that neither a lagging list nor a restore cut short by a session switch puts one back twice
     * (Review Focus 6). Written by `restoreReturned` only.
     */
    val restoredUuids: MutableSet<String> = HashSet()

    /** A footer menu that `/model`, `/permissions` or `/mode` asked to open; the control opens it and clears the request. */
    val menuRequest: MutableState<FooterMenu?> = mutableStateOf(null)

    /**
     * How many of the mode, model and effort menus, the ring's popover and the `+` menu are open: each counts itself in while open
     * ([CountOpenMenu]), and the composer offers the permission mode cycle only while none is (desktop-parity spec 7, 14.1).
     */
    val openMenus: MutableIntState = mutableIntStateOf(0)

    /** The `+` menu's `Slash commands` (MCP spec 3.4): `/` in an empty draft, so the palette opens as typing it does; else nothing. */
    fun openPalette() {
        if (draft.text.isEmpty()) draft.setTextAndPlaceCursorAtEnd("/")
    }

    // The row keys, the rows and the list's height at the list's last emission, and whether the list has had an emission since it
    // last showed this session. Kept here, not in the panel, so that rows added while another session is shown count when this one
    // is shown again (spec 4.16, 7.8). Null keys until the first emission.
    private var seenKeys: HashSet<String>? = null
    private var seenRows: List<RenderedRow> = emptyList()
    private var seenHeight: Int = 0
    private var shown: Boolean = false

    /** The list stops showing this session: its next emission pins the bottom while following ([seen]). */
    fun hidden() {
        shown = false
    }

    /**
     * Records [rows], drawn in a list [height] px tall, as what the list has seen, and says what changed since the last call
     * (spec 4.16). Rows are compared by their models ([RenderedRow.row]), so a body's Markdown arriving for an opened row
     * ([RenderedRow.bodies]) changes nothing: opening a row never scrolls. [rows] must not change afterwards (the presenter's are
     * immutable).
     *
     * It runs on the EDT for every emission, so it does no more than the rows that changed need: the presenter hands back the rows
     * before a streamed delta as the same models, and only the rows after the first one that is not the same are compared, counted
     * and have their keys recorded again.
     */
    fun seen(rows: List<RenderedRow>, height: Int): ListChange {
        val before = seenRows
        if (rows === before && height == seenHeight && shown) return ListChange(pin = false, appended = null)
        val common = minOf(rows.size, before.size)
        var same = 0
        while (same < common && rows[same].row === before[same].row) same++
        val rowsChanged = rows.size != before.size || (same until rows.size).any { rows[it].row != before[it].row }
        val keys = seenKeys
        // Counted by key, so that grouping (a lone call joining a group shrinks the list) never miscounts. The rows before `same`
        // are the same models, so their keys were there already.
        val added = if (keys == null) 0 else (same until rows.size).count { rows[it].row.key !in keys }
        val tailChanged = rows.lastOrNull()?.row != before.lastOrNull()?.row
        val pin = !shown || rowsChanged || height != seenHeight
        seenKeys = (keys ?: HashSet(rows.size)).apply {
            // Keys are unique within a list, so those of the rows that stayed are untouched.
            for (i in same until before.size) remove(before[i].row.key)
            for (i in same until rows.size) add(rows[i].row.key)
        }
        seenRows = rows
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

/**
 * An image attached to the prompt being typed (polish spec 2.7): [id] is the composer's own, [name] the file's (or `Pasted image.png`),
 * [mediaType] from its first bytes, [bytes] its raw bytes, encoded only when the prompt is sent, and [thumbnail] its decoded thumbnail,
 * null when it could not be decoded.
 */
internal class PendingImage(val id: String, val name: String, val mediaType: String, val bytes: ByteArray, val thumbnail: ImageBitmap?)
