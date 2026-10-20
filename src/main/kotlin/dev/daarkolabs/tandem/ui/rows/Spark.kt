package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/** The spark's glyphs, played forward then back so that it grows and shrinks (spec 4.9). */
private val GLYPHS: List<String> = listOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢")

/** The glyph shown when the spark stands still: `✻`. */
private const val STILL: Int = 4

/** One step of the cycle. */
private const val STEP_MS: Long = 120

/**
 * The working row's glyph (spec 4.9, decision 7): cycles [GLYPHS] one step every 120 ms while [animated], else stands at `✻`. A leaf,
 * so that only it recomposes on each step; the loop ends when the row leaves the screen. Decoration only: assistive technology reads
 * the row's own description.
 */
@Composable
internal fun Spark(animated: Boolean, tint: Color) {
    var index by remember { mutableIntStateOf(STILL) }
    LaunchedEffect(animated) {
        if (animated) {
            while (true) {
                delay(STEP_MS)
                index = (index + 1) % GLYPHS.size
            }
        } else {
            index = STILL
        }
    }
    Text(
        GLYPHS[index],
        color = tint,
        style = JewelTheme.editorTextStyle,
        modifier = Modifier.width(16.dp).clearAndSetSemantics {},
        textAlign = TextAlign.Center,
    )
}
