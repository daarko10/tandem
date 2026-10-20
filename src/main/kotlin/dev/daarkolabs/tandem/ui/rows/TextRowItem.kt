@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.TextRow
import dev.daarkolabs.tandem.ui.RenderedRow
import dev.daarkolabs.tandem.ui.chatColumn
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.Markdown

/** One text block of a reply (spec 4.4): plain Markdown flush with the column's left edge, from blocks parsed off the EDT. */
@Composable
internal fun TextRowItem(rendered: RenderedRow) {
    val row = rendered.row as TextRow
    Box(Modifier.chatColumn().padding(top = row.gap.dp.dp)) {
        Markdown(rendered.blocks ?: persistentListOf(), row.markdown, modifier = Modifier.fillMaxWidth(), selectable = true)
    }
}
