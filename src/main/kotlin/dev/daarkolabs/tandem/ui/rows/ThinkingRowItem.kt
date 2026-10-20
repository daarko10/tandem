package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.RowLabel
import dev.daarkolabs.tandem.chat.ThinkingRow
import dev.daarkolabs.tandem.chat.ToolState
import dev.daarkolabs.tandem.ui.ChatUiState
import dev.daarkolabs.tandem.ui.chatColumn
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/** What assistive technology calls a thinking row while it streams (spec 4.5). */
private const val STREAMING_DESCRIPTION: String = "Claude, thinking"

/**
 * A thinking block (spec 4.5), drawn like a tool row: one secondary line, `Thinking… · 50 tokens` while it streams and
 * `Thought for 2s` once done, with a chevron only once summary text has arrived. Opened ([ChatUiState.expanded], closed by default,
 * also while streaming), the summary shows below in the normal text colour, one paragraph per `\n\n`, as plain text: never Markdown.
 */
@Composable
internal fun ThinkingRowItem(row: ThinkingRow, ui: ChatUiState) {
    val expandedState = ui.expanded(row.key)
    val expanded by expandedState
    val opens = row.summary.isNotBlank()
    val label = remember(row.label) { RowLabel(row.label, persistentListOf()) }
    Column(Modifier.chatColumn().padding(top = row.gap.dp.dp)) {
        // DONE draws the label in the secondary colour and, closed, no chevron: a thinking row never runs as a tool does.
        ToolLine(
            label,
            ToolState.DONE,
            stats = null,
            opens = opens,
            open = expanded && opens,
            minHeight = 24.dp,
            description = if (row.streaming) STREAMING_DESCRIPTION else row.label,
        ) { expandedState.value = !expandedState.value }
        if (expanded && opens) {
            val paragraphs = remember(row.summary) { paragraphs(row.summary) }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                paragraphs.forEachIndexed { i, paragraph ->
                    Text(
                        paragraph,
                        color = JewelTheme.globalColors.text.normal,
                        modifier = if (i > 0) Modifier.padding(top = 8.dp) else Modifier,
                    )
                }
            }
        }
    }
}

/** [summary]'s paragraphs: split on blank lines, trimmed, the empty ones left out. */
private fun paragraphs(summary: String): List<String> = summary.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
