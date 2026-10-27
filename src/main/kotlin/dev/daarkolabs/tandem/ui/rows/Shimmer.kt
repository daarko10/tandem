package dev.daarkolabs.tandem.ui.rows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.WorkingIndicator
import kotlinx.coroutines.delay

/** Half the width of the shimmer's light band (spec 15.1). */
private val BAND_HALF_WIDTH = 32.dp

/** The motion loops' pause outside tests: real time. */
private val realPause: suspend (Long) -> Unit = { delay(it) }

/**
 * The pause between the motion loops' frames, the shimmer's ([rememberShimmer]) and the orbs' ([Spark]): `delay`, real time. A test
 * that renders frames at chosen times provides a pause on frame time (`{ withFrameMillis { } }`), so that what it draws depends on
 * the frames it renders, never on the wall clock.
 */
internal val LocalMotionPause: ProvidableCompositionLocal<suspend (Long) -> Unit> = staticCompositionLocalOf { realPause }

/**
 * The shimmer's progress while [active] ([WorkingIndicator.shimmerProgress]): null at rest, else where the band is, 0 to 1. Its loop
 * ([runShimmer]) starts when the caller enters the composition or [active] changes, and ends when it leaves; [pause] is
 * [LocalMotionPause]'s, `delay` outside tests. Read the state only in draw ([shimmer]), so a frame redraws the layer and recomposes nothing.
 */
@Composable
internal fun rememberShimmer(active: Boolean, pause: suspend (Long) -> Unit = LocalMotionPause.current): State<Float?> {
    val progress: MutableState<Float?> = remember { mutableStateOf(null) }
    LaunchedEffect(active) { runShimmer(active, { progress.value = it }, pause) }
    return progress
}

/**
 * The shimmer's loop (spec 15.2): [set]s null and returns when not [active]. Else, from its first frame, it sleeps in [pause] until a
 * sweep starts ([WorkingIndicator.msToNextSweep]), then, while the sweep lasts, [set]s its progress on a frame and pauses
 * [WorkingIndicator.SHIMMER_FRAME_MS] in turn, at most about 30 frames a second, until the progress is null again, which it [set]s.
 * It parks only in `withFrameMillis` or [pause], so a hidden tool window, whose frames stop, costs nothing.
 */
internal suspend fun runShimmer(active: Boolean, set: (Float?) -> Unit, pause: suspend (Long) -> Unit = { delay(it) }) {
    set(null)
    if (!active) return
    val start = withFrameMillis { it }
    var elapsed = 0L
    while (true) {
        pause(WorkingIndicator.msToNextSweep(elapsed))
        while (true) {
            val progress = withFrameMillis { time ->
                elapsed = time - start
                WorkingIndicator.shimmerProgress(elapsed)
            }
            set(progress)
            if (progress == null) break
            pause(WorkingIndicator.SHIMMER_FRAME_MS)
        }
    }
}

/**
 * The shimmer over this node's drawing (spec 15.1, 15.2): only while [active], an offscreen layer in which a horizontal band of
 * [highlight], fading out to transparent on both sides over [BAND_HALF_WIDTH], is drawn `SrcAtop` over what the node drew, so only its text takes
 * the light. Its centre moves from half a band before x = 0 to half a band after [endX] as [progress] goes from 0 to 1; [endX] is
 * clamped to the node's width, and the band is drawn up to it only. [progress] and [endX] are read in draw alone; null draws nothing.
 * Put it before what it shines on (a `Text`, or a background), so that the node's own drawing is inside the layer.
 */
internal fun Modifier.shimmer(active: Boolean, progress: () -> Float?, endX: () -> Float, highlight: Color): Modifier {
    if (!active) return this
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val p = progress() ?: return@drawWithContent
        val end = endX().coerceIn(0f, size.width)
        if (end <= 0f) return@drawWithContent
        val half = BAND_HALF_WIDTH.toPx()
        val centre = -half + p * (end + 2 * half)
        drawRect(
            Brush.horizontalGradient(listOf(highlight.copy(alpha = 0f), highlight, highlight.copy(alpha = 0f)), startX = centre - half, endX = centre + half),
            size = Size(end, size.height),
            blendMode = BlendMode.SrcAtop,
        )
    }
}
