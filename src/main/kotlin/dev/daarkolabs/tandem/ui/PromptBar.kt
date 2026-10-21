package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionManager
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

/**
 * The bottom of a session's chat: the prompt with Send, or Stop while a turn runs, and below it the session's model and permission
 * mode pickers ([ModelPicker], [PermissionModePicker]) and, when the session has one, the effort it started with, read-only.
 *
 * A pick goes to [manager], which switches a running process at once and starts a stopped session with it; it becomes the project's
 * last pick in [workspace] too, which a new session starts from. The pickers show what the session's state says: the CLI confirms a
 * permission mode with its next status or `system/init`. A session whose process ended takes prompts too: the next one starts it
 * again. With [focus] the prompt takes the focus, and then [onFocused] is called.
 */
@Composable
internal fun PromptBar(
    handle: SessionHandle,
    manager: SessionManager,
    workspace: TandemWorkspaceState,
    focus: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val state by handle.state.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        PromptBox(handle, manager, state, focus, onFocused)
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The model picker takes what the others leave, and shortens its entry when that is too little: a tool window is narrow.
            ModelPicker(state.models, state.requestedModel, state.activeModel, Modifier.weight(1f, fill = false)) { model ->
                manager.setModel(handle.id, model)
                workspace.state.lastModel = model
            }
            PermissionModePicker(state.permissionMode) { mode ->
                manager.setPermissionMode(handle.id, mode)
                workspace.state.lastPermissionMode = mode.wire
            }
            state.effort?.let { EffortBadge(it) }
        }
    }
}

/**
 * The prompt and Send, or Stop while a turn runs. A session whose process ended takes prompts too: the next one starts it again.
 * With [focus] the prompt takes the focus, and then [onFocused] is called.
 */
@Composable
private fun PromptBox(handle: SessionHandle, manager: SessionManager, state: ChatState, focus: Boolean, onFocused: () -> Unit) {
    val input = rememberTextFieldState()
    val inputFocus = remember { FocusRequester() }
    LaunchedEffect(focus) {
        if (focus) {
            inputFocus.requestFocus()
            onFocused()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextArea(input, Modifier.weight(1f).heightIn(min = 56.dp, max = 160.dp).focusRequester(inputFocus))
        if (state.busy) {
            OutlinedButton(onClick = { manager.interrupt(handle.id) }) { Text("Stop") }
        } else {
            DefaultButton(
                onClick = {
                    val text = input.text.toString().trim()
                    if (text.isNotEmpty()) {
                        manager.send(handle.id, text)
                        input.clearText()
                    }
                },
            ) { Text("Send") }
        }
    }
}

/** The effort the session started with. It cannot change while the session runs, so it is shown and not picked. */
@Composable
private fun EffortBadge(effort: String) {
    WithTooltip("Effort is chosen when a session starts.") {
        Text("effort: $effort", color = JewelTheme.globalColors.text.info, maxLines = 1)
    }
}
