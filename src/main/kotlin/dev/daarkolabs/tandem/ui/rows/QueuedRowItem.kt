package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.QueuedRow
import dev.daarkolabs.tandem.ui.chatColumn
import dev.daarkolabs.tandem.ui.chatPalette
import dev.daarkolabs.tandem.ui.smallTextStyle
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** How far a waiting message's bubble is dimmed: the dim tier of a user bubble (desktop-parity spec 12.1). */
private const val QUEUED_ALPHA = 0.6f

/**
 * A message sent while Claude works that the CLI has not started yet (desktop-parity spec 12.1, 12.3): the user bubble's frame at the
 * dim tier, flush right like a sent prompt's, with its text and its images' thumbnails ([images], by image id, from the presenter),
 * and its caption under it in the footer size. When [QueuedRow.withdrawable], two 16 dp icon buttons under the caption:
 * `Edit queued message` puts it back in the composer (`onWithdraw(true)`), `Cancel queued message` discards it (`onWithdraw(false)`);
 * both disabled while a withdraw is out. The text and the caption are one node for assistive technology, each button its own;
 * nothing takes the focus by itself.
 */
@Composable
internal fun QueuedRowItem(
    row: QueuedRow,
    onWithdraw: (restore: Boolean) -> Unit,
    images: ImmutableMap<String, ImageBitmap> = persistentMapOf(),
) {
    val palette = chatPalette()
    Box(Modifier.chatColumn().padding(top = row.gap.dp.dp).testTag("queued-row"), contentAlignment = Alignment.TopEnd) {
        Box(Modifier.fillMaxWidth(QUEUED_WIDTH), contentAlignment = Alignment.TopEnd) {
            Column(horizontalAlignment = Alignment.End) {
                Column(Modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.End) {
                    if (row.images.isNotEmpty()) Box(Modifier.alpha(QUEUED_ALPHA)) { ImageStrip(row.images, images) }
                    if (row.text.isNotBlank()) {
                        if (row.images.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.alpha(QUEUED_ALPHA)
                                .background(palette.bubbleFill, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(row.text, color = JewelTheme.globalColors.text.normal, maxLines = MAX_LINES, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(row.caption, style = smallTextStyle(), color = palette.dim, modifier = Modifier.padding(top = 4.dp))
                }
                if (row.withdrawable) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WithdrawButton(AllIconsKeys.Actions.Edit, "Edit queued message", enabled = !row.withdrawing) { onWithdraw(true) }
                        WithdrawButton(AllIconsKeys.Actions.Close, "Cancel queued message", enabled = !row.withdrawing) { onWithdraw(false) }
                    }
                }
            }
        }
    }
}

/** One of a waiting message's two buttons: a 16 dp [icon], described as [description]. */
@Composable
private fun WithdrawButton(icon: IconKey, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(16.dp).semantics { contentDescription = description }) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

/** The bubble's widest, as a fraction of the column: a sent prompt's. */
private const val QUEUED_WIDTH = 0.85f

/** A waiting message shows at most this many lines; the rest shows once it is sent. */
private const val MAX_LINES = 8
