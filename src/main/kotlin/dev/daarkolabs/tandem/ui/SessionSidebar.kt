package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.intellij.util.ui.JBUI
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionManager
import dev.daarkolabs.tandem.session.SessionStatus
import dev.daarkolabs.tandem.session.transcript.SessionSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.simpleListItemStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** How often the rows' relative times are worked out again. */
private const val CLOCK_TICK_MILLIS: Long = 60_000

/** The status of a session that is not open: no process. */
private val NOT_OPEN: StateFlow<SessionStatus> = MutableStateFlow(SessionStatus.STOPPED)

/** The attention of a session that is not open: none. */
private val NO_ATTENTION: StateFlow<Boolean> = MutableStateFlow(false)

/**
 * The session list beside the chat: a "New session" button, which opens [NewSessionPopup] with [newSessionDefaults], a filter by
 * title, and a row for every session of the project, newest first ([SessionManager.summaries]), whether it is open or only has a
 * transcript. A row shows the title (plain text), how long ago the session was last written, a status dot and, when the session
 * asks for the user (it waits for a permission or finished a turn while another one was selected), a "needs you" badge.
 *
 * A left click shows the session ([SessionActions.show]); a right click opens its menu: Rename (the header's title field, through
 * [onRename]), Fork (only once the session has a transcript), Delete… (asks in the row itself), Copy resume command and, for an
 * open session, Close, which the "x" on a hovered row does too.
 *
 * The list is read again from disk when the sidebar appears, so sessions started elsewhere show up.
 */
@Composable
internal fun SessionSidebar(
    manager: SessionManager,
    selectedId: SessionId?,
    actions: SessionActions,
    newSessionDefaults: () -> NewSessionOptions,
    onRename: (SessionId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summaries by manager.summaries.collectAsState()
    val open by manager.sessions.collectAsState()
    val handles = remember(open) { open.associateBy { it.id } }
    val filter = rememberTextFieldState()
    val query = filter.text.toString()
    val rows = remember(summaries, query) { summaries.filteredBy(query) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(CLOCK_TICK_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    LaunchedEffect(manager) { manager.refreshIndex() }

    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NewSessionButton(newSessionDefaults, actions)
        TextField(filter, Modifier.fillMaxWidth(), placeholder = { Text("Filter") })
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(rows, key = { it.id }) { summary ->
                SessionRow(
                    summary,
                    handles[summary.id],
                    isSelected = summary.id == selectedId,
                    now = now,
                    actions = actions,
                    onRename = { onRename(summary.id) },
                )
            }
        }
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

/** "New session", and below it, once pressed, the popup that creates one with [defaults] and selects it. */
@Composable
private fun NewSessionButton(defaults: () -> NewSessionOptions, actions: SessionActions) {
    var popupShown by remember { mutableStateOf(false) }
    Box {
        DefaultButton(onClick = { popupShown = true }, modifier = Modifier.fillMaxWidth()) { Text("New session") }
        if (popupShown) {
            NewSessionPopup(
                defaults(),
                onCreate = { options ->
                    popupShown = false
                    actions.create(options)
                },
                onDismiss = { popupShown = false },
            )
        }
    }
}

/** A row: what it shows normally, while it asks whether to delete the session, and while the session is being deleted. */
private enum class RowMode { NORMAL, CONFIRMING_DELETE, DELETING }

/**
 * One session's row (see [SessionSidebar]), a tab to assistive technology and the UI tests: everything in it reads as one, but for
 * its buttons. [handle] is the session's while it is open.
 */
@Composable
private fun SessionRow(
    summary: SessionSummary,
    handle: SessionHandle?,
    isSelected: Boolean,
    now: Long,
    actions: SessionActions,
    onRename: () -> Unit,
) {
    val status by (handle?.status ?: NOT_OPEN).collectAsState()
    val attention by (handle?.attention ?: NO_ATTENTION).collectAsState()
    var mode by remember { mutableStateOf(RowMode.NORMAL) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val currentShow by rememberUpdatedState { actions.show(summary.id) }
    val currentMode by rememberUpdatedState(mode)
    val listStyle = JewelTheme.simpleListItemStyle
    val background = when {
        isSelected -> listStyle.colors.backgroundSelected
        hovered -> listStyle.colors.backgroundActive
        else -> Color.Unspecified
    }

    Box {
        Row(
            Modifier.fillMaxWidth()
                .background(background, RoundedCornerShape(listStyle.metrics.selectionBackgroundCornerSize))
                .hoverable(interactions)
                .pointerInput(Unit) {
                    detectRowClicks(
                        onSelect = { if (currentMode == RowMode.NORMAL) currentShow() },
                        onMenu = { position -> if (currentMode == RowMode.NORMAL) menuAt = position },
                    )
                }
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    selected = isSelected
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
                        onDelete = {
                            mode = RowMode.DELETING
                            scope.launch { if (!actions.delete(summary.id, summary.title)) mode = RowMode.NORMAL }
                        },
                        onCancel = { mode = RowMode.NORMAL },
                    )
                    RowMode.DELETING -> Text("Deleting…", color = JewelTheme.globalColors.text.info)
                }
            }
            if (handle != null && hovered && mode == RowMode.NORMAL) {
                IconButton(onClick = { actions.close(summary.id) }) {
                    Icon(AllIconsKeys.Actions.Close, contentDescription = "Close session")
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
                item("Fork", enabled = summary.file != null) { actions.fork(summary.id) } // nothing to fork before the first prompt
                item("Delete…") { mode = RowMode.CONFIRMING_DELETE }
                item("Copy resume command") { actions.copyResumeCommand(summary.id) }
                if (handle != null) item("Close") { actions.close(summary.id) }
            }
        }
    }
}

/** The question a row asks before it deletes its session, with its two answers. */
@Composable
private fun DeleteConfirmation(onDelete: () -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Delete this session and its transcript?", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = onDelete) { Text("Delete") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/**
 * The session's [status] as a dot in a colour of the theme: running, waiting for a permission, idle (a process and nothing to do)
 * or stopped (no process). Assistive technology reads the status.
 */
@Composable
private fun StatusDot(status: SessionStatus) {
    // Read from the theme so that the dot follows a change of theme, as the IDE's own icon badges do.
    val dark = JewelTheme.isDark
    val stopped = JewelTheme.globalColors.text.disabled
    val (color, description) = remember(status, dark, stopped) {
        when (status) {
            SessionStatus.RUNNING -> JBUI.CurrentTheme.IconBadge.INFORMATION.toComposeColor() to "running"
            SessionStatus.WAITING -> JBUI.CurrentTheme.IconBadge.WARNING.toComposeColor() to "waiting for permission"
            SessionStatus.IDLE -> JBUI.CurrentTheme.IconBadge.SUCCESS.toComposeColor() to "idle"
            SessionStatus.STOPPED -> stopped to "stopped"
        }
    }
    Box(Modifier.size(8.dp).background(color, CircleShape).semantics { contentDescription = description })
}

/**
 * Calls [onSelect] on a left click and [onMenu], with where the pointer is, on a right press. A press or release that a child
 * takes, such as the close button's, is left to it.
 */
private suspend fun PointerInputScope.detectRowClicks(onSelect: () -> Unit, onMenu: (Offset) -> Unit) {
    awaitEachGesture {
        val press = awaitPointerEvent()
        val change = press.changes.firstOrNull()
        if (press.type != PointerEventType.Press || change == null || change.isConsumed) return@awaitEachGesture
        when {
            press.buttons.isSecondaryPressed -> {
                change.consume()
                onMenu(change.position)
            }
            press.buttons.isPrimaryPressed -> if (waitForUpOrCancellation() != null) onSelect()
        }
    }
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
