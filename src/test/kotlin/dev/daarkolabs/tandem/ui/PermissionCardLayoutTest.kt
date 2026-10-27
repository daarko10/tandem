@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, org.jetbrains.jewel.foundation.ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import dev.daarkolabs.tandem.chat.Payload
import dev.daarkolabs.tandem.chat.PermissionCard
import dev.daarkolabs.tandem.ui.cards.PermissionCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The permission card in a short tool window (spec 4.12): a long command never pushes Deny, Always allow and Allow once out of the
 * card or off the screen; its title and box scroll instead, also after Show more.
 */
@TestApplication
class PermissionCardLayoutTest {
    private val command = (1..40).joinToString("\n") { "echo \"line $it of a long generated shell command\"" }
    private val card = PermissionCard(
        requestId = "r1",
        toolUseId = "t1",
        agentDescription = null,
        verb = "Run",
        objectText = command,
        description = "Print forty lines",
        payload = Payload.Mono(command),
        canAlwaysAllow = true,
    )
    private val buttons = listOf("Deny", "Always allow for session", "Allow once")

    @Test
    fun `a long card in a 300 dp box keeps its three buttons in view`() = scene {
        Box(Modifier.fillMaxSize()) { PermissionCardView(card, moreWaiting = 0, takeFocus = { false }, {}, {}, {}) }
    }

    @Test
    fun `in the chat pane the card takes what the prompt bar leaves and keeps its buttons above it`() = scene(maxBottom = 180f) {
        ChatPaneColumn(
            Modifier.fillMaxSize(),
            chat = { Box(it) },
            card = { PermissionCardView(card, moreWaiting = 0, takeFocus = { false }, {}, {}, {}) },
        ) {
            // A prompt bar 120 dp high under a card in 300 dp: the buttons must end above its top, at 180.
            Box(Modifier.fillMaxWidth().height(120.dp))
        }
    }

    /**
     * Composes [content] in a 400 × 300 px scene at a density of 1 under the bridge theme, checks that each of [buttons] is laid out
     * whole inside the card and the scene, then clicks Show more and checks again.
     */
    private fun scene(maxBottom: Float = 300f, content: @Composable () -> Unit) = timeoutRunBlocking(60.seconds) {
        // Skia's reference cleaner and Java2D's disposer start with the first rendered scene and live as long as the JVM.
        ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Reference Cleaner", "Java2D Disposer")
        withContext(Dispatchers.EDT) {
            val scene = ImageComposeScene(400, 300, Density(1f)) { SwingBridgeTheme { content() } }
            try {
                scene.render()
                scene.render(16_000_000L)
                assertButtonsInView(scene, maxBottom, "collapsed")
                // The link's click, merged with its text.
                val more = scene.semanticsOwners.flatMap { all(it.rootSemanticsNode) }.first { node ->
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Show more" } &&
                        node.config.getOrNull(SemanticsActions.OnClick) != null
                }
                more.config[SemanticsActions.OnClick].action!!.invoke()
                scene.render(100_000_000L)
                scene.render(200_000_000L)
                assertTrue(nodes(scene).any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Show less" } })
                assertButtonsInView(scene, maxBottom, "expanded")
            } finally {
                scene.close()
            }
        }
    }

    private fun assertButtonsInView(scene: ImageComposeScene, maxBottom: Float, state: String) {
        val frame = bounds(nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "permission-card" })
        assertTrue(frame.bottom <= maxBottom + 0.5f, "$state: the card ends inside its space: $frame")
        for (label in buttons) {
            val button = bounds(nodes(scene).first { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
            assertTrue(button.height >= 20f, "$state: $label is laid out at full height: $button")
            assertTrue(button.top >= frame.top - 0.5f && button.bottom <= frame.bottom + 0.5f, "$state: $label is inside the card: $button in $frame")
            assertTrue(button.top >= 0f && button.bottom <= maxBottom + 0.5f, "$state: $label is on screen: $button")
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { owner -> all(owner.unmergedRootSemanticsNode) }

    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)

    /** Where [node] is laid out, unclipped. */
    private fun bounds(node: SemanticsNode): Rect = Rect(node.positionInRoot, node.size.toSize())
}
