package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.TurnEndRow
import dev.daarkolabs.tandem.session.TurnEnd
import dev.daarkolabs.tandem.ui.chatColumn
import dev.daarkolabs.tandem.ui.smallTextStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * How the latest turn ended (spec 4.10): one secondary line, `Worked for 4s`, or the interrupt line; a failed turn's line is red with
 * the error icon, so it never reads like a finished one. One text node.
 */
@Composable
internal fun TurnEndItem(row: TurnEndRow) {
    val failed = row.end == TurnEnd.FAILED
    val colors = JewelTheme.globalColors.text
    Row(Modifier.chatColumn().padding(top = row.gap.dp.dp), verticalAlignment = Alignment.CenterVertically) {
        if (failed) {
            Icon(AllIconsKeys.General.Error, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(row.text, style = smallTextStyle(), color = if (failed) colors.error else colors.info, modifier = Modifier.testTag("turn-footer"))
    }
}
