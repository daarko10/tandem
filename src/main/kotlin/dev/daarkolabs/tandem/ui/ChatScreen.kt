package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.PendingPermission
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionManager
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

/**
 * One session's chat: its transcript ([rows], rendered by [ChatRowsPresenter]), the permission requests it waits on, and the prompt
 * box. Everything goes to [manager] for [handle]'s session; none of it waits: a prompt to a stopped session starts its process.
 * With [focusPrompt] the prompt box takes the focus, and then [onPromptFocused] is called.
 */
@Composable
internal fun ChatPane(
    project: Project,
    handle: SessionHandle,
    manager: SessionManager,
    rows: StateFlow<List<ChatRow>>,
    focusPrompt: Boolean = false,
    onPromptFocused: () -> Unit = {},
) {
    val state by handle.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ChatPanel(project, rows, emptyText = if (state.ended) "Claude Code is not running." else "Ask Claude anything about this project.")
        }
        state.pendingPermissions.forEach { PermissionPrompt(it, handle, manager) }
        PromptBox(handle, manager, state, focusPrompt, onPromptFocused)
    }
}

@Composable
private fun PermissionPrompt(permission: PendingPermission, handle: SessionHandle, manager: SessionManager) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(JewelTheme.globalColors.borders.disabled).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Allow ${permission.toolName}?", fontWeight = FontWeight.Bold)
        permission.description?.let { Text(it) }
        Text(permission.input.toString().take(300), color = JewelTheme.globalColors.text.info)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = { manager.allow(handle.id, permission.requestId, always = false) }) { Text("Allow once") }
            OutlinedButton(onClick = { manager.allow(handle.id, permission.requestId, always = true) }) { Text("Allow for this session") }
            OutlinedButton(onClick = { manager.deny(handle.id, permission.requestId) }) { Text("Deny") }
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
