package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.ShellRow
import dev.daarkolabs.tandem.ui.ChatUiState
import dev.daarkolabs.tandem.ui.chatColumn
import dev.daarkolabs.tandem.ui.chatPalette
import dev.daarkolabs.tandem.ui.smallTextStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * A shell escape (MCP spec 12.1, 12.2), in place of a user bubble: `! <command>` in the editor style, every line of it, with
 * `Stop command` at its right while it runs ([onStop]); under it, once there is output, the output as a Bash call's (polish spec 2.4
 * item 3: mono, no fill and no box, in the secondary tier, 10 dp below), clamped at 20 lines with `Show more` / `Show less` as a tool
 * body ([Clamped], its state `sh:<id>`'s `output` in [ui]) and selectable; then the status line in the footer size, in the error
 * colour, when there is something to say (`exit 2`, `Stopped after 3.0s`, …). The header is one node, `! <command>`, and the output
 * is described as `Shell output`.
 */
@Composable
internal fun ShellRowItem(row: ShellRow, ui: ChatUiState, onStop: () -> Unit) {
    val colors = JewelTheme.globalColors
    Column(Modifier.chatColumn().padding(top = row.gap.dp.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            val header = "! ${row.command}"
            Text(
                header,
                style = JewelTheme.editorTextStyle,
                color = colors.text.normal,
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = header },
            )
            if (row.running) {
                IconButton(onClick = onStop, modifier = Modifier.size(24.dp).semantics { contentDescription = "Stop command" }) {
                    Icon(AllIconsKeys.Actions.Suspend, contentDescription = null, tint = colors.text.info)
                }
            }
        }
        if (row.output.isNotEmpty()) {
            val secondary = chatPalette().secondary
            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    Clamped(row.key, "output", row.output.size, row.totalLines, ui) { shown, modifier ->
                        Box(modifier.fillMaxWidth().padding(top = 10.dp).semantics { contentDescription = "Shell output" }) {
                            MonoLines(row.output.take(shown), firstLineError = false, color = secondary)
                        }
                    }
                }
            }
        }
        row.status?.let { status ->
            Text(
                status,
                style = smallTextStyle(),
                color = if (row.statusError) colors.text.error else colors.text.info,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
