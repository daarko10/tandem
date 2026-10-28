@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, org.jetbrains.jewel.foundation.ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import dev.daarkolabs.tandem.chat.SlashPalette
import dev.daarkolabs.tandem.chat.slashPalette
import dev.daarkolabs.tandem.protocol.SlashCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The palette's description box, drawn (MCP spec 14): a long description is shown whole, on as many lines as it needs, beside the list
 * when the composer is wide and under it when it is narrow.
 */
@TestApplication
class SlashPaletteDescriptionTest {
    private val description = "Review the current branch's diff against main for correctness bugs, security problems, missing tests " +
        "and anything else a careful reviewer would flag before merging."
    private val palette = slashPalette("/", listOf(SlashCommand("review", description)), emptyList(), emptySet())!!

    @Test
    fun `beside the list a long description is wider and shows every line`() {
        val layout = drawnDescription(boxWidthPx = 780)
        assertTrue(layout.size.width > 260, "the box grows past the old 260 dp: ${layout.size}")
        assertTrue(layout.lineCount > 1, "lines: ${layout.lineCount}")
        assertFalse(layout.hasVisualOverflow, "nothing is cut: ${layout.lineCount} lines")
    }

    @Test
    fun `a description longer than the box scrolls instead of being cut`() {
        val long = List(12) { description }.joinToString(" ")
        val longPalette = slashPalette("/", listOf(SlashCommand("review", long)), emptyList(), emptySet())!!
        // The list (one row here) has a vertical scroll range too; the description's is the one that can move.
        var ranges: List<ScrollAxisRange> = emptyList()
        val layout = drawnDescription(boxWidthPx = 780, palette = longPalette, text = long) { nodes ->
            ranges = nodes.mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
        }
        assertTrue(layout.lineCount > 8, "longer than the box: ${layout.lineCount} lines")
        assertFalse(layout.hasVisualOverflow, "laid out whole, not cut with an ellipsis: ${layout.lineCount} lines")
        assertTrue(ranges.any { it.maxValue() > 0f }, "the box scrolls to the rest: ${ranges.map { it.maxValue() }}")
    }

    @Test
    fun `under the list a long description shows more than two lines`() {
        val layout = drawnDescription(boxWidthPx = 300)
        assertTrue(layout.lineCount > 2, "more than two lines: ${layout.lineCount}")
        assertFalse(layout.hasVisualOverflow, "nothing is cut: ${layout.lineCount} lines")
    }

    /** The description's text layout in the palette drawn above a composer box [boxWidthPx] wide, in an 800 × 400 px scene. */
    private fun drawnDescription(
        boxWidthPx: Int,
        palette: SlashPalette = this.palette,
        text: String = description,
        inspect: (List<SemanticsNode>) -> Unit = {},
    ): TextLayoutResult {
        var result: TextLayoutResult? = null
        timeoutRunBlocking(60.seconds) {
            // Skia's reference cleaner and Java2D's disposer start with the first rendered scene and live as long as the JVM.
            ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Reference Cleaner", "Java2D Disposer")
            withContext(Dispatchers.EDT) {
                val scene = ImageComposeScene(800, 400, Density(1f)) {
                    SwingBridgeTheme {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.align(Alignment.BottomStart).size(24.dp)) {
                                SlashPaletteView(palette, boxWidthPx, maxHeightPx = 360, onPick = {})
                            }
                        }
                    }
                }
                try {
                    scene.render()
                    scene.render(16_000_000L)
                    inspect(nodes(scene))
                    val node = nodes(scene).first { node ->
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text in it.text }
                    }
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    result = layouts.single()
                } finally {
                    scene.close()
                }
            }
        }
        return result!!
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { owner -> all(owner.unmergedRootSemanticsNode) }

    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
}
