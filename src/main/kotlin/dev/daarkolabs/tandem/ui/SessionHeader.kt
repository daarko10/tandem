package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.SessionHandle
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
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
        }
        if (composer != null) {
            val model by composer.collectAsState()
            StatusLine(model)
        }
    }
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
    val warning = model.modelWarning
    if (warning != null) {
        Text(
            "⚠ $warning",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().background(Color(0xFFB3261E)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    model.claudeCodeVersion?.let { version ->
        Text("claude $version", color = JewelTheme.globalColors.text.info, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}
