package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The chat column's widest (spec 4.1). */
private val COLUMN_MAX: Dp = 768.dp

/**
 * The chat column (spec 4.1): the whole width up to 768 dp, centred when the pane is wider. Every row's root applies it; the 16 dp
 * gutters come from the list's content padding.
 */
internal fun Modifier.chatColumn(): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = COLUMN_MAX).fillMaxWidth()

/**
 * Fades the bottom [height] of the box out (spec 4.3, 4.7): opaque down to [height] above its bottom edge, transparent at it. It
 * only fades: callers clamp and clip first, so it applies to the clipped box (`heightIn(max = 160.dp).bottomFade(60.dp)`). The
 * offscreen layer is required: `DstIn` drawn into the shared surface would multiply the alpha of everything already painted there.
 */
internal fun Modifier.bottomFade(height: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val start = if (size.height > 0f) (1f - height.toPx() / size.height).coerceIn(0f, 1f) else 0f
            drawRect(Brush.verticalGradient(start to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
