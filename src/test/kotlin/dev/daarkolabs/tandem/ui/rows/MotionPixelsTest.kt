@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import dev.daarkolabs.tandem.chat.RowGap
import dev.daarkolabs.tandem.chat.ThinkingRow
import dev.daarkolabs.tandem.chat.WorkingRow
import dev.daarkolabs.tandem.ui.ChatUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What the working row draws in motion (spec 15, Review Focus 10): 0.1.2's orbs ([Spark]) and the shimmer's band, rendered headlessly
 * with Compose's [ImageComposeScene] at two frame times, compared per region. The orbs' frames themselves are `OrbIndicatorTest`'s;
 * this pins that the composed row draws both moving, and both still under Reduce motion.
 */
@TestApplication
class MotionPixelsTest {
    private val density = 2f

    /** A pixel region of a rendered frame, ARGB per pixel. */
    private data class Region(val pixels: List<Int>) {
        fun hasNonBlack(): Boolean = pixels.any { (it and 0xFFFFFF) != 0 }

        override fun toString(): String = "Region(${pixels.size} px, ${pixels.distinct().size} colours, hash ${pixels.hashCode()})"
    }

    @Test
    fun `the orbs and the band move in the drawn row`() {
        val (orbs0, orbs1000, box0, box1050) = drawnRow(reduce = false)
        assertNotEquals(orbs0, orbs1000, "the orbs move: frame(1000), the spread ring, against frame(0)")
        assertNotEquals(box0, box1050, "the band crosses the box mid-sweep")
        assertTrue(box1050.hasNonBlack(), "the band lights the black box")
    }

    @Test
    fun `under Reduce motion the drawn row stands still`() {
        val (orbs0, orbs1000, box0, box1050) = drawnRow(reduce = true)
        assertEquals(orbs0, orbs1000, "the orbs show OrbIndicator.STILL throughout")
        assertEquals(box0, box1050, "no band")
    }

    @Test
    fun `the working and thinking rows draw them`() {
        val moving = themedRows(reduce = false)
        assertNotEquals(moving[0], moving[1], "the working row's orbs move")
        assertNotEquals(moving[2], moving[3], "the band crosses the working row's step")
        assertNotEquals(moving[4], moving[5], "the band crosses the streaming thinking row's label")
        val still = themedRows(reduce = true)
        assertEquals(still[0], still[1], "under Reduce motion the working row's orbs stand still")
        assertEquals(still[2], still[3], "under Reduce motion the working row's step does not shimmer")
        assertEquals(still[4], still[5], "under Reduce motion the thinking row's label does not shimmer")
    }

    /**
     * What [WorkingRowItem] draws in motion, driven by one `workingView`: the orbs' square at 0 and 1 000 ms, and a black box under the
     * shimmer (the black inside the shimmer's layer, as the row's Text is) at 0 and 1 050 ms (600 + 450, mid-sweep).
     */
    private fun drawnRow(reduce: Boolean): List<Region> = scene(width = 320, height = 48, content = {
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            LocalReducedMotion provides reduce,
        ) {
            val now = System.currentTimeMillis()
            val row = WorkingRow(turnUuid = "u1", startedAtMillis = now - 3_000, step = "Pondering", outputTokens = 0, waiting = false)
            val view = workingView(row, now, now, agentExpanded = false)
            val s = rememberShimmer(view.shimmerEnd > 0) { withFrameMillis { } }
            val end = with(LocalDensity.current) { 100.dp.toPx() }
            Row(Modifier.background(Color.White)) {
                Box(Modifier.size(24.dp)) { Spark(view.animated, Color.Black) }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(120.dp, 20.dp).shimmer(view.shimmerEnd > 0, { s.value }, { end }, Color.White).background(Color.Black))
            }
        }
    }) { render, _ ->
        val orbs = Rect(0f, 0f, 48f, 48f)
        val box = Rect(64f, 0f, 304f, 40f)
        val at0 = render(0L, 0L)
        val at1000 = render(1_000L, 0L)
        val at1050 = render(1_050L, 0L)
        listOf(region(at0, orbs), region(at1000, orbs), region(at0, box), region(at1050, box))
    }

    /**
     * [WorkingRowItem] on a running row and [ThinkingRowItem] on a streaming one themselves, under the bridge theme, with their real
     * `delay`s: the orbs' square of the working row at 0 and 1 000 ms, its step and the thinking row's label at 0 and 1 050 ms.
     */
    private fun themedRows(reduce: Boolean): List<Region> {
        val now = System.currentTimeMillis()
        val working = WorkingRow(turnUuid = "u1", startedAtMillis = now - 3_000, step = "Pondering", outputTokens = 0, waiting = false)
        val thinking = ThinkingRow("think:1", "Thinking… · 50 tokens", summary = "", streaming = true, gap = RowGap.NONE)
        val ui = ChatUiState()
        return scene(width = 600, height = 200, content = {
            SwingBridgeTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduce) {
                    Column {
                        WorkingRowItem(working.copy(gap = RowGap.NONE), MutableStateFlow(now))
                        ThinkingRowItem(thinking, ui)
                    }
                }
            }
        }) { render, scene ->
            val first = render(0L, 0L)
            val workingRow = bounds(scene) { it.config.getOrNull(SemanticsProperties.TestTag) == "working-row" }
            val thinkingRow = bounds(scene) { "Claude, thinking" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            val side = workingRow.height
            val orbs = Rect(workingRow.left, workingRow.top, workingRow.left + side, workingRow.bottom)
            val step = Rect(workingRow.left + side + 8 * density, workingRow.top, workingRow.left + side + 48 * density, workingRow.bottom)
            val label = Rect(thinkingRow.left, thinkingRow.top, thinkingRow.left + 40 * density, thinkingRow.bottom)
            val at1000 = render(1_000L, 0L)
            // The shimmers' first sweep starts after a real delay of 600 ms here: wait for it before the mid-sweep frame.
            val at1050 = render(1_050L, 700L)
            listOf(
                region(first, orbs), region(at1000, orbs),
                region(first, step), region(at1050, step),
                region(first, label), region(at1050, label),
            )
        }
    }

    /**
     * Composes [content] in a [width] by [height] px scene at [density] on the EDT and runs [check] with a `render(t, waitMs)` that
     * waits [waitMs] of real time, then renders frame time t three times with 50 ms of real time before each ([Spark] keeps its own
     * real `delay(33)`), and returns the last frame.
     */
    private fun <T> scene(width: Int, height: Int, content: @Composable () -> Unit, check: suspend (suspend (Long, Long) -> Image, ImageComposeScene) -> T): T {
        var result: T? = null
        timeoutRunBlocking(60.seconds) {
            // Skia's reference cleaner and Java2D's disposer start with the first rendered scene and live as long as the JVM.
            ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Reference Cleaner", "Java2D Disposer")
            withContext(Dispatchers.EDT) {
                val scene = ImageComposeScene(width, height, Density(density), content = content)
                try {
                    result = check({ ms, waitMs ->
                        delay(waitMs)
                        var image: Image? = null
                        repeat(3) {
                            delay(50)
                            image = scene.render(ms * 1_000_000)
                        }
                        image!!
                    }, scene)
                } finally {
                    scene.close()
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** The ARGB pixels of [image] inside [rect]. */
    private fun region(image: Image, rect: Rect): Region {
        val bitmap = Bitmap.makeFromImage(image)
        val pixels = ArrayList<Int>()
        for (y in rect.top.toInt() until rect.bottom.toInt().coerceAtMost(bitmap.height)) {
            for (x in rect.left.toInt() until rect.right.toInt().coerceAtMost(bitmap.width)) pixels += bitmap.getColor(x, y)
        }
        bitmap.close()
        return Region(pixels)
    }

    /** Where the first node that matches [which] is laid out, in px. */
    private fun bounds(scene: ImageComposeScene, which: (SemanticsNode) -> Boolean): Rect {
        fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
        return scene.semanticsOwners.flatMap { all(it.unmergedRootSemanticsNode) }.first(which).boundsInRoot
    }
}
