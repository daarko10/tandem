package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.PopupPositionProvider
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.ui.JBUI
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionManager
import dev.daarkolabs.tandem.session.SessionStatus
import dev.daarkolabs.tandem.session.transcript.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Badge
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.focusOutline
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.simpleListItemStyle
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.swing.SwingUtilities

private val LOG = fileLogger()

/** How often the rows' relative times are worked out again. */
private const val CLOCK_TICK_MILLIS: Long = 60_000

/** How long Home or End waits for the list to lay out the row it scrolled to. */
private const val SCROLL_WAIT_MILLIS: Long = 1_000

/** The status of a session that is not open: no process. */
private val NOT_OPEN: StateFlow<SessionStatus> = MutableStateFlow(SessionStatus.STOPPED)

/** The attention of a session that is not open: none. */
private val NO_ATTENTION: StateFlow<Boolean> = MutableStateFlow(false)

/**
 * The session list: a "New session" button, which opens [NewSessionPopup] offering [newSession], a filter by title, and a row
 * for every session of the project, newest first ([SessionManager.summaries]), whether it is open or only has a transcript. A row
 * shows the title (plain text), how long ago the session was last written, a status dot and, when the session asks for the user (it
 * waits for a permission or finished a turn while another one was selected), a "needs you" badge.
 *
 * A left click, or Enter or Space on a focused row, shows the session ([SessionActions.show]) and then [onSessionShown] is told, as it
 * is of a session created here. The row's menu (Rename, Fork, Delete…, Copy resume command and, for an open session, Close) opens
 * with a right click, the "⋯" a hovered or focused row shows, or the IDE's context menu key while the row or its "⋯" has the focus
 * ([RowMenus]: the row is scrolled into view first). Rename goes to the header's title field through [onRename]; Rename and Fork then call [onPicked].
 * Delete… asks in the row itself, as the Delete key does; the "x" on a hovered open row closes it.
 *
 * Rows take the focus with Tab, like the filter and the buttons; Up and Down move it between rows, Home and End to the first and the
 * last.
 *
 * The list is read again from disk when the sidebar appears, so sessions started elsewhere show up. With [focusFilter] the filter
 * takes the focus when the sidebar appears, as it does when the sidebar opens over the chat. [onFilterFocused] is told whenever the
 * filter takes the focus. [rowMenus] lets the tool window open the focused row's menu, and [newSessionFocus] focus "New session".
 */
@Composable
internal fun SessionSidebar(
    manager: SessionManager,
    selectedId: SessionId?,
    actions: SessionActions,
    newSession: NewSessionChoices,
    onRename: (SessionId) -> Unit,
    modifier: Modifier = Modifier,
    onPicked: () -> Unit = {},
    onSessionShown: (SessionId) -> Unit = {},
    focusFilter: Boolean = false,
    onFilterFocused: () -> Unit = {},
    rowMenus: RowMenus = remember { RowMenus() },
    newSessionFocus: FocusRequester? = null,
) {
    val summaries by manager.summaries.collectAsState()
    val open by manager.sessions.collectAsState()
    val handles = remember(open) { open.associateBy { it.id } }
    val forkOf = remember(open) { open.associate { it.id to it.forkOf } }
    val written = remember(summaries) { summaries.filter { it.file != null }.mapTo(HashSet()) { it.id } }
    val filter = rememberTextFieldState()
    val filterFocus = remember { FocusRequester() }
    val query = filter.text.toString()
    val rows = remember(summaries, query) { summaries.filteredBy(query) }
    val listState = rememberLazyListState()
    // The rows of the last composition, for what runs after it: a scroll, a delete.
    val currentRows by rememberUpdatedState(rows)
    val rowFocus = remember { HashMap<SessionId, FocusRequester>() }
    // The row whose menu the context menu key asked for, once it is in view.
    var menuFor by remember { mutableStateOf<SessionId?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(CLOCK_TICK_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    LaunchedEffect(manager) { refreshLogged(manager::refreshIndex) }
    LaunchedEffect(focusFilter) { if (focusFilter) filterFocus.requestFocus() }
    LaunchedEffect(summaries) { rowFocus.keys.retainAll(summaries.mapTo(HashSet()) { it.id }) }

    /** Moves the focus to row [index], scrolled into view first if it is out of view: such a row has no focus target yet. */
    fun focusRow(index: Int) {
        val id = currentRows.getOrNull(index)?.id ?: return
        scope.launch {
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == id }) listState.scrollToItem(index)
            val laidOut = withTimeoutOrNull(SCROLL_WAIT_MILLIS) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == id } }.first { it }
            }
            if (laidOut != null) rowFocus[id]?.requestFocus()
        }
    }

    /** Opens the menu of row [id], scrolled into view first if any of it is out of view. */
    fun openMenu(id: SessionId) {
        val index = currentRows.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        scope.launch {
            // Scrolled to, a row is at the top of the view: in view enough for its menu, even when it is taller than the view.
            if (!listState.fullyVisible(id)) listState.scrollToItem(index)
            val inView = withTimeoutOrNull(SCROLL_WAIT_MILLIS) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == id } }.first { it }
            }
            if (inView != null) menuFor = id
        }
    }

    /**
     * Deletes [summary]'s session, in the sidebar's scope so that it goes on when the row leaves. Then a row that [hadFocus] hands
     * it on: to the next row, else the previous one ([rowFocusedAfterDelete]), else the filter. [onFailed] if it could not be.
     */
    fun delete(summary: SessionSummary, hadFocus: Boolean, onFailed: () -> Unit) {
        val next = rowFocusedAfterDelete(currentRows.map { it.id }, summary.id)
        scope.launch {
            if (!actions.delete(summary.id, summary.title)) {
                onFailed()
                return@launch
            }
            if (!hadFocus) return@launch
            val index = next?.let { id -> currentRows.indexOfFirst { it.id == id } } ?: -1
            if (index >= 0) focusRow(index) else filterFocus.requestFocus()
        }
    }
    // The rows of the last composition, not those of the first.
    val currentOpenMenu by rememberUpdatedState<(SessionId) -> Unit>(::openMenu)
    DisposableEffect(rowMenus) {
        rowMenus.open = { id -> currentOpenMenu(id) }
        onDispose {
            rowMenus.open = null
            rowMenus.focused = null
        }
    }

    /** Moves the focus from row [from] as [key] asks: to the next or the previous row, the first or the last; never out of the list. */
    fun moveFocus(from: Int, key: RowKey) {
        when (key) {
            RowKey.NEXT -> if (from < rows.lastIndex) focusManager.moveFocus(FocusDirection.Down)
            RowKey.PREVIOUS -> if (from > 0) focusManager.moveFocus(FocusDirection.Up)
            RowKey.FIRST -> focusRow(0)
            RowKey.LAST -> focusRow(rows.lastIndex)
            else -> Unit
        }
    }

    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NewSessionLauncher(newSession, onCreate = { options -> onSessionShown(actions.create(options)) }) { open ->
            val focus = newSessionFocus?.let { Modifier.focusRequester(it) } ?: Modifier
            DefaultButton(onClick = open, modifier = Modifier.fillMaxWidth().then(focus)) { Text("New session") }
        }
        TextField(
            filter,
            Modifier.fillMaxWidth()
                .focusRequester(filterFocus)
                .onFocusChanged { if (it.hasFocus) onFilterFocused() }
                .semantics { contentDescription = "Filter sessions" },
            placeholder = { Text("Filter") },
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            itemsIndexed(rows, key = { _, summary -> summary.id }) { index, summary ->
                SessionRow(
                    summary,
                    handles[summary.id],
                    deleteWarning = forkWarning(forksStartingFrom(summary.id, forkOf) { it in written }.size),
                    isSelected = summary.id == selectedId,
                    now = now,
                    actions = actions,
                    focusRequester = rowFocus.getOrPut(summary.id) { FocusRequester() },
                    onShow = {
                        actions.show(summary.id)
                        onSessionShown(summary.id)
                    },
                    onRename = {
                        onRename(summary.id)
                        onPicked()
                    },
                    onFork = {
                        actions.fork(summary.id)
                        onPicked()
                    },
                    onMoveFocus = { key -> moveFocus(index, key) },
                    onFocusWithin = { within ->
                        if (within) rowMenus.focused = summary.id else if (rowMenus.focused == summary.id) rowMenus.focused = null
                    },
                    menuRequested = menuFor == summary.id,
                    onMenuRequestTaken = { menuFor = null },
                    onDelete = { hadFocus, onFailed -> delete(summary, hadFocus, onFailed) },
                )
            }
        }
    }
}

/**
 * Runs [refresh], which reads the session list again from disk. A failure, such as a bug in reading a transcript, is logged and the
 * list stays as it was: thrown in a [LaunchedEffect], it would take the whole tool window's composition down. A cancellation, as when
 * the sidebar leaves the screen, goes through.
 */
internal suspend fun refreshLogged(refresh: suspend () -> Unit) {
    try {
        refresh()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The class only: a message can quote a transcript.
        LOG.warn("session list not read again (${e.javaClass.name})")
    }
}

/** The sessions whose title contains [query], ignoring case and the blanks around it; all of them for a blank [query]. */
internal fun List<SessionSummary>.filteredBy(query: String): List<SessionSummary> {
    val wanted = query.trim()
    return if (wanted.isEmpty()) this else filter { it.title.contains(wanted, ignoreCase = true) }
}

/**
 * How long before [now] [then] was, as a row shows it: "just now" under a minute (or for a time after [now]), "3 min ago" under an
 * hour, "2 h ago" earlier the same day, "yesterday" on the day before, else the date, short, in [locale]. Days are those of [zone].
 */
internal fun relativeTime(then: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val minutes = (now - then) / 60_000
    if (minutes < 1) return "just now"
    if (minutes < 60) return "$minutes min ago"
    val day = Instant.ofEpochMilli(then).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> "${minutes / 60} h ago"
        today.minusDays(1) -> "yesterday"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(day)
    }
}

/** The row of [rows] that takes the focus once row [deleted] is gone: the next one, else the previous one; null for none. */
internal fun rowFocusedAfterDelete(rows: List<SessionId>, deleted: SessionId): SessionId? {
    val index = rows.indexOf(deleted).takeIf { it >= 0 } ?: return null
    return rows.getOrNull(index + 1) ?: rows.getOrNull(index - 1)
}

/** A row: what it shows normally, while it asks whether to delete the session, and while the session is being deleted. */
internal enum class RowMode { NORMAL, CONFIRMING_DELETE, DELETING }

/** What a key does to a focused row. */
internal enum class RowKey { SHOW, NEXT, PREVIOUS, FIRST, LAST, DELETE, CANCEL_DELETE }

/**
 * What a key going down ([type]) asks of a focused row in [mode]: Enter or Space shows its session, Down and Up move the focus to the
 * next and the previous row, Home and End to the first and the last, Delete asks whether to delete it, and Escape takes the question
 * back. On macOS ([mac]) Backspace, which the Mac's "delete" key sends, alone or with Command, asks too, as in Finder. Null for
 * anything else, so that the key goes on to the row's parents and the IDE: the context menu key, for one, is the IDE's.
 */
internal fun rowKey(key: Key, type: KeyEventType, mode: RowMode, mac: Boolean = SystemInfo.isMac): RowKey? {
    if (type != KeyEventType.KeyDown) return null
    return when (mode) {
        RowMode.NORMAL -> when (key) {
            Key.Enter, Key.NumPadEnter, Key.Spacebar -> RowKey.SHOW
            Key.DirectionDown -> RowKey.NEXT
            Key.DirectionUp -> RowKey.PREVIOUS
            Key.MoveHome -> RowKey.FIRST
            Key.MoveEnd -> RowKey.LAST
            Key.Delete -> RowKey.DELETE
            Key.Backspace -> if (mac) RowKey.DELETE else null
            else -> null
        }
        RowMode.CONFIRMING_DELETE -> if (key == Key.Escape) RowKey.CANCEL_DELETE else null
        RowMode.DELETING -> null
    }
}

/**
 * Whether a key going down ([type]) on a focused "⋯" opens its menu: Enter, the keypad's Enter or Space. On the key going down, as
 * the rows act on their keys, and not on its release as a button does: Enter's release does not reach the tool window's panel.
 */
internal fun opensMenu(key: Key, type: KeyEventType): Boolean =
    type == KeyEventType.KeyDown && (key == Key.Enter || key == Key.NumPadEnter || key == Key.Spacebar)

/** Where a row and its "⋯" are, for the row's menu. Set when they are placed and read when a menu opens, both on the EDT. */
private class RowPlaces {
    var row: LayoutCoordinates? = null
    var more: LayoutCoordinates? = null

    /** Just under the start of the "⋯", in the row's coordinates. */
    fun underMore(): Offset {
        val row = row?.takeIf { it.isAttached } ?: return Offset.Zero
        val more = more?.takeIf { it.isAttached } ?: return Offset.Zero
        return row.localPositionOf(more, Offset(0f, more.size.height.toFloat()))
    }
}

/**
 * One session's row (see [SessionSidebar]), a tab to assistive technology and the UI tests: everything in it reads as one, but for
 * its buttons, and it can be activated (its click shows the session). [handle] is the session's while it is open. The row takes the
 * focus with Tab ([focusRequester] for Home and End) and then shows a focus ring and its "⋯", which is the next Tab stop.
 */
@Composable
private fun SessionRow(
    summary: SessionSummary,
    handle: SessionHandle?,
    deleteWarning: String?,
    isSelected: Boolean,
    now: Long,
    actions: SessionActions,
    focusRequester: FocusRequester,
    onShow: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onMoveFocus: (RowKey) -> Unit,
    onFocusWithin: (Boolean) -> Unit,
    onDelete: (hadFocus: Boolean, onFailed: () -> Unit) -> Unit,
    menuRequested: Boolean,
    onMenuRequestTaken: () -> Unit,
) {
    val status by (handle?.status ?: NOT_OPEN).collectAsState()
    val attention by (handle?.attention ?: NO_ATTENTION).collectAsState()
    var mode by remember { mutableStateOf(RowMode.NORMAL) }
    // Whether the question takes the focus when it shows: asked from the menu, whose "⋯" goes away with the question.
    var focusQuestion by remember { mutableStateOf(false) }
    // Whether the row had the focus when its menu opened.
    var menuFromFocus by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var focusWithin by remember { mutableStateOf(false) }
    val places = remember { RowPlaces() }

    /** Opens the row's menu at [position], in the row's coordinates. */
    fun openMenu(position: Offset) {
        menuFromFocus = focusWithin
        menuAt = position
    }

    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val currentShow by rememberUpdatedState(onShow)
    val currentMode by rememberUpdatedState(mode)
    val listStyle = JewelTheme.simpleListItemStyle
    LaunchedEffect(menuRequested) {
        if (!menuRequested) return@LaunchedEffect
        if (mode == RowMode.NORMAL) openMenu(places.underMore())
        onMenuRequestTaken()
    }

    /** Takes the question back; the row gets back the focus that its buttons had. */
    fun cancelDelete() {
        if (focusWithin) focusRequester.requestFocus()
        mode = RowMode.NORMAL
    }

    val shape = RoundedCornerShape(listStyle.metrics.selectionBackgroundCornerSize)
    val background = when {
        isSelected -> listStyle.colors.backgroundSelected
        hovered -> listStyle.colors.backgroundActive
        else -> Color.Unspecified
    }

    Box {
        Row(
            Modifier.fillMaxWidth()
                .onGloballyPositioned { places.row = it }
                .background(background, shape)
                .focusOutline(focused, shape)
                .hoverable(interactions)
                .pointerInput(Unit) {
                    detectRowClicks(
                        onSelect = { if (currentMode == RowMode.NORMAL) currentShow() },
                        onMenu = { position -> if (currentMode == RowMode.NORMAL) openMenu(position) },
                    )
                }
                .onFocusChanged {
                    focusWithin = it.hasFocus
                    onFocusWithin(it.hasFocus)
                }
                // Before focusable(), so that it gets the keys while the row has the focus.
                .onKeyEvent { event ->
                    when (val rowKey = rowKey(event.key, event.type, mode)) {
                        RowKey.SHOW -> onShow()
                        RowKey.NEXT, RowKey.PREVIOUS, RowKey.FIRST, RowKey.LAST -> onMoveFocus(rowKey)
                        RowKey.DELETE -> {
                            focusQuestion = false
                            mode = RowMode.CONFIRMING_DELETE
                        }
                        RowKey.CANCEL_DELETE -> cancelDelete()
                        null -> return@onKeyEvent false
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(interactionSource = interactions)
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    selected = isSelected
                    onClick(label = "Show session") {
                        currentShow()
                        true
                    }
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(summary.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (mode) {
                    RowMode.NORMAL -> Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(relativeTime(summary.lastModified, now), color = JewelTheme.globalColors.text.info, maxLines = 1)
                        if (attention) Badge { Text("needs you") }
                    }
                    RowMode.CONFIRMING_DELETE -> DeleteConfirmation(
                        deleteWarning,
                        focusCancel = focusQuestion,
                        onDelete = {
                            // The buttons go away: the row keeps the focus they had until it goes too.
                            val hadFocus = focusWithin
                            if (hadFocus) focusRequester.requestFocus()
                            mode = RowMode.DELETING
                            onDelete(hadFocus) { mode = RowMode.NORMAL }
                        },
                        onCancel = ::cancelDelete,
                    )
                    RowMode.DELETING -> Text("Deleting…", color = JewelTheme.globalColors.text.info)
                }
            }
            // Also while its menu is open, which takes the pointer and the focus.
            if (mode == RowMode.NORMAL && (hovered || focusWithin || menuAt != null)) {
                WithTooltip("Session actions") {
                    IconButton(
                        onClick = { openMenu(places.underMore()) },
                        modifier = Modifier.onGloballyPositioned { places.more = it }.onPreviewKeyEvent { event ->
                            val opens = opensMenu(event.key, event.type)
                            if (opens) openMenu(places.underMore())
                            opens
                        },
                    ) { Icon(AllIconsKeys.Actions.MoreHorizontal, contentDescription = "Session actions") }
                }
            }
            if (handle != null && hovered && mode == RowMode.NORMAL) {
                WithTooltip("Close session") {
                    IconButton(onClick = { actions.close(summary.id) }) {
                        Icon(AllIconsKeys.Actions.Close, contentDescription = "Close session")
                    }
                }
            }
        }
        menuAt?.let { position ->
            // Jewel's own ContextMenu is internal; this is what it does: a menu at the pointer.
            PopupMenu(
                onDismissRequest = {
                    menuAt = null
                    true
                },
                popupPositionProvider = remember(position) { AtPointer(position.round()) },
            ) {
                fun item(text: String, enabled: Boolean = true, action: () -> Unit) {
                    selectableItem(
                        selected = false,
                        onClick = {
                            menuAt = null
                            action()
                        },
                        enabled = enabled,
                    ) { Text(text) }
                }
                item("Rename", action = onRename)
                item("Fork", enabled = summary.file != null, action = onFork) // nothing to fork before the first prompt
                item("Delete…") {
                    // The "⋯" goes away with the question: Cancel takes the focus it had. A row whose menu opened while the focus was
                    // elsewhere, as on a right click while typing a prompt, leaves the focus there.
                    focusQuestion = menuFromFocus
                    mode = RowMode.CONFIRMING_DELETE
                }
                item("Copy resume command") { actions.copyResumeCommand(summary.id) }
                if (handle != null) item("Close") { actions.close(summary.id) }
            }
        }
    }
}

/**
 * The open sessions, by id, of those in [forkOf] (each mapped to the session it forks, if any) that fork session [id] and have no
 * transcript of their own yet ([hasTranscript]): such a fork starts from [id]'s transcript, and cannot start again once it is deleted.
 */
internal fun forksStartingFrom(id: SessionId, forkOf: Map<SessionId, SessionId?>, hasTranscript: (SessionId) -> Boolean): List<SessionId> =
    forkOf.filter { (fork, original) -> original == id && !hasTranscript(fork) }.keys.toList()

/** What the delete question adds when [forks] open forks start from the session's transcript; null for none. */
internal fun forkWarning(forks: Int): String? = when (forks) {
    0 -> null
    1 -> "An open fork of it has no transcript of its own yet, and could not start again without this one."
    else -> "$forks open forks of it have no transcript of their own yet, and could not start again without this one."
}

/**
 * The question a row asks before it deletes its session, with [warning] ([forkWarning]) if it has one, and its two answers. With
 * [focusCancel] Cancel takes the focus when the question shows.
 */
@Composable
private fun DeleteConfirmation(warning: String?, focusCancel: Boolean, onDelete: () -> Unit, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusCancel) cancelFocus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Delete this session and its transcript?", fontWeight = FontWeight.Bold)
        if (warning != null) Text("⚠ $warning", color = JewelTheme.globalColors.text.warning)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = onDelete) { Text("Delete") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text("Cancel") }
        }
    }
}

/**
 * The session's [status] as a dot in a colour of the theme: running, waiting for permission, idle (a process and nothing to do) or
 * stopped (no process). The word is its tooltip and what assistive technology reads.
 */
@Composable
private fun StatusDot(status: SessionStatus) {
    // Read from the theme so that the dot follows a change of theme, as the IDE's own icon badges do.
    val dark = JewelTheme.isDark
    val stopped = JewelTheme.globalColors.text.disabled
    val (color, word) = remember(status, dark, stopped) {
        when (status) {
            SessionStatus.RUNNING -> JBUI.CurrentTheme.IconBadge.INFORMATION.toComposeColor() to "running"
            SessionStatus.WAITING -> JBUI.CurrentTheme.IconBadge.WARNING.toComposeColor() to "waiting for permission"
            SessionStatus.IDLE -> JBUI.CurrentTheme.IconBadge.SUCCESS.toComposeColor() to "idle"
            SessionStatus.STOPPED -> stopped to "stopped"
        }
    }
    WithTooltip(word) {
        Box(Modifier.size(8.dp).background(color, CircleShape).semantics { contentDescription = word })
    }
}

/**
 * Calls [onSelect] on a left click and [onMenu], with where the pointer is, on a right press. A press or release that a child takes,
 * such as a button's, is left to it, and so is the IDE's context click, which [interceptContextClicks] takes first.
 */
private suspend fun PointerInputScope.detectRowClicks(onSelect: () -> Unit, onMenu: (Offset) -> Unit) {
    awaitEachGesture {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull()
        if (change == null || change.isConsumed) return@awaitEachGesture
        val awt = event.nativeEvent as? MouseEvent
        val press = event.type == PointerEventType.Press
        when {
            press && (event.buttons.isSecondaryPressed || awt?.isPopupTrigger == true) -> {
                change.consume()
                onMenu(change.position)
            }
            press && event.buttons.isPrimaryPressed -> if (waitForUpOrCancellation() != null) onSelect()
        }
    }
}

/** The mouse buttons AWT reports as down. */
private const val BUTTONS_DOWN: Int = MouseEvent.BUTTON1_DOWN_MASK or MouseEvent.BUTTON2_DOWN_MASK or MouseEvent.BUTTON3_DOWN_MASK

/**
 * Whether this is the click the IDE's ShowPopupMenu action (the context menu key) sends to where it guesses the focus is: a press
 * that AWT marks as a popup trigger, with no button down.
 */
private fun MouseEvent.isContextClick(): Boolean = id == MouseEvent.MOUSE_PRESSED && isPopupTrigger && modifiersEx and BUTTONS_DOWN == 0

/**
 * Sends the release that the IDE's context click lacks, once it has been handled. Without it Compose takes the pointer for still
 * down, and sends the next press to whatever took this one.
 */
private fun MouseEvent.releaseLater() {
    val release = MouseEvent(component, MouseEvent.MOUSE_RELEASED, `when`, 0, x, y, 1, false, button)
    SwingUtilities.invokeLater { component.dispatchEvent(release) }
}

/**
 * What the IDE's context menu key does in the tool window: open the menu of the sidebar row that has the focus, or one of its
 * buttons ([focused]), through the sidebar showing it ([open]). Set and read on the EDT.
 */
internal class RowMenus {
    /** The row that has the focus, or one of its buttons; null when none has. */
    var focused: SessionId? = null

    /** Opens the menu of a row, scrolled into view first; null while no sidebar shows. */
    var open: ((SessionId) -> Unit)? = null

    /** The context menu key was pressed: the focused row's menu opens; without one, nothing happens. */
    fun contextMenuKey() {
        val id = focused ?: return
        open?.invoke(id)
    }
}

/**
 * Takes every context click the IDE's context menu key sends (ShowPopupMenu presses where it guesses the focus is, with no release)
 * before anything under it, so that it never lands on a row out of view, the dimmed chat or the middle of the panel: [onKey] is
 * called instead, and the missing release is sent. Real right clicks go through.
 */
internal suspend fun PointerInputScope.interceptContextClicks(onKey: () -> Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val awt = event.nativeEvent as? MouseEvent ?: continue
            if (!awt.isContextClick()) continue
            event.changes.forEach { it.consume() }
            awt.releaseLater()
            onKey()
        }
    }
}

/** Whether all of item [key] is in the list's view. */
private fun LazyListState.fullyVisible(key: Any): Boolean {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
    return item.offset >= layoutInfo.viewportStartOffset && item.offset + item.size <= layoutInfo.viewportEndOffset
}

/**
 * Places a popup at [position] in its anchor, the pointer's place in a row: below and right of it, or above and left where the
 * window has no room for it there.
 */
private class AtPointer(private val position: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + position.x
        val y = anchorBounds.top + position.y
        return IntOffset(
            (if (x + popupContentSize.width <= windowSize.width) x else x - popupContentSize.width).coerceAtLeast(0),
            (if (y + popupContentSize.height <= windowSize.height) y else y - popupContentSize.height).coerceAtLeast(0),
        )
    }
}
