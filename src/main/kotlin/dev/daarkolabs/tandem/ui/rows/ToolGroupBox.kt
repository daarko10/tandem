package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.ToolCall
import dev.daarkolabs.tandem.ui.ChatUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider

/**
 * An opened group (spec 4.6.1): a bordered box with one 36 dp sub-row per call, each with its own label, colour and chevron, and
 * dividers between them. A sub-row opens its call's body inside the box; its key is `c:<call id>`.
 */
@Composable
internal fun ToolGroupBox(calls: ImmutableList<ToolCall>, bodies: ImmutableMap<String, ImmutableList<MarkdownBlock>>, ui: ChatUiState) {
    val border = JewelTheme.globalColors.borders.normal
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).border(1.dp, border, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp)) {
        calls.forEachIndexed { index, call ->
            if (index > 0) Divider(Orientation.Horizontal, Modifier.fillMaxWidth(), color = border, thickness = 1.dp)
            SubRow(call, bodies["c:${call.id}"], ui)
        }
    }
}

@Composable
private fun SubRow(call: ToolCall, blocks: ImmutableList<MarkdownBlock>?, ui: ChatUiState) {
    val key = "c:${call.id}"
    val expandedState = ui.expanded(key)
    val expanded by expandedState
    val opens = canExpand(call)
    val needsParse = parsedOnOpen(call.body) && blocks == null
    Column(Modifier.fillMaxWidth()) {
        ToolLine(call.label, call.state, call.stats, opens, expanded && opens, minHeight = 36.dp) {
            val open = !expandedState.value
            expandedState.value = open
            if (open && needsParse) ui.onExpand(key)
        }
        if (expanded && opens) {
            if (needsParse) LaunchedEffect(key) { ui.onExpand(key) }
            ToolBodyView(call, blocks, ui)
        }
    }
}
