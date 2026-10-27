package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme

/** A switch's colours: its [track], the faint [border] drawn round the track ([Color.Transparent] for none) and its [knob]. */
internal data class SwitchColors(val track: Color, val border: Color, val knob: Color)

/**
 * The colours of a connector switch that is [on] in a [dark] or light theme whose panel is [panel]: on, a success green track
 * (#3FB950 dark, #2DA44E light) with a white knob; off, a track just distinct from the panel (lightened in dark, darkened in light)
 * with a faint border and a muted grey knob. Jewel's own tokens gave no pair readable in both themes: `borders.focused` against
 * `borders.normal` read as two near-black pills in the dark theme, the knob (`panelBackground`) vanishing into them.
 */
internal fun connectorSwitchColors(on: Boolean, dark: Boolean, panel: Color): SwitchColors = when {
    on && dark -> SwitchColors(Color(0xFF3FB950), Color.Transparent, Color.White)
    on -> SwitchColors(Color(0xFF2DA44E), Color.Transparent, Color.White)
    dark -> SwitchColors(lerp(panel, Color.White, 0.10f), Color.White.copy(alpha = 0.16f), Color(0xFF8B8E94))
    else -> SwitchColors(lerp(panel, Color.Black, 0.07f), Color.Black.copy(alpha = 0.14f), Color(0xFF8F949C))
}

/**
 * A connector row's on/off switch (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 17.2): Jewel 2026.1.5 has none. A
 * rounded 28 × 16 dp track and a 12 dp knob in [connectorSwitchColors], the knob at the track's end when [on] and its start when off,
 * all at 40 % alpha unless [enabled]. Drawn only: the row it sits in carries the switch's semantics. Nothing animates.
 */
@Composable
internal fun ConnectorSwitch(on: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = connectorSwitchColors(on, JewelTheme.isDark, JewelTheme.globalColors.panelBackground)
    Canvas(modifier.size(TRACK_WIDTH, TRACK_HEIGHT).alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        val radius = size.height / 2
        drawRoundRect(colors.track, cornerRadius = CornerRadius(radius, radius))
        if (colors.border.alpha > 0f) {
            val stroke = 1.dp.toPx()
            drawRoundRect(
                colors.border,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(radius - stroke / 2, radius - stroke / 2),
                style = Stroke(stroke),
            )
        }
        val knobRadius = KNOB.toPx() / 2
        val inset = radius - knobRadius
        val atEnd = on != (layoutDirection == LayoutDirection.Rtl)
        val x = if (atEnd) size.width - inset - knobRadius else inset + knobRadius
        drawCircle(colors.knob, radius = knobRadius, center = Offset(x, radius))
    }
}

private val TRACK_WIDTH = 28.dp
private val TRACK_HEIGHT = 16.dp
private val KNOB = 12.dp
private const val DISABLED_ALPHA = 0.4f
