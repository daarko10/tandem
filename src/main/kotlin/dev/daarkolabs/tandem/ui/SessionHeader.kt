package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.ComposerModel
import dev.daarkolabs.tandem.chat.mcpBadge
import dev.daarkolabs.tandem.chat.mcpTooltip
import dev.daarkolabs.tandem.session.McpState
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.InlineWarningBanner
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The top of the chat: the button that shows or hides the session sidebar, a "+" that starts a new session (the same popup as the
 * sidebar's "New session", offering [newSession]), the selected session's title and its status line, read from the session's
 * [composer] model so that a streamed delta never recomposes the header.
 *
 * A double-click on the title, or [renaming] (the sidebar menu's Rename), swaps the title for a text field that starts with all of
 * it selected: Enter renames the session to what it holds ([onRename]) unless that is blank, Escape leaves it as it was. Either
 * way [onRenamingChange] says when the field opens and closes. With no [handle] (nothing selected) the header holds the buttons only.
 *
 * [toggleFocus] lets the tool window give the toggle the focus back when the sidebar it opened over the chat closes, and
 * [newSessionFocus] give the "+" the focus.
 *
 * With the session's MCP servers ([mcp], null with no session) the row ends with the `MCP servers` button, which toggles the
 * session's MCP panel ([onToggleMcp]), and its badge, `connected/total` (MCP spec 3.1), drawn only while the session's [status] says a
 * process runs. The button is the header's last focusable; [mcpFocus] lets the tool window give it the focus.
 */
@Composable
internal fun SessionHeader(
    handle: SessionHandle?,
    composer: StateFlow<ComposerModel>?,
    sidebarShown: Boolean,
    onToggleSidebar: () -> Unit,
    newSession: NewSessionChoices,
    onCreateSession: (NewSessionOptions) -> Unit,
    renaming: Boolean,
    onRenamingChange: (Boolean) -> Unit,
    onRename: (title: String) -> Unit,
    toggleFocus: FocusRequester,
    newSessionFocus: FocusRequester = remember { FocusRequester() },
    mcp: StateFlow<McpState>? = null,
    status: StateFlow<SessionStatus>? = null,
    onToggleMcp: () -> Unit = {},
    mcpFocus: FocusRequester = remember { FocusRequester() },
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WithTooltip("Toggle sidebar") {
                IconButton(onClick = onToggleSidebar, modifier = Modifier.focusRequester(toggleFocus)) {
                    val description = if (sidebarShown) "Hide sessions" else "Show sessions"
                    Icon(AllIconsKeys.General.LayoutEditorPreview, contentDescription = description)
                }
            }
            NewSessionLauncher(newSession, onCreateSession) { open ->
                WithTooltip("New session") {
                    IconButton(onClick = open, modifier = Modifier.focusRequester(newSessionFocus)) { Icon(AllIconsKeys.General.Add, contentDescription = "New session") }
                }
            }
            if (handle != null) {
                // Per session, so that a title field open for one session never carries its text over to another.
                key(handle.id) {
                    val title by handle.title.collectAsState()
                    if (renaming) {
                        TitleField(
                            title,
                            onConfirm = { name ->
                                onRename(name)
                                onRenamingChange(false)
                            },
                            onCancel = { onRenamingChange(false) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        SessionTitle(title, onDoubleClick = { onRenamingChange(true) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (mcp != null) McpButton(mcp, status, onToggleMcp, mcpFocus)
        }
        if (composer != null) {
            val model by composer.collectAsState()
            StatusLine(model)
        }
    }
}

/**
 * The `MCP servers` button and its badge, `connected/total` in the footer size, with the tooltip `MCP servers: C of T connected`
 * (MCP spec 3.1). Before the session lists any server the badge is not drawn and the tooltip reads `MCP servers`; while no process
 * runs ([status] `STOPPED`) it is not drawn either, since its servers are then an earlier process's, and the tooltip says the
 * session is not running. It recomposes only when the counts change.
 */
@Composable
private fun McpButton(mcp: StateFlow<McpState>, status: StateFlow<SessionStatus>?, onToggle: () -> Unit, focus: FocusRequester) {
    val live: () -> Boolean = { status?.value != SessionStatus.STOPPED }
    val counts by remember(mcp, status) {
        val liveFlow = status?.map { it != SessionStatus.STOPPED } ?: flowOf(true)
        combine(mcp, liveFlow) { state, running -> McpCounts(state, running) }.distinctUntilChanged()
    }.collectAsState(McpCounts(mcp.value, live()))
    WithTooltip(counts.tooltip) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = onToggle, modifier = Modifier.focusRequester(focus)) {
                Icon(AllIconsKeys.Webreferences.Server, contentDescription = "MCP servers")
            }
            counts.badge?.let { Text(it, style = smallTextStyle(), color = JewelTheme.globalColors.text.info) }
        }
    }
}

/** What the header shows of a session's MCP servers: the badge ([mcpBadge], null when there is none) and the tooltip ([mcpTooltip]). */
private data class McpCounts(val badge: String?, val tooltip: String) {
    constructor(state: McpState, live: Boolean) : this(mcpBadge(state, live), mcpTooltip(state, live))
}

/**
 * The title, plain text on one line. To assistive technology it is the heading of the chat, read out from its description rather
 * than as text of its own: it repeats the sidebar row's title, and for a new session the first prompt, which the transcript shows.
 */
@Composable
private fun SessionTitle(title: String, onDoubleClick: () -> Unit, modifier: Modifier) {
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)
    Text(
        title,
        modifier = modifier
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentOnDoubleClick() }) }
            .clearAndSetSemantics {
                heading()
                contentDescription = title
            },
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The title being edited, focused, all of it selected to start with. Enter confirms a title that is not blank; Escape cancels. */
@Composable
private fun TitleField(title: String, onConfirm: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val state = rememberTextFieldState(title, TextRange(0, title.length))
    val focus = remember { FocusRequester() }
    TextField(
        state,
        modifier.focusRequester(focus).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Enter, Key.NumPadEnter -> {
                    val name = state.text.toString().trim()
                    if (name.isNotEmpty()) onConfirm(name)
                    true
                }
                Key.Escape -> {
                    onCancel()
                    true
                }
                else -> false
            }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * The CLI's version, `claude <version>` (spec 4.1): the pickers below the prompt show the model and the permission mode, and the
 * chat's working row whether a turn is in progress. Above it, while the CLI does not run the model that was picked, a warning
 * saying so.
 */
@Composable
private fun StatusLine(model: ComposerModel) {
    // The theme's warning banner (spec 4.2: theme tokens only, no literal colours).
    model.modelWarning?.let { InlineWarningBanner(text = it, modifier = Modifier.fillMaxWidth()) }
    model.claudeCodeVersion?.let { version ->
        Text("claude $version", color = JewelTheme.globalColors.text.info, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}
