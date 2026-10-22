package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.NoticeLink
import dev.daarkolabs.tandem.chat.NoticeRow
import dev.daarkolabs.tandem.session.NoticeKind
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.ui.chatColumn
import org.jetbrains.jewel.foundation.modifier.thenIf
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * A notice (spec 4.11): plain secondary text; a warning or an error has its icon and colour, so a failure is easy to find.
 *
 * A local command's output (MCP spec 3.5) is one node for assistive technology, `Reply to /<command>` (`Command output` when the
 * command is unknown); its text stays plain, never Markdown, so backticks show as typed. `/mcp`'s ends with the link
 * `Open the MCP panel` in place of the CLI's terminal hint, which calls [onOpenMcp].
 */
@Composable
internal fun NoticeItem(row: NoticeRow, onOpenMcp: () -> Unit = {}) {
    val colors = JewelTheme.globalColors.text
    Row(
        Modifier.chatColumn().padding(top = row.gap.dp.dp)
            .thenIf(row.kind == NoticeKind.LOCAL_COMMAND) {
                semantics(mergeDescendants = true) { contentDescription = row.command?.let { "Reply to /$it" } ?: "Command output" }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The text takes what the link leaves, so a long notice wraps instead of pushing the link out.
        val text = Modifier.weight(1f, fill = false)
        when (row.level) {
            NoticeLevel.INFO -> Text(row.text, color = colors.info, modifier = text)
            NoticeLevel.WARNING -> {
                Icon(AllIconsKeys.General.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(row.text, color = colors.warning, modifier = text)
            }
            NoticeLevel.ERROR -> {
                Icon(AllIconsKeys.General.Error, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(row.text, color = colors.error, modifier = text)
            }
        }
        when (row.link) {
            NoticeLink.OPEN_MCP -> NoticeLinkText(NoticeLink.OPEN_MCP.label, onOpenMcp)
            null -> Unit
        }
    }
}

/**
 * The action at the end of a notice (MCP spec 3.5): its [label] in the link colour, after the text, clickable and named by its label.
 * A clickable is its own node, so it stays apart from the notice's merged one.
 */
@Composable
private fun NoticeLinkText(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = JewelTheme.globalColors.text.info,
        modifier = Modifier.padding(start = 6.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    )
}
