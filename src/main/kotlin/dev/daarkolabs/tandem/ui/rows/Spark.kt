package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.daarkolabs.tandem.chat.Orb
import dev.daarkolabs.tandem.chat.OrbIndicator
import dev.daarkolabs.tandem.ui.lineHeight
import dev.daarkolabs.tandem.ui.smallTextStyle

/** How far an orb's light centre sits up and to the left of its middle, and how far its shading spreads, in orb radii. */
private const val LIGHT_OFFSET = 0.35f
private const val SHADE_RADIUS = 1.4f

/**
 * How much lighter than the tint the light centre is, and how much darker the far edge, from 0 (the tint) to 1: barely lighter, since
 * the desktop app's orbs are flat coral discs with a soft edge, their brightest point the tint itself, without a highlight.
 */
private const val LIGHTER = 0.1f
private const val DARKER = 0.18f

/**
 * The pause between two frames of the orbs: at most about 30 a second, never every refresh of a 60 or 120 Hz display, since the
 * frame, its drawing and the panel's render all run on the EDT.
 */
private const val FRAME_PAUSE_MS = 33L

/**
 * The working row's indicator (spec 4.9.1): the desktop app's coral orbs turning in 3D ([OrbIndicator]), on a square as tall as a line
 * of the row's text, each a disc of [tint], barely lighter up and to the left of its middle and darker towards its edge, drawn far to near.
 * While [animated] a frame-clock loop moves a time that only the draw phase reads, at most about 30 times a second ([FRAME_PAUSE_MS], in [LocalMotionPause]),
 * so the row redraws but never recomposes, and in a layer of its own, so that only the indicator's small picture is drawn again, not
 * the chat's; the loop ends when the row leaves the composition. Otherwise it shows [OrbIndicator.STILL], in [tint]: the warning or
 * error colour while the row waits or the CLI is silent, and the time goes back to 0, so that a row that animates again starts the
 * cycle from its first frame. Decoration only: assistive technology reads the row's own description.
 */
@Composable
internal fun Spark(animated: Boolean, tint: Color) {
    var elapsed by remember { mutableLongStateOf(0L) }
    val pause = LocalMotionPause.current
    LaunchedEffect(animated) {
        if (animated) {
            val start = withFrameMillis { it }
            while (true) {
                withFrameMillis { elapsed = it - start }
                pause(FRAME_PAUSE_MS)
            }
        } else {
            elapsed = 0L
        }
    }
    val shades = remember(tint) { Shades(tint) }
    Spacer(
        Modifier.size(lineHeight(smallTextStyle())).clearAndSetSemantics {}.graphicsLayer().drawBehind {
            for (orb in OrbIndicator.frame(elapsed, animated)) drawOrb(orb, shades)
        },
    )
}

/** The three colours of an orb's shading in [tint]: lighter, the tint itself, darker. */
private class Shades(tint: Color) {
    val light: Color = mix(tint, 1f, LIGHTER)
    val middle: Color = tint
    val dark: Color = mix(tint, 0f, DARKER)

    /** [color] moved [amount] of the way towards the channel value [towards] (1 lighter, 0 darker), keeping its alpha. */
    private fun mix(color: Color, towards: Float, amount: Float): Color = Color(
        red = color.red + (towards - color.red) * amount,
        green = color.green + (towards - color.green) * amount,
        blue = color.blue + (towards - color.blue) * amount,
        alpha = color.alpha,
    )
}

/** [orb] on this square: a disc whose radial shading has its lightest point up and to the left, at the orb's alpha. */
private fun DrawScope.drawOrb(orb: Orb, shades: Shades) {
    val side = size.minDimension
    val radius = orb.radius * side
    val center = Offset(orb.x * side, orb.y * side)
    val light = center - Offset(radius * LIGHT_OFFSET, radius * LIGHT_OFFSET)
    val brush = Brush.radialGradient(
        0f to shades.light,
        0.5f to shades.middle,
        1f to shades.dark,
        center = light,
        radius = radius * SHADE_RADIUS,
    )
    drawCircle(brush, radius = radius, center = center, alpha = orb.alpha)
}
