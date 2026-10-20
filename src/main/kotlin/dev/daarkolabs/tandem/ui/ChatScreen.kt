package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.chat.PermissionCard
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionManager
import dev.daarkolabs.tandem.ui.cards.PendingCardHost
import dev.daarkolabs.tandem.ui.cards.answerFor
import dev.daarkolabs.tandem.ui.cards.escapeAnswer
import dev.daarkolabs.tandem.ui.cards.pendingCard
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
 * takes the focus only when [cardTakesFocus] says so then.
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
    cardTakesFocus: () -> Boolean = { false },
) {
    val composer by presenter.composer.collectAsState()
    val pendingFlow = remember(presenter) { presenter.chat.map { it.pendingCard() }.distinctUntilChanged() }
    val pending by pendingFlow.collectAsState(presenter.chat.value.pendingCard())
    // The plan card's feedback, for the request on screen: Esc in the composer sends it too.
    val feedback = remember(pending.card?.requestId) { TextFieldState() }
    val panel = JewelTheme.globalColors.panelBackground
    Column(Modifier.fillMaxSize()) {
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
            ChatPanel(project, presenter, ui, emptyText, handle.lastFrameAtMillis, onFocusComposer)
        }
        pending.card?.let { PendingCardHost(project, handle, manager, pending, feedback, cardTakesFocus) }
        PromptBar(
            handle,
            manager,
            workspace,
            presenter,
            ui,
            focusPrompt,
            onPromptFocused,
            onCardEscape = {
                pending.card?.let { card ->
                    answerFor(handle.state.value, card.requestId, escapeAnswer(card, feedback.text.toString()))
                        ?.let { manager.answer(handle.id, card.requestId, it) }
                }
            },
        )
    }
}
