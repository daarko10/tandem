@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui.cards

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.chat.Payload
import dev.daarkolabs.tandem.chat.PendingCard
import dev.daarkolabs.tandem.chat.PermissionCard
import dev.daarkolabs.tandem.chat.PlanCard
import dev.daarkolabs.tandem.chat.QuestionCard
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.PendingPermission
import dev.daarkolabs.tandem.session.PermissionAnswer
import dev.daarkolabs.tandem.session.PermissionAnswers
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionManager
import dev.daarkolabs.tandem.ui.RenderedChat
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.MarkdownBlock

/** What the docked card shows: the oldest pending request's [card], how many more wait, and a plan card's plan, parsed. */
internal data class PendingCardModel(val card: PendingCard?, val moreWaiting: Int, val planBlocks: ImmutableList<MarkdownBlock>?)

/** This chat's [PendingCardModel]; it changes only when the card does, not on every streamed delta. */
internal fun RenderedChat.pendingCard(): PendingCardModel = PendingCardModel(card, moreWaiting, planBlocks)

/**
 * The card of the request [model] holds, docked above the composer (spec 4.12–4.14), answered through [manager] for [handle]'s
 * session. A click reads the request from the session's state as it is then ([answerFor]): one that is no longer pending (answered,
 * cancelled, its turn ended) is not answered. [feedback] is the plan card's feedback, which Esc in the composer sends too.
 */
@Composable
internal fun PendingCardHost(project: Project, handle: SessionHandle, manager: SessionManager, model: PendingCardModel, feedback: TextFieldState) {
    val card = model.card ?: return
    val answer: ((PendingPermission) -> PermissionAnswer) -> Unit = { build ->
        answerFor(handle.state.value, card.requestId, build)?.let { manager.answer(handle.id, card.requestId, it) }
    }
    when (card) {
        is PermissionCard -> PermissionCardView(
            card,
            model.moreWaiting,
            onAllowOnce = { answer(PermissionAnswers::allowOnce) },
            onAllowForSession = { answer(PermissionAnswers::allowForSession) },
            onDeny = { answer { PermissionAnswers.deny() } },
        )
        // Until the question and plan cards: the generic card, whose Deny and Esc send what Esc in the composer sends.
        else -> PermissionCardView(
            genericCard(card),
            model.moreWaiting,
            onAllowOnce = { answer(PermissionAnswers::allowOnce) },
            onAllowForSession = {},
            onDeny = { answer(escapeAnswer(card, feedback.text.toString())) },
        )
    }
}

/** [card] as the generic permission card `Allow Claude to Use <tool>?`, for a card without a view of its own. */
internal fun genericCard(card: PendingCard): PermissionCard = when (card) {
    is PermissionCard -> card
    is QuestionCard -> useTool(card, "AskUserQuestion")
    is PlanCard -> useTool(card, "ExitPlanMode")
}

private fun useTool(card: PendingCard, tool: String): PermissionCard =
    PermissionCard(card.requestId, card.toolUseId, card.agentDescription, "Use", tool, null, Payload.None, canAlwaysAllow = false)

/** The answer [build] makes of the pending request [requestId] in [state]; null when no such request is pending any more. */
internal fun answerFor(state: ChatState, requestId: String, build: (PendingPermission) -> PermissionAnswer): PermissionAnswer? =
    state.pendingPermissions.firstOrNull { it.requestId == requestId }?.let(build)

/**
 * The answer Esc gives [card] (spec 4.18): Deny for a permission, Cancel for a question, Keep planning with the typed [feedback]
 * for a plan.
 */
internal fun escapeAnswer(card: PendingCard, feedback: String): (PendingPermission) -> PermissionAnswer = when (card) {
    is PermissionCard -> { _ -> PermissionAnswers.deny() }
    is QuestionCard -> { _ -> PermissionAnswers.cancelQuestion() }
    is PlanCard -> { _ -> PermissionAnswers.keepPlanning(feedback) }
}
