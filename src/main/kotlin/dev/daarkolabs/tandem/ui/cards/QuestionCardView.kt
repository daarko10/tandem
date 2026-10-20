@file:OptIn(ExperimentalLayoutApi::class)

package dev.daarkolabs.tandem.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.Pick
import dev.daarkolabs.tandem.chat.Question
import dev.daarkolabs.tandem.chat.QuestionCard
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.HorizontallyScrollableContainer
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.SegmentedControl
import org.jetbrains.jewel.ui.component.SegmentedControlButtonData
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.theme.checkboxStyle
import org.jetbrains.jewel.ui.theme.radioButtonStyle

/** The number keys reach the first this many rows of a question (`1`–`9`, spec 4.13). */
private const val DIGIT_ROWS = 9

/**
 * The question card of an `AskUserQuestion` request (spec 4.13): one tab per question when there are several (an unanswered one
 * marked ` ·`), the active question, its options as radio buttons (one answer) or checkboxes (several), each with its number key and
 * description, then `Other` and its field, then `Cancel` (Esc) and `Submit answers` (Enter, enabled once every question has an
 * answer), and how many more requests wait behind it ([moreWaiting]). `1`–`9` pick the active question's options outside the `Other`
 * field, `←`/`→` switch questions while the tabs have the focus, Enter in the field submits ([questionKey]). The first option takes
 * the focus when the card opens if [takeFocus] says so then ([CardFrame]).
 */
@Composable
internal fun QuestionCardView(
    card: QuestionCard,
    moreWaiting: Int,
    takeFocus: () -> Boolean,
    onSubmit: (Map<Int, Pick>) -> Unit,
    onCancel: () -> Unit,
) {
    val questions = card.questions
    var tab by remember(card.requestId) { mutableIntStateOf(0) }
    var picked by remember(card.requestId) { mutableStateOf(emptyMap<Int, Set<Int>>()) }
    val otherStates = remember(card.requestId, questions.size) { List(questions.size) { TextFieldState() } }
    val otherFocus = remember(card.requestId, questions.size) { List(questions.size) { FocusRequester() } }
    val defaultFocus = remember(card.requestId) { FocusRequester() }
    var otherFocused by remember(card.requestId) { mutableStateOf(false) }
    var stripFocused by remember(card.requestId) { mutableStateOf(false) }

    fun othersNow(): Map<Int, String> = otherStates.withIndex().associate { (i, state) -> i to state.text.toString() }
    // Read in composition, so that typing in a field recomposes the card (Submit's state, the radio of Other, the tab marker).
    val others = othersNow()
    val picks = picksOf(card, picked, others)
    fun answered(i: Int): Boolean = !others[i].isNullOrBlank() || picked[i].orEmpty().isNotEmpty()
    val submit: () -> Unit = { picksOf(card, picked, othersNow())?.let(onSubmit) }
    val active = tab.coerceIn(0, questions.lastIndex)
    val question = questions[active]

    // The `Other` row's index focuses its field; an option's toggles it (in a one-answer question it also clears `Other`, so that one
    // radio is selected at a time); a digit past the `Other` row changes nothing.
    fun pick(index: Int) {
        when (index) {
            question.options.size -> otherFocus[active].requestFocus()
            in question.options.indices -> {
                picked = picked + (active to togglePick(question, picked[active].orEmpty(), index))
                if (!question.multiSelect) otherStates[active].clearText()
            }
            else -> Unit
        }
    }

    // Before CardFrame's own keys, so that digits, arrows and Enter in `Other` never reach it.
    Box(
        Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            val key = questionKey(event, otherFocused, stripFocused) ?: return@onPreviewKeyEvent false
            when (key) {
                is QuestionKey.Pick -> pick(key.index)
                QuestionKey.PrevTab -> tab = (active - 1).coerceAtLeast(0)
                QuestionKey.NextTab -> tab = (active + 1).coerceAtMost(questions.lastIndex)
                QuestionKey.Submit -> submit()
            }
            true
        },
    ) {
        CardFrame(
            card.requestId,
            "question-card",
            "Question from Claude",
            textFieldFocused = otherFocused,
            defaultFocus = defaultFocus,
            takeFocus = takeFocus,
            onEnter = submit,
            onCmdEnter = null,
            onEscape = onCancel,
        ) {
            agentLine(card)?.let { Text(it, color = JewelTheme.globalColors.text.info) }
            if (questions.size > 1) {
                HorizontallyScrollableContainer(Modifier.fillMaxWidth().onFocusChanged { stripFocused = it.hasFocus }) {
                    SegmentedControl(
                        buttons = questions.mapIndexed { i, q ->
                            SegmentedControlButtonData(
                                selected = i == active,
                                content = { Text(tabLabel(q.header, answered(i))) },
                                onSelect = { tab = i },
                            )
                        },
                    )
                }
            }
            Text(question.text, fontWeight = FontWeight.SemiBold)
            val otherText = others[active]
            val otherChosen = !otherText.isNullOrBlank()
            val optionsPicked = picked[active].orEmpty()
            question.options.forEachIndexed { index, option ->
                ChoiceRow(
                    label = option.label,
                    cap = capFor(index),
                    description = option.description,
                    multiSelect = question.multiSelect,
                    // What Submit sends: `Other` wins while its text is non-blank (spec 4.13).
                    selected = !otherChosen && index in optionsPicked,
                    modifier = if (index == 0) Modifier.focusRequester(defaultFocus) else Modifier,
                    onPick = { pick(index) },
                )
            }
            ChoiceRow(
                label = "Other",
                cap = capFor(question.options.size),
                description = null,
                multiSelect = question.multiSelect,
                selected = otherChosen,
                // The requester is always attached: on `Other` when the question offers no option.
                modifier = if (question.options.isEmpty()) Modifier.focusRequester(defaultFocus) else Modifier,
                onPick = { otherFocus[active].requestFocus() },
            )
            TextArea(
                otherStates[active],
                Modifier.fillMaxWidth().padding(start = choiceIndent(question.multiSelect))
                    .focusRequester(otherFocus[active])
                    .onFocusChanged { otherFocused = it.hasFocus },
                lineLimits = TextFieldLineLimits.MultiLine(1, 4),
                placeholder = { Text("Something else…", color = JewelTheme.globalColors.text.info) },
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.weight(1f))
                CardButton("Cancel", "Esc", primary = false, onClick = onCancel)
                CardButton("Submit answers", "↩", primary = true, enabled = picks != null, onClick = submit)
            }
            MoreWaiting(moreWaiting)
        }
    }
}

/**
 * One choice of a question: a radio button ([multiSelect] false) or a checkbox, [label] in semibold with its [description] under it
 * in the secondary colour, and its number key [cap] at the end. Known to assistive technology, and to the IDE tests, as [label].
 */
@Composable
private fun ChoiceRow(
    label: String,
    cap: String?,
    description: String?,
    multiSelect: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onPick: () -> Unit,
) {
    val rowModifier = modifier.fillMaxWidth().semantics { contentDescription = label }
    val secondary = JewelTheme.globalColors.text.info
    val content: @Composable RowScope.() -> Unit = {
        // The description sits under the label, so it is indented to it.
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            description?.let { Text(it, color = secondary) }
        }
        if (cap != null) Text(cap, color = secondary)
    }
    if (multiSelect) {
        CheckboxRow(checked = selected, onCheckedChange = { onPick() }, modifier = rowModifier, verticalAlignment = Alignment.Top, content = content)
    } else {
        RadioButtonRow(selected = selected, onClick = onPick, modifier = rowModifier, verticalAlignment = Alignment.Top, content = content)
    }
}

/** How far a choice's label starts from its row's edge: the radio button or the checkbox and the gap after it. */
@Composable
private fun choiceIndent(multiSelect: Boolean): Dp = if (multiSelect) {
    JewelTheme.checkboxStyle.metrics.let { it.checkboxSize.width + it.iconContentGap }
} else {
    JewelTheme.radioButtonStyle.metrics.let { it.radioButtonSize.width + it.iconContentGap }
}

/** The number key of the row at [index]: `1`–`9`, none past the ninth row, which no key reaches. */
private fun capFor(index: Int): String? = if (index < DIGIT_ROWS) "${index + 1}" else null

/**
 * The option picks of [question] after its option [index] is picked from [picked]: that option alone for a one-answer question,
 * that option toggled for a multi-select one; unchanged when [index] is no option's (a digit past the last option, or the `Other`
 * row's index, `options.size`).
 */
internal fun togglePick(question: Question, picked: Set<Int>, index: Int): Set<Int> = when {
    index !in question.options.indices -> picked
    !question.multiSelect -> setOf(index)
    index in picked -> picked - index
    else -> picked + index
}

/**
 * What [card] sends ([dev.daarkolabs.tandem.chat.Cards.answers]), from the option picks [picked] and the `Other` texts [others] per
 * question index: the trimmed `Other` text when it is not blank, else the picked options in option order; null while any question
 * has neither, since `Submit answers` needs an answer to every question (spec 4.13).
 */
internal fun picksOf(card: QuestionCard, picked: Map<Int, Set<Int>>, others: Map<Int, String>): Map<Int, Pick>? {
    val picks = LinkedHashMap<Int, Pick>()
    for (i in card.questions.indices) {
        val other = others[i]?.trim().orEmpty()
        val options = picked[i].orEmpty()
        picks[i] = when {
            other.isNotEmpty() -> Pick.Other(other)
            options.isNotEmpty() -> Pick.Options(options.sorted())
            else -> return null
        }
    }
    return picks
}

/** A question's tab label: its [header], followed by ` ·` until it is [answered] (spec 4.13). */
internal fun tabLabel(header: String, answered: Boolean): String = if (answered) header else "$header ·"
