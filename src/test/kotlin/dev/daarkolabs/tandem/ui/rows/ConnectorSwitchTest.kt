@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The connector switch's colours (desktop-parity spec 17.2): on and off told apart, the knob seen on its track, in both themes. */
@TestApplication
class ConnectorSwitchTest {
    /** The new UI's panels: dark and light. */
    private val panels = mapOf(true to Color(0xFF2B2D30), false to Color(0xFFF7F8FA))

    @Test
    fun `on and off differ clearly in both themes`() {
        for ((dark, panel) in panels) {
            val on = connectorSwitchColors(on = true, dark = dark, panel = panel)
            val off = connectorSwitchColors(on = false, dark = dark, panel = panel)
            assertTrue(contrast(on.track, off.track) >= 2.0, "dark=$dark: on ${on.track} against off ${off.track}")
            val green = on.track
            assertTrue(green.green > green.red + 0.2f && green.green > green.blue + 0.2f, "dark=$dark: the on track is green: $green")
            assertEquals(Color.White, on.knob, "dark=$dark: the on knob is white")
        }
    }

    @Test
    fun `the knob stands out from its track in both themes`() {
        for ((dark, panel) in panels) {
            for (state in listOf(true, false)) {
                val c = connectorSwitchColors(on = state, dark = dark, panel = panel)
                assertTrue(contrast(c.knob, c.track) >= 2.0, "dark=$dark on=$state: knob ${c.knob} on track ${c.track}")
            }
        }
    }

    @Test
    fun `off sits close to the panel with a faint border`() {
        for ((dark, panel) in panels) {
            val off = connectorSwitchColors(on = false, dark = dark, panel = panel)
            assertNotEquals(panel, off.track, "dark=$dark: the off track is distinct from the panel")
            assertTrue(contrast(off.track, panel) < 1.4, "dark=$dark: the off track stays close to the panel: ${off.track}")
            assertTrue(off.border.alpha in 0.05f..0.3f, "dark=$dark: a faint border: ${off.border}")
            // Lighter than the panel in dark, darker in light.
            assertEquals(dark, off.track.luminance() > panel.luminance(), "dark=$dark")
            assertTrue(contrast(off.border.compositeOver(off.track), off.track) > 1.05, "dark=$dark: the border shows")
        }
    }

    @Test
    fun `the drawn switch is green with a white knob when on and a plain track when off`() = timeoutRunBlocking(60.seconds) {
        // Skia's reference cleaner and Java2D's disposer start with the first rendered scene and live as long as the JVM.
        ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Reference Cleaner", "Java2D Disposer")
        withContext(Dispatchers.EDT) {
            // At a density of 2: the on switch at x 0..56 px, the off one at 80..136 px, both 32 px high.
            val scene = ImageComposeScene(160, 40, Density(2f)) {
                SwingBridgeTheme {
                    Row {
                        ConnectorSwitch(on = true, enabled = true)
                        Spacer(Modifier.width(12.dp))
                        ConnectorSwitch(on = false, enabled = true)
                    }
                }
            }
            try {
                val bitmap = Bitmap.makeFromImage(scene.render())
                val onTrack = bitmap.getColor(8, 16)
                val onKnob = bitmap.getColor(40, 16)
                val offKnob = bitmap.getColor(96, 16)
                val offTrack = bitmap.getColor(128, 16)
                bitmap.close()
                val (r, g, b) = rgb(onTrack)
                assertTrue(g > r + 40 && g > b + 40, "the on track is green: ${hex(onTrack)}")
                assertTrue(rgb(onKnob).all { it >= 0xF0 }, "the on knob is white: ${hex(onKnob)}")
                assertNotEquals(onTrack, offTrack, "on and off tracks differ")
                assertNotEquals(offTrack, offKnob, "the off knob shows on its track")
            } finally {
                scene.close()
            }
        }
    }

    /** WCAG's contrast ratio of [a] against [b]. */
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return max(la, lb) / min(la, lb)
    }

    private fun rgb(argb: Int): List<Int> = listOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)

    private fun hex(argb: Int): String = "#%08X".format(argb)
}
