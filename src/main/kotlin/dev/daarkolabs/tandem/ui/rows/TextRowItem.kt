@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.TextRow
import dev.daarkolabs.tandem.ui.LocalUrlHandler
import dev.daarkolabs.tandem.ui.RenderedRow
import dev.daarkolabs.tandem.ui.chatColumn
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.modifier.thenIf
import org.jetbrains.jewel.markdown.Markdown

/**
 * One text block of a reply (spec 4.4): plain Markdown flush with the column's left edge, from blocks parsed off the EDT. The last
 * text block of an ended turn's reply has its hover row ([MessageActionsRow]) 4 dp under it when [reply] is given (polish spec 2.6);
 * a subagent's text row passes none.
 */
@Composable
internal fun TextRowItem(rendered: RenderedRow, reply: ReplyHost? = null) {
    val row = rendered.row as TextRow
    val actions = row.actions?.takeIf { reply != null }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(Modifier.chatColumn().padding(top = row.gap.dp.dp).thenIf(actions != null) { hoverable(hover) }) {
        Markdown(
            rendered.blocks ?: persistentListOf(),
            row.markdown,
            modifier = Modifier.fillMaxWidth(),
            selectable = true,
            onUrlClick = LocalUrlHandler.current,
        )
        if (actions != null && reply != null) {
            Spacer(Modifier.height(4.dp))
            MessageActionsRow(actions, reply, hovered)
        }
    }
}
