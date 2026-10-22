package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
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
import dev.daarkolabs.tandem.chat.RowOptions
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
 * The working row as shown: while a running subagent's Agent row is open ([agentExpanded]) its nested rows name the step, so the row
 * shows the turn's own step ([WorkingRow.expandedStep]) in place of the subagent's progress (spec 4.9); otherwise [row] itself.
 */
internal fun shownWorkingRow(row: WorkingRow, agentExpanded: Boolean): WorkingRow =
    if (agentExpanded && row.expandedStep != null) row.copy(step = row.expandedStep) else row

/**
 * The working row while a turn runs (spec 4.9): the [Spark], then `Pondering… · 3s · 1.2k tokens`, `Waiting for you · 3s` in the
 * warning colour or `Not responding · 1m 5s` in the error colour. Once a second it reads the clock and when the process last wrote a
 * line ([lastFrameAt], read on the tick, never collected, so that a line never recomposes the row); [WorkingIndicator.view] does the
 * rest. [agentExpanded]: a running Agent row is open ([shownWorkingRow]). One node for assistive technology, described as
 * `Claude is working` (or waiting, or not responding), with the text merged in.
 */
@Composable
internal fun WorkingRowItem(row: WorkingRow, lastFrameAt: StateFlow<Long>, agentExpanded: Boolean = false) {
    var now by remember(row.key) { mutableLongStateOf(System.currentTimeMillis()) }
    var lastFrame by remember(row.key) { mutableLongStateOf(lastFrameAt.value) }
    LaunchedEffect(row.key) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
            lastFrame = lastFrameAt.value
        }
    }
    val view = workingView(row, now, lastFrame, agentExpanded)
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
 * What [WorkingRowItem] shows for [row] at [now], the process having last written a line at [lastFrame] ([WorkingIndicator.view]):
 * the spark stands still under Tandem's `Reduce motion` setting ([reducedMotion]).
 */
@Composable
internal fun workingView(row: WorkingRow, now: Long, lastFrame: Long, agentExpanded: Boolean): WorkingIndicator.View =
    WorkingIndicator.view(shownWorkingRow(row, agentExpanded), now, lastFrame, reducedMotion())

/**
 * Tandem's `Reduce motion` setting ([RowOptions.reduceMotion]), which the tool window provides ([ProvideRowMotion]); off unless
 * provided, so the spark moves.
 */
internal val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/**
 * Whether the user asked for less motion: Tandem's `Reduce motion` setting ([LocalReducedMotion]). Then the spark stands still. Not
 * the IDE's `UISettings.animateWindows`: that is the slide of unpinned tool windows (registry `ide.animate.toolwindows`), off by
 * default, which stilled the spark for everyone.
 */
@Composable
internal fun reducedMotion(): Boolean = LocalReducedMotion.current

/** Provides [options]' `Reduce motion` setting ([RowOptions.reduceMotion]) to the rows in [content], as [LocalReducedMotion]. */
@Composable
internal fun ProvideRowMotion(options: RowOptions, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides options.reduceMotion, content = content)
}
