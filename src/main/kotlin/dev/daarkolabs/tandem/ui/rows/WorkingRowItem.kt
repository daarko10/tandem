package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.intellij.ide.ui.UISettings
import dev.daarkolabs.tandem.chat.WorkingIndicator
import dev.daarkolabs.tandem.chat.WorkingIndicator.Mode
import dev.daarkolabs.tandem.chat.WorkingRow
import dev.daarkolabs.tandem.ui.TandemColors
import dev.daarkolabs.tandem.ui.chatColumn
import dev.daarkolabs.tandem.ui.smallTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/** How often the elapsed time and the not-responding check move on. */
private const val TICK_MS: Long = 1_000

/**
 * The working row while a turn runs (spec 4.9): the [Spark], then `Pondering… · 3s · 1.2k tokens`, `Waiting for you · 3s` in the
 * warning colour or `Not responding · 1m 5s` in the error colour. Once a second it reads the clock and when the process last wrote a
 * line ([lastFrameAt], read on the tick, never collected, so that a line never recomposes the row); [WorkingIndicator.view] does the
 * rest. One node for assistive technology, described as `Claude is working` (or waiting, or not responding), with the text merged in.
 */
@Composable
internal fun WorkingRowItem(row: WorkingRow, lastFrameAt: StateFlow<Long>) {
    var now by remember(row.key) { mutableLongStateOf(System.currentTimeMillis()) }
    var lastFrame by remember(row.key) { mutableLongStateOf(lastFrameAt.value) }
    LaunchedEffect(row.key) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
            lastFrame = lastFrameAt.value
        }
    }
    val view = WorkingIndicator.view(row, now, lastFrame, reducedMotion())
    val colors = JewelTheme.globalColors.text
    val color = when (view.mode) {
        Mode.RUNNING -> colors.info
        Mode.WAITING -> colors.warning
        Mode.NOT_RESPONDING -> colors.error
    }
    Row(
        Modifier.chatColumn().padding(top = row.gap.dp.dp)
            .semantics(mergeDescendants = true) { contentDescription = view.accessibleLabel }
            .testTag("working-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spark(animated = view.animated, tint = if (view.mode == Mode.RUNNING) TandemColors.spark else color)
        Spacer(Modifier.width(8.dp))
        Text(view.text, color = color, style = smallTextStyle())
    }
}

/**
 * Whether the IDE asks for less motion: its window animations are off (open item 5: whether that is the right flag is open, so it is
 * read here only). Then the spark stands still.
 */
@Composable
internal fun reducedMotion(): Boolean = !UISettings.getInstance().animateWindows
