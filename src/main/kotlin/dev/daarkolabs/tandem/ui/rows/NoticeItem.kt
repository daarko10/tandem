@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.NoticeLink
import dev.daarkolabs.tandem.chat.NoticeRow
import dev.daarkolabs.tandem.session.NoticeKind
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.UsagePart
import dev.daarkolabs.tandem.ui.LocalUrlHandler
import dev.daarkolabs.tandem.ui.chatColumn
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.modifier.thenIf
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * A notice (spec 4.11): plain secondary text; a warning or an error has its icon and colour, so a failure is easy to find.
 *
 * A local command's output (MCP spec 3.5) is one node for assistive technology, `Reply to /<command>` (`Command output` when the
 * command is unknown); its text stays plain, never Markdown, so backticks show as typed, except `/context`'s report, whose [blocks]
 * (parsed off the EDT, desktop-parity spec 3.1) draw as a reply's Markdown does, so its tables read as tables. `/mcp`'s ends with the
 * link `Open the MCP panel` in place of the CLI's terminal hint, which calls [onOpenMcp].
 *
 * A `/usage` (or `/cost`) notice of the live stream with its card (desktop-parity spec 3.2, 3.7) draws [UsageCardView] in place of
 * the text, and its `Retry` calls [onUsageRetry] with the line's uuid. That row is not merged: its `Reply to /<command>` sits on a
 * container that does not merge, so the card, its window control, each `Retry` and the disclosures stay their own nodes.
 */
@Composable
internal fun NoticeItem(
    row: NoticeRow,
    blocks: ImmutableList<MarkdownBlock>? = null,
    onOpenMcp: () -> Unit = {},
    onUsageRetry: (uuid: String, part: UsagePart) -> Unit = { _, _ -> },
) {
    val colors = JewelTheme.globalColors.text
    val description = row.command?.let { "Reply to /$it" } ?: "Command output"
    val usage = row.usage
    val uuid = row.sourceUuid
    if (usage != null && uuid != null) {
        Column(Modifier.chatColumn().padding(top = row.gap.dp.dp).semantics { contentDescription = description }) {
            UsageCardView(usage, row.key) { part -> onUsageRetry(uuid, part) }
        }
        return
    }
    val modifier = Modifier.chatColumn().padding(top = row.gap.dp.dp)
        .thenIf(row.kind == NoticeKind.LOCAL_COMMAND) { semantics(mergeDescendants = true) { contentDescription = description } }
    if (blocks != null) {
        Column(modifier) {
            Markdown(blocks, row.text, modifier = Modifier.fillMaxWidth(), selectable = true, onUrlClick = LocalUrlHandler.current)
        }
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
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
 * The action at the end of a notice (MCP spec 3.5): a Jewel [Link] with [label], after the text, so it looks and acts as a link, and
 * named by its label. A clickable is its own node, so it stays apart from the notice's merged one.
 */
@Composable
private fun NoticeLinkText(label: String, onClick: () -> Unit) {
    Link(text = label, onClick = onClick, modifier = Modifier.padding(start = 6.dp).semantics { contentDescription = label })
}
