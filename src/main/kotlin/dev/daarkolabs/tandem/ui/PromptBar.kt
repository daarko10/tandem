@file:OptIn(ExperimentalLayoutApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.ComposerModel
import dev.daarkolabs.tandem.chat.ScrollEvent
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionManager
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.util.Locale

/**
 * The bottom of a session's chat (spec 4.15): the composer, a text area in a rounded box with Send (`↵`), or Stop while a turn runs,
 * at its right, and below it the session's permission mode and model pickers ([PermissionModePicker], [ModelPicker]) and, when the
 * session has one, the effort it started with, read-only. Everything shown comes from [presenter]'s composer model, which changes
 * only when one of its own fields does, never on a streamed delta.
 *
 * The prompt being typed is [ui]'s draft, so it survives switching sessions. Enter sends it unless it is blank, and does nothing
 * while a turn runs; Shift+Enter is a newline; Esc goes to the open card ([onCardEscape]), else stops a running turn. A send makes
 * the list follow its bottom again. A pick goes to [manager], which switches a running process at once and starts a stopped
 * session with it; it becomes the project's last pick in [workspace] too, which a new session starts from. A session whose process
 * ended takes prompts too: the next one starts it again. With [focus] the prompt takes the focus, and then [onFocused] is called.
 */
@Composable
internal fun PromptBar(
    handle: SessionHandle,
    manager: SessionManager,
    workspace: TandemWorkspaceState,
    presenter: ChatPresenter,
    ui: ChatUiState,
    focus: Boolean = false,
    onFocused: () -> Unit = {},
    onCardEscape: () -> Unit = {},
) {
    val composer by presenter.composer.collectAsState()
    val scope = rememberCoroutineScope()
    val send = {
        val text = ui.draft.text.toString().trim()
        if (text.isNotEmpty()) {
            manager.send(handle.id, text)
            ui.draft.clearText()
            ui.follow.value = ui.follow.value.on(ScrollEvent.Sent)
            scope.launch {
                val last = ui.listState.layoutInfo.totalItemsCount - 1
                if (last >= 0) ui.listState.animateScrollToItem(last)
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        PromptBox(
            ui,
            composer,
            focus,
            onFocused,
            onAction = { action ->
                when (action) {
                    ComposerAction.Send -> send()
                    ComposerAction.Stop -> manager.interrupt(handle.id)
                    ComposerAction.CardEscape -> onCardEscape()
                }
            },
        )
        FlowRow(
            Modifier.chatColumn().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            PermissionModePicker(composer.permissionMode) { mode ->
                manager.setPermissionMode(handle.id, mode)
                workspace.state.lastPermissionMode = mode.wire
            }
            // Mode at the left, model and effort at the right; on a narrow pane they wrap onto a second line.
            Spacer(Modifier.weight(1f))
            ModelPicker(composer.models, composer.requestedModel, composer.activeModel) { model ->
                manager.setModel(handle.id, model)
                workspace.state.lastModel = model
            }
            composer.effort?.let { EffortBadge(it) }
        }
    }
}

/**
 * The text area and Send or Stop in one rounded box, capped to the chat column. Keys go through [composerKey]; [onAction] does
 * what it returns. With [focus] the text area takes the focus, and then [onFocused] is called.
 */
@Composable
private fun PromptBox(ui: ChatUiState, composer: ComposerModel, focus: Boolean, onFocused: () -> Unit, onAction: (ComposerAction) -> Unit) {
    val inputFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focus) {
        if (focus) {
            inputFocus.requestFocus()
            onFocused()
        }
    }
    val colors = JewelTheme.globalColors
    val shape = RoundedCornerShape(8.dp)
    Row(
        // 4 dp above, so that with the list's 12 dp bottom padding the last row sits 16 dp above the box (spec 4.1).
        Modifier.chatColumn().padding(start = 16.dp, end = 16.dp, top = 4.dp)
            .border(1.dp, if (focused) colors.borders.focused else colors.borders.normal, shape)
            // 12 dp inside the box all round (spec 4.15), counting the undecorated TextArea's own content padding (5 dp across,
            // 4 dp down): 7 + 5 at the start, 8 + 4 above and below the text, so 184 dp holds 8 lines of 20 dp.
            .padding(start = 7.dp, end = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextArea(
            ui.draft,
            Modifier.weight(1f).heightIn(min = 40.dp, max = 184.dp).padding(vertical = 8.dp)
                .focusRequester(inputFocus)
                .onFocusChanged { focused = it.hasFocus }
                .onPreviewKeyEvent { event -> composerKeyEvent(event, composer, ui.draft, onAction) },
            lineLimits = TextFieldLineLimits.MultiLine(1, 8),
            undecorated = true,
            placeholder = { Text("Describe a task or ask a question", color = colors.text.info) },
        )
        // Centred on the last line: 12 dp padding + half a 20 dp line − half the 24 dp button.
        val buttonModifier = Modifier.padding(start = 8.dp, bottom = 10.dp).size(24.dp)
        if (composer.busy) {
            IconButton(onClick = { onAction(ComposerAction.Stop) }, modifier = buttonModifier.semantics { contentDescription = "Stop" }) {
                Icon(AllIconsKeys.Actions.Suspend, contentDescription = null, tint = colors.text.info)
            }
        } else {
            IconButton(onClick = { onAction(ComposerAction.Send) }, modifier = buttonModifier.semantics { contentDescription = "Send" }) {
                Text("↵", style = JewelTheme.editorTextStyle, color = colors.text.info)
            }
        }
    }
}

/**
 * Hands [event] to [composerKey] and runs what it returns, else to [composerFallback]: a newline in [draft] ([insertNewline]),
 * which the text area would not type, or nothing at all (spec 4.15). Every other key goes to the text area.
 */
private fun composerKeyEvent(event: KeyEvent, composer: ComposerModel, draft: TextFieldState, onAction: (ComposerAction) -> Unit): Boolean {
    val ctrlOrMeta = event.isCtrlPressed || event.isMetaPressed
    val action = composerKey(
        event.key,
        event.type,
        event.isShiftPressed,
        event.isAltPressed,
        ctrlOrMeta,
        busy = composer.busy,
        cardOpen = composer.cardOpen,
    )
    if (action != null) {
        onAction(action)
        return true
    }
    return when (composerFallback(event.key, event.type, event.isShiftPressed, event.isAltPressed, ctrlOrMeta, busy = composer.busy)) {
        ComposerFallback.Newline -> {
            insertNewline(draft)
            true
        }
        ComposerFallback.Swallow -> true
        null -> false
    }
}

/** The effort the session started with, capitalised. It cannot change while the session runs, so it is shown and not picked. */
@Composable
private fun EffortBadge(effort: String) {
    WithTooltip("Effort is chosen when a session starts.") {
        Text(
            effort.replaceFirstChar { it.titlecase(Locale.ROOT) },
            color = JewelTheme.globalColors.text.info,
            style = smallTextStyle(),
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = "Effort" },
        )
    }
}
