package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * A connector row's on/off switch (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 17.2): Jewel 2026.1.5 has none. A
 * rounded 28 × 16 dp track in the theme's accent when [on] (`globalColors.borders.focused`: Jewel's checkbox style carries no fill
 * token, its checked fill is the platform's `checkBox.svg` palette) and in the normal border colour when off, a 12 dp knob in the
 * panel background at the track's end when on and its start when off, all at 40 % alpha unless [enabled]. Drawn only: the row it sits
 * in carries the switch's semantics. Nothing animates.
 */
@Composable
internal fun ConnectorSwitch(on: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = JewelTheme.globalColors
    val track = if (on) colors.borders.focused else colors.borders.normal
    val knob = colors.panelBackground
    Canvas(modifier.size(TRACK_WIDTH, TRACK_HEIGHT).alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        val radius = size.height / 2
        drawRoundRect(track, cornerRadius = CornerRadius(radius, radius))
        val knobRadius = KNOB.toPx() / 2
        val inset = radius - knobRadius
        val atEnd = on != (layoutDirection == LayoutDirection.Rtl)
        val x = if (atEnd) size.width - inset - knobRadius else inset + knobRadius
        drawCircle(knob, radius = knobRadius, center = Offset(x, radius))
    }
}

private val TRACK_WIDTH = 28.dp
private val TRACK_HEIGHT = 16.dp
private val KNOB = 12.dp
private const val DISABLED_ALPHA = 0.4f
