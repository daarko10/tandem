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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.ide.TandemChat
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.PendingPermission
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

/** The chat tool window: model status, transcript, open permission prompts, and the prompt box. */
@Composable
internal fun ChatScreen(project: Project, chat: TandemChat, rows: StateFlow<List<ChatRow>>) {
    val state by chat.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        StatusBar(state)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ChatPanel(project, rows, emptyText = if (state.ended) "Claude Code is not running." else "Ask Claude anything about this project.")
        }
        state.pendingPermissions.forEach { PermissionPrompt(it, chat) }
        PromptBox(chat, state)
    }
}

@Composable
private fun StatusBar(state: ChatState) {
    val warning = state.modelWarning
    if (warning != null) {
        Text(
            "⚠ $warning",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().background(Color(0xFFB3261E)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    val parts = listOfNotNull(
        state.activeModel ?: state.requestedModel ?: "default model",
        state.permissionMode?.wire?.let { "mode: $it" },
        state.claudeCodeVersion?.let { "Claude Code $it" },
        if (state.busy) "working…" else null,
    )
    Text(parts.joinToString("  ·  "), color = JewelTheme.globalColors.text.info, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
}

@Composable
private fun PermissionPrompt(permission: PendingPermission, chat: TandemChat) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(JewelTheme.globalColors.borders.disabled).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Allow ${permission.toolName}?", fontWeight = FontWeight.Bold)
        permission.description?.let { Text(it) }
        Text(permission.input.toString().take(300), color = JewelTheme.globalColors.text.info)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = { chat.allow(permission.requestId, always = false) }) { Text("Allow once") }
            OutlinedButton(onClick = { chat.allow(permission.requestId, always = true) }) { Text("Allow for this session") }
            OutlinedButton(onClick = { chat.deny(permission.requestId) }) { Text("Deny") }
        }
    }
}

@Composable
private fun PromptBox(chat: TandemChat, state: ChatState) {
    val input = rememberTextFieldState()
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextArea(input, Modifier.weight(1f).heightIn(min = 56.dp, max = 160.dp), enabled = !state.ended)
        if (state.busy) {
            OutlinedButton(onClick = chat::interrupt) { Text("Stop") }
        } else {
            DefaultButton(
                onClick = {
                    val text = input.text.toString().trim()
                    if (text.isNotEmpty()) {
                        chat.send(text)
                        input.clearText()
                    }
                },
                enabled = !state.ended,
            ) { Text("Send") }
        }
    }
}
