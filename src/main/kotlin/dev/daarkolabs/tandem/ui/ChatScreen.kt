package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.chat.PermissionCard
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionManager
import dev.daarkolabs.tandem.session.UsagePart
import dev.daarkolabs.tandem.ui.cards.PendingCardHost
import dev.daarkolabs.tandem.ui.cards.answerFor
import dev.daarkolabs.tandem.ui.cards.escapeAnswer
import dev.daarkolabs.tandem.ui.cards.pendingCard
import dev.daarkolabs.tandem.ui.rows.ReplyHost
import dev.daarkolabs.tandem.ui.rows.copyToClipboard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.jewel.foundation.modifier.thenIf
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * One session's chat: its transcript ([ChatPanel], drawn from [presenter] with what the chat remembers on screen in [ui]), the card of
 * the request it waits on ([PendingCardHost]), docked above the composer, and the prompt bar ([PromptBar]) with the session's
 * pickers, whose picks are also the project's last ones in [workspace]. Everything goes to [manager] for [handle]'s session; none of
 * it waits: a prompt to a stopped session starts its process. While a permission card is open the transcript is dimmed. With
 * [focusPrompt] the prompt box takes the focus, and then [onPromptFocused] is called. Esc in the transcript asks for the prompt box's
 * focus with [onFocusComposer]; Esc in the prompt box while a card is open gives the card's Esc answer (spec 4.18). A card that opens
 * takes the focus only when [cardTakesFocus] says so then. Under each ended turn's reply, Copy and Fork from here go through [actions].
 * Prompts, with their attached images, go out through the session's [sender]. A local `/mcp` notice's link calls [onOpenMcp], a
 * running shell escape's `Stop command` [onStop], and a usage card's `Retry` [onUsageRetry]; a waiting message's Edit and Cancel go to
 * [manager] (desktop-parity spec 12.3).
 */
@Composable
internal fun ChatPane(
    project: Project,
    handle: SessionHandle,
    manager: SessionManager,
    workspace: TandemWorkspaceState,
    presenter: ChatPresenter,
    ui: ChatUiState,
    actions: SessionActions,
    sender: ComposerSender,
    focusPrompt: Boolean = false,
    onPromptFocused: () -> Unit = {},
    onFocusComposer: () -> Unit = {},
    cardTakesFocus: () -> Boolean = { false },
    onOpenMcp: () -> Unit = {},
    onStop: () -> Unit = {},
    onUsageRetry: (uuid: String, part: UsagePart) -> Unit = { _, _ -> },
) {
    val composer by presenter.composer.collectAsState()
    val pendingFlow = remember(presenter) { presenter.chat.map { it.pendingCard() }.distinctUntilChanged() }
    val pending by pendingFlow.collectAsState(presenter.chat.value.pendingCard())
    // The plan card's feedback, for the request on screen: Esc in the composer sends it too.
    val feedback = remember(pending.card?.requestId) { TextFieldState() }
    val panel = JewelTheme.globalColors.panelBackground
    // The replies' hover rows (polish spec 2.6): Fork from here forks at the reply's own point (desktop-parity spec 13.1).
    val reply = remember(handle.id, actions) { ReplyHost(::copyToClipboard) { at -> actions.fork(handle.id, at) } }
    // The chat pane's height, which caps the context popover's (desktop-parity spec 3.1: 60 % of the tool window).
    val density = LocalDensity.current
    // Read only by the open popover (through the provider), so a resize recomposes neither the pane nor the prompt bar.
    val paneHeight = remember { mutableStateOf<Dp?>(null) }
    val paneHeightOf: () -> Dp? = remember(paneHeight) { { paneHeight.value } }
    Column(Modifier.fillMaxSize().onSizeChanged { paneHeight.value = with(density) { it.height.toDp() } }) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                // The dim of spec 4.12: a scrim drawn over the list, never an offscreen layer of it.
                .thenIf(pending.card is PermissionCard) {
                    drawWithContent {
                        drawContent()
                        drawRect(panel.copy(alpha = 0.5f))
                    }
                },
        ) {
            val emptyText = if (composer.ended) {
                "This session's claude process has stopped. Send a prompt to start it again."
            } else {
                "Ask Claude anything about this project."
            }
            ChatPanel(
                project,
                presenter,
                ui,
                emptyText,
                handle.lastFrameAtMillis,
                onFocusComposer,
                reply,
                onOpenMcp,
                onStop,
                onUsageRetry,
                onWithdraw = { uuid, restore -> manager.withdraw(handle.id, uuid, restore) },
            )
        }
        pending.card?.let { PendingCardHost(project, handle, manager, pending, feedback, cardTakesFocus) }
        PromptBar(
            project,
            handle,
            manager,
            workspace,
            presenter,
            ui,
            sender,
            focusPrompt,
            onPromptFocused,
            onCardEscape = {
                pending.card?.let { card ->
                    answerFor(handle.state.value, card.requestId, escapeAnswer(card, feedback.text.toString()))
                        ?.let { manager.answer(handle.id, card.requestId, it) }
                }
            },
            paneHeight = paneHeightOf,
        )
    }
}
