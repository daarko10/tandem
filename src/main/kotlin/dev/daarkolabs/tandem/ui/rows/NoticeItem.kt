package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.NoticeRow
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.ui.chatColumn
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** A notice (spec 4.11): plain secondary text; a warning or an error has its icon and colour, so a failure is easy to find. */
@Composable
internal fun NoticeItem(row: NoticeRow) {
    val colors = JewelTheme.globalColors.text
    Row(Modifier.chatColumn().padding(top = row.gap.dp.dp), verticalAlignment = Alignment.CenterVertically) {
        when (row.level) {
            NoticeLevel.INFO -> Text(row.text, color = colors.info)
            NoticeLevel.WARNING -> {
                Icon(AllIconsKeys.General.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(row.text, color = colors.warning)
            }
            NoticeLevel.ERROR -> {
                Icon(AllIconsKeys.General.Error, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(row.text, color = colors.error)
            }
        }
    }
}
