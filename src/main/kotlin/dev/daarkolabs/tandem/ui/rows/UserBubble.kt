package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.UserRow
import dev.daarkolabs.tandem.ui.chatColumn
import dev.daarkolabs.tandem.ui.chatPalette
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * What the user sent (spec 4.3): a filled bubble flush with the column's right edge, as wide as its text up to 85 % of the column.
 * The text is plain, never Markdown, with its line breaks; a slash command or `!` line is in the editor font. One text node.
 */
@Composable
internal fun UserBubble(row: UserRow) {
    val body = JewelTheme.defaultTextStyle
    val style = if (row.mono) JewelTheme.editorTextStyle.copy(fontSize = body.fontSize, lineHeight = body.lineHeight) else body
    val fill = chatPalette().bubbleFill
    Box(Modifier.chatColumn().padding(top = row.gap.dp.dp), contentAlignment = Alignment.TopEnd) {
        Box(Modifier.fillMaxWidth(BUBBLE_WIDTH), contentAlignment = Alignment.TopEnd) {
            Box(Modifier.background(fill, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                SelectionContainer {
                    Text(row.text, style = style, color = JewelTheme.globalColors.text.normal, modifier = Modifier.testTag("user-bubble"))
                }
            }
        }
    }
}

/** The bubble's widest, as a fraction of the column. */
private const val BUBBLE_WIDTH = 0.85f
