package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.HISTORY_LOADING
import dev.daarkolabs.tandem.chat.ModelBlock
import dev.daarkolabs.tandem.chat.TIPS_SHOWN
import dev.daarkolabs.tandem.chat.USAGE_LOADING
import dev.daarkolabs.tandem.chat.UsageCardModel
import dev.daarkolabs.tandem.chat.UsageFact
import dev.daarkolabs.tandem.chat.UsageSection
import dev.daarkolabs.tandem.chat.UsageWindowModel
import dev.daarkolabs.tandem.chat.WINDOW_EMPTY
import dev.daarkolabs.tandem.chat.tipsTitle
import dev.daarkolabs.tandem.chat.tipsToggle
import dev.daarkolabs.tandem.session.UsagePart
import dev.daarkolabs.tandem.ui.ChatUiState
import dev.daarkolabs.tandem.ui.chatPalette
import dev.daarkolabs.tandem.ui.smallTextStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.SegmentedControl
import org.jetbrains.jewel.ui.component.SegmentedControlButtonData
import org.jetbrains.jewel.ui.component.Text

/**
 * The /usage card (desktop-parity spec 3.2, 3.5, 3.7, 16) for [model], drawn in place of a usage notice's text: a bordered box in the
 * chat column (the polish cards' frame: 6 dp radius, 12 dp inside), its sections stacked and every line wrapping, so nothing sits side
 * by side and the card reads at the tool window's narrowest width. `Retry` asks again for one part through [onRetry]. What the card
 * remembers on screen (the window, the tips and `CLI output` disclosures) is per card, keyed by [key], and kept in the session's [ui]
 * like an open row's state, so it survives the card scrolling out of view and back; it is not saved with the session. The box is
 * `Usage for this session` for assistive technology and is not merged, so its window control, each `Retry` and the disclosures stay
 * their own nodes; its text is selectable like a reply's. Nothing in it takes the focus by itself: it arrives while the user may type.
 */
@Composable
internal fun UsageCardView(model: UsageCardModel, key: String, ui: ChatUiState, onRetry: (UsagePart) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
            .padding(12.dp)
            .testTag("usage-card")
            .semantics { contentDescription = "Usage for this session" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SessionSection(model, onRetry)
        if (model.models.isNotEmpty()) BreakdownSection(model.models)
        HistorySection(model.history, key, ui, onRetry)
        CliOutput(model, key, ui)
    }
}

/** `This session`: the facts as one wrapping line, then the CLI's `Prompt cache` line; or the part's loading or failure line. */
@Composable
private fun SessionSection(model: UsageCardModel, onRetry: (UsagePart) -> Unit) {
    Section("This session") {
        when (val facts = model.facts) {
            UsageSection.Loading -> Dim(USAGE_LOADING)
            is UsageSection.Error -> Failure(facts, onRetry = { onRetry(UsagePart.SESSION) })
            is UsageSection.Note -> Dim(facts.text)
            is UsageSection.Shown -> if (facts.value.isNotEmpty()) Selectable { Text(pairs(facts.value, chatPalette().dim)) }
        }
        model.promptCache?.let { cache -> Selectable { Text(pairs(listOf(UsageFact("Prompt cache", cache)), chatPalette().dim)) } }
    }
}

/** `Breakdown`: per model its label and cost, its share of the session's cost as a bar and a line, then its token rows. */
@Composable
private fun BreakdownSection(models: List<ModelBlock>) {
    val palette = chatPalette()
    Section("Breakdown") {
        for (block in models) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Selectable {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(block.label) }
                            block.cost?.let { append("  $it") }
                        },
                    )
                }
                block.costShare?.let { share ->
                    ShareBar(share, palette.accent, palette.dim)
                    block.costShareText?.let { Dim(it) }
                }
                if (block.rows.isNotEmpty()) Selectable { Text(pairs(block.rows, palette.dim)) }
            }
        }
    }
}

/**
 * `What's using your limits?`: the `24h | 7d` control (`24h` first, the choice per card), then the window's caveat, its behaviour
 * lines, its share rows and its tips; or the part's loading, failure or note line.
 */
@Composable
private fun HistorySection(
    history: UsageSection<Pair<UsageWindowModel?, UsageWindowModel?>>,
    key: String,
    ui: ChatUiState,
    onRetry: (UsagePart) -> Unit,
) {
    Section("What's using your limits?") {
        when (history) {
            UsageSection.Loading -> Dim(HISTORY_LOADING)
            is UsageSection.Error -> Failure(history, onRetry = { onRetry(UsagePart.HISTORY) })
            is UsageSection.Note -> Dim(history.text)
            is UsageSection.Shown -> {
                // `24h` until `7d` is chosen.
                var week by ui.expanded("$key:usage-7d")
                SegmentedControl(
                    buttons = listOf("24h", "7d").mapIndexed { index, label ->
                        SegmentedControlButtonData(selected = (index == 1) == week, content = { Text(label) }, onSelect = { week = index == 1 })
                    },
                    modifier = Modifier.semantics { contentDescription = "Usage window" },
                )
                val window = if (week) history.value.second else history.value.first
                if (window == null) Dim(WINDOW_EMPTY) else WindowView(window, key, ui)
            }
        }
    }
}

/** One window: the caveat, the behaviour lines, the share rows with their bars, then the tips with their disclosure. */
@Composable
private fun WindowView(window: UsageWindowModel, key: String, ui: ChatUiState) {
    val palette = chatPalette()
    Dim(window.caveat)
    if (window.behaviors.isNotEmpty()) {
        Selectable {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (line in window.behaviors) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line.pct) }
                            append(" ${line.label}")
                        },
                    )
                }
            }
        }
    }
    // Name, kind (dim), the bar, then the share (spec 3.2).
    for (share in window.shares) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Selectable {
                Text(
                    buildAnnotatedString {
                        append(share.name)
                        withStyle(SpanStyle(color = palette.dim)) { append("  ${share.kind}") }
                    },
                )
            }
            ShareBar(share.fraction, palette.accent, palette.dim)
            Selectable { Text(share.pct) }
        }
    }
    if (window.tips.isEmpty()) return
    var open by ui.expanded("$key:usage-tips")
    Text(tipsTitle(window.tips.size), fontWeight = FontWeight.SemiBold)
    Selectable {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (tip in if (open) window.tips else window.tips.take(TIPS_SHOWN)) {
                Column {
                    Text(tip.title)
                    tip.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = smallTextStyle(), color = palette.dim) }
                }
            }
        }
    }
    tipsToggle(window.tips.size, open)?.let { label -> Disclosure(label, open, chevron = false) { open = !open } }
}

/** `CLI output`: a disclosure, open at first when the card says so, showing the notice's own text, plain, in the editor style. */
@Composable
private fun CliOutput(model: UsageCardModel, key: String, ui: ChatUiState) {
    // Whether the user flipped it from the model's default. A part that fails later opens it as well (spec 3.2), so the state follows
    // the model's flag: a new flag starts unflipped.
    var flipped by ui.expanded("$key:usage-cli:${model.cliTextOpen}")
    val open = model.cliTextOpen != flipped
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Disclosure("CLI output", open, chevron = true) { flipped = !flipped }
        if (open) Selectable { Text(model.cliText, style = JewelTheme.editorTextStyle, color = chatPalette().secondary) }
    }
}

/** A section: its [title] in the semibold weight, then its [content], stacked. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** A failed part: its text in the error colour and, when [UsageSection.Error.retry], `Retry`. */
@Composable
private fun Failure(error: UsageSection.Error, onRetry: () -> Unit) {
    Selectable { Text(error.text, color = JewelTheme.globalColors.text.error) }
    if (error.retry) OutlinedButton(onClick = onRetry) { Text("Retry") }
}

/** A line in the dim tier, in the footer size. */
@Composable
private fun Dim(text: String) {
    Selectable { Text(text, style = smallTextStyle(), color = chatPalette().dim) }
}

/** Text that can be selected and copied, as a reply's. */
@Composable
private fun Selectable(content: @Composable () -> Unit) {
    SelectionContainer { content() }
}

/**
 * A disclosure row, [label] (after `›` or `⌄` with [chevron]), at least 24 dp tall; Enter, Space or a click calls [onToggle]. Its state
 * is `Expanded` or `Collapsed` for assistive technology.
 */
@Composable
private fun Disclosure(label: String, open: Boolean, chevron: Boolean, onToggle: () -> Unit) {
    val dim = chatPalette().dim
    Row(
        Modifier.heightIn(min = 24.dp)
            .clickable(onClick = onToggle)
            .semantics {
                role = Role.Button
                stateDescription = if (open) "Expanded" else "Collapsed"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chevron) Text(if (open) "⌄" else "›", color = dim, modifier = Modifier.width(14.dp))
        Text(label, color = JewelTheme.globalColors.text.info)
    }
}

/** A 4 dp bar of [fraction] in [color] over a track of [dim] at 30 % alpha; a non-zero share is at least 1 px wide. */
@Composable
private fun ShareBar(fraction: Float, color: Color, dim: Color) {
    Box(
        Modifier.fillMaxWidth().height(4.dp).drawBehind {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(dim.copy(alpha = 0.3f), cornerRadius = radius)
            val share = fraction.coerceIn(0f, 1f)
            if (share > 0f) drawRoundRect(color, size = Size((size.width * share).coerceAtLeast(1f), size.height), cornerRadius = radius)
        },
    )
}

/** `label value` pairs as one wrapping line, the labels in [dim], the pairs apart by ` · `. */
private fun pairs(facts: List<UsageFact>, dim: Color): AnnotatedString = buildAnnotatedString {
    facts.forEachIndexed { index, fact ->
        if (index > 0) withStyle(SpanStyle(color = dim)) { append(" · ") }
        withStyle(SpanStyle(color = dim)) { append(fact.label) }
        append(" ${fact.value}")
    }
}
