@file:OptIn(ExperimentalLayoutApi::class, ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.Cards
import dev.daarkolabs.tandem.chat.PlanCard
import dev.daarkolabs.tandem.chat.PlanSource
import dev.daarkolabs.tandem.ui.bottomFade
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import java.nio.file.Path

/** A plan taller than this is clamped until `Show more` (spec 4.14). */
private val PLAN_CLAMP = 240.dp

/**
 * The plan approval card of an `ExitPlanMode` request (spec 4.14): `Approve this plan?`, where the plan came from
 * ([Cards.sourceLine]), the plan as Markdown ([blocks], parsed by the presenter; clamped at 240 dp, faded, with `Show more` /
 * `Show less`), `Open plan file` when the request names an absolute plan file ([planFileToOpen]), the [feedback] field, then
 * `Keep planning` (Esc, and Enter in the field), `Approve, keep current mode` and `Approve and start coding` (Enter elsewhere on the
 * card, the default, which takes the focus when [takeFocus] says so), and how many more requests wait behind it ([moreWaiting]).
 * [onApprove] says whether to start coding.
 */
@Composable
internal fun PlanCardView(
    card: PlanCard,
    blocks: ImmutableList<MarkdownBlock>?,
    moreWaiting: Int,
    feedback: TextFieldState,
    takeFocus: () -> Boolean,
    onApprove: (startCoding: Boolean) -> Unit,
    onKeepPlanning: () -> Unit,
    onOpenPlanFile: () -> Unit,
) {
    var feedbackFocused by remember(card.requestId) { mutableStateOf(false) }
    val defaultFocus = remember(card.requestId) { FocusRequester() }
    val secondary = JewelTheme.globalColors.text.info
    CardFrame(
        card.requestId,
        "plan-card",
        "Plan approval",
        textFieldFocused = feedbackFocused,
        defaultFocus = defaultFocus,
        takeFocus = takeFocus,
        onEnter = { onApprove(true) },
        onCmdEnter = null,
        onEscape = onKeepPlanning,
    ) {
        Text("Approve this plan?", fontWeight = FontWeight.SemiBold)
        agentLine(card)?.let { Text(it, color = secondary) }
        Text(Cards.sourceLine(card), color = secondary)
        val plan = card.plan
        if (plan != null && blocks != null) {
            var expanded by remember(card.requestId) { mutableStateOf(false) }
            var overflows by remember(card.requestId) { mutableStateOf(false) }
            val density = LocalDensity.current
            Box(
                Modifier.fillMaxWidth()
                    .then(if (expanded) Modifier else Modifier.heightIn(max = PLAN_CLAMP).clipToBounds())
                    .then(if (overflows && !expanded) Modifier.bottomFade(60.dp) else Modifier),
            ) {
                Markdown(
                    blocks,
                    plan,
                    // Unbounded, so that the size seen here is the plan's whole height inside the clamp.
                    modifier = Modifier.fillMaxWidth()
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .onSizeChanged { overflows = it.height > with(density) { PLAN_CLAMP.roundToPx() } },
                    selectable = true,
                )
            }
            if (overflows) Link(if (expanded) "Show less" else "Show more", onClick = { expanded = !expanded })
        }
        if (planFileToOpen(card) != null) Link("Open plan file", onClick = onOpenPlanFile)
        TextField(
            feedback,
            Modifier.fillMaxWidth()
                .onFocusChanged { feedbackFocused = it.hasFocus }
                // The card leaves Enter to the focused field; here it sends the feedback.
                .onPreviewKeyEvent { event ->
                    planFeedbackKey(event) == PlanKey.KEEP_PLANNING && run {
                        onKeepPlanning()
                        true
                    }
                },
            placeholder = { Text("What should change?", color = secondary) },
        )
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardButton("Keep planning", "Esc", primary = false, onClick = onKeepPlanning)
            Spacer(Modifier.weight(1f))
            CardButton("Approve, keep current mode", null, primary = false) { onApprove(false) }
            // The card's focus starts here, never in the feedback field or on `Show more`.
            CardButton("Approve and start coding", "↩", primary = true, modifier = Modifier.focusRequester(defaultFocus)) {
                onApprove(true)
            }
        }
        MoreWaiting(moreWaiting)
    }
}

/**
 * The plan file `Open plan file` opens (spec 4.14): the request's own `planFilePath` ([PlanSource.EXIT_PLAN_INPUT]; a
 * [PlanSource.PLAN_FILE_DRAFT] Write failed, so its file does not exist), only when it is an absolute path; null otherwise, and for a
 * path the platform cannot parse. The path comes from the CLI, so a relative one, or the fixtures' redacted `<path>`, opens nothing.
 */
internal fun planFileToOpen(card: PlanCard): Path? =
    card.planFilePath?.takeIf { card.source == PlanSource.EXIT_PLAN_INPUT }
        ?.let { runCatching { Path.of(it) }.getOrNull() }
        ?.takeIf { it.isAbsolute }
