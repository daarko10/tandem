package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.session.PendingPermission
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * One session's chat: its transcript ([ChatPanel], drawn from [presenter] with what the chat remembers on screen in [ui]), the
 * permission requests it waits on, and the prompt bar ([PromptBar]) with the session's pickers, whose picks are also the project's
 * last ones in [workspace]. Everything goes to [manager] for [handle]'s session; none of it waits: a prompt to a stopped session
 * starts its process. With [focusPrompt] the prompt box takes the focus, and then [onPromptFocused] is called. Esc in the
 * transcript asks for the prompt box's focus with [onFocusComposer]; Esc in the prompt box while a request waits denies it.
 */
@Composable
internal fun ChatPane(
    project: Project,
    handle: SessionHandle,
    manager: SessionManager,
    workspace: TandemWorkspaceState,
    presenter: ChatPresenter,
    ui: ChatUiState,
    focusPrompt: Boolean = false,
    onPromptFocused: () -> Unit = {},
    onFocusComposer: () -> Unit = {},
) {
    val composer by presenter.composer.collectAsState()
    val pendingFlow = remember(handle) { handle.state.map { it.pendingPermissions }.distinctUntilChanged() }
    val pending by pendingFlow.collectAsState(handle.state.value.pendingPermissions)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val emptyText = if (composer.ended) {
                "This session's claude process has stopped. Send a prompt to start it again."
            } else {
                "Ask Claude anything about this project."
            }
            ChatPanel(project, presenter, ui, emptyText, handle.lastFrameAtMillis, onFocusComposer)
        }
        pending.forEach { PermissionPrompt(it, handle, manager) }
        PromptBar(
            handle,
            manager,
            workspace,
            presenter,
            ui,
            focusPrompt,
            onPromptFocused,
            // Until the permission card (slice B), Esc in the prompt box answers the first waiting request as its Deny does.
            onCardEscape = { pending.firstOrNull()?.let { manager.deny(handle.id, it.requestId) } },
        )
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
