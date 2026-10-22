package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * [content] with [text] as its tooltip, shown while the pointer rests on it.
 *
 * Jewel's Tooltip takes Compose's TooltipPlacement, which is still an experimental foundation API: this is the one place that opts
 * in, so that a change to it touches one function.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WithTooltip(text: String, content: @Composable () -> Unit) {
    Tooltip(tooltip = { Text(text) }, content = content)
}

/** [content] with [tooltip] as its tooltip, for one with more than a line of plain text. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WithTooltip(tooltip: @Composable () -> Unit, content: @Composable () -> Unit) {
    Tooltip(tooltip = tooltip, content = content)
}
