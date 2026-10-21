@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.TextRow
import dev.daarkolabs.tandem.chat.ToolBody
import dev.daarkolabs.tandem.chat.ToolCall
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.ui.ChatUiState
import dev.daarkolabs.tandem.ui.RenderedRow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.MarkdownBlock

/**
 * The opened body of an Agent call (spec 4.8): its subagent's rows indented 16 dp, each tool call its own row and the forwarded text
 * as a text row, then the agent's own Markdown body when it has one (its final text, when none was forwarded). The parsed Markdown
 * of the nested rows comes in [bodies] by each row's key, and the agent's own under [rowKey], all from the presenter.
 */
@Composable
internal fun AgentBody(rowKey: String, call: ToolCall, bodies: ImmutableMap<String, ImmutableList<MarkdownBlock>>, ui: ChatUiState) {
    Column(Modifier.padding(start = 16.dp)) {
        call.children.forEach { child ->
            key(child.key) {
                when (child) {
                    is ToolRow -> ToolRowItem(RenderedRow(child, null, bodies), ui)
                    is TextRow -> TextRowItem(RenderedRow(child, bodies[child.key], bodies))
                    // A subagent's rows are its tool calls and its text only.
                    else -> Unit
                }
            }
        }
    }
    if (call.body !is ToolBody.None) ToolBodyView(call, bodies[rowKey], ui)
}
