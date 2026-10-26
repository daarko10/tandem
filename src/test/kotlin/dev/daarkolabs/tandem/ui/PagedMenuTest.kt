@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import dev.daarkolabs.tandem.chat.PluginCommand
import dev.daarkolabs.tandem.chat.PluginEntry
import dev.daarkolabs.tandem.chat.PluginItemKind
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.junit.jupiter.api.Test
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.beans.PropertyChangeEvent
import javax.swing.JPanel
import javax.swing.JTextField
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `+` menu's pages and keys (desktop-parity spec 3.3, 3.5, 9.4), and the paging helper the footer menus share with it. */
@TestApplication
class PagedMenuTest {
    private val source = JPanel()

    private fun pressed(code: Int, modifiers: Int = 0, from: java.awt.Component = source): KeyEvent =
        KeyEvent(from, KeyEvent.KEY_PRESSED, 0L, modifiers, code, KeyEvent.CHAR_UNDEFINED)

    private fun released(code: Int): KeyEvent = KeyEvent(source, KeyEvent.KEY_RELEASED, 0L, 0, code, KeyEvent.CHAR_UNDEFINED)

    // spec: → does nothing on any page
    @Test
    fun `right turns no page in the plus menu`() {
        for (page in listOf(PLUS_MAIN, PLUS_PLUGINS, PLUS_CONNECTORS, PLUS_FIRST_PLUGIN, PLUS_FIRST_PLUGIN + 1)) {
            assertNull(plusMenuTurn(pressed(KeyEvent.VK_RIGHT), page), "page $page")
        }
    }

    // spec: ← goes back one page
    @Test
    fun `left goes back to the parent page`() {
        assertEquals(PLUS_MAIN, plusMenuTurn(pressed(KeyEvent.VK_LEFT), PLUS_PLUGINS))
        assertEquals(PLUS_MAIN, plusMenuTurn(pressed(KeyEvent.VK_LEFT), PLUS_CONNECTORS))
        assertEquals(PLUS_PLUGINS, plusMenuTurn(pressed(KeyEvent.VK_LEFT), PLUS_FIRST_PLUGIN))
        assertEquals(PLUS_PLUGINS, plusMenuTurn(pressed(KeyEvent.VK_LEFT), PLUS_FIRST_PLUGIN + 1))
        assertNull(plusMenuTurn(pressed(KeyEvent.VK_LEFT), PLUS_MAIN))
    }

    @Test
    fun `a modifier or a release turns nothing`() {
        assertNull(plusMenuTurn(pressed(KeyEvent.VK_LEFT, KeyEvent.SHIFT_DOWN_MASK), PLUS_PLUGINS))
        assertNull(plusMenuTurn(released(KeyEvent.VK_LEFT), PLUS_PLUGINS))
    }

    @Test
    fun `the menu's MCP opener closes the menu and opens the panel as a user action`() {
        val ui = ChatUiState()
        val state = PagedMenuState()
        state.openAt(PLUS_MAIN)
        state.openMcpFromMenu(ui)
        assertFalse(state.open)
        assertTrue(ui.mcpPanelOpen.value)
        assertTrue(ui.takeMcpStartOnOpen(), "the panel starts a session without a process")
        assertFalse(ui.takeMcpStartOnOpen(), "read once")
    }

    @Test
    fun `the plus menu is at most 360 dp wide and never wider than the column less 16 dp`() {
        assertEquals(240.dp to 360.dp, plusMenuWidth(600.dp))
        assertEquals(240.dp to 284.dp, plusMenuWidth(300.dp))
        assertEquals(184.dp to 184.dp, plusMenuWidth(200.dp))
    }

    // spec: the + menu takes keys from its own panel alone
    @Test
    fun `the plus menu takes keys from its own panel alone and closes when the focus leaves it`() {
        val panel = JPanel()
        val field = JTextField().also(panel::add)
        val editor = JTextField().also(JPanel()::add)
        val state = PagedMenuState()
        state.openAt(PLUS_PLUGINS)
        val dispatcher = pagedMenuDispatcher(state, panel, ::plusMenuTurn, digitPick = null)
        assertFalse(dispatcher.dispatchKeyEvent(pressed(KeyEvent.VK_LEFT, from = editor)), "a key typed in another component is left alone")
        assertEquals(PLUS_PLUGINS, state.page)
        assertTrue(dispatcher.dispatchKeyEvent(pressed(KeyEvent.VK_LEFT, from = field)))
        assertEquals(PLUS_MAIN, state.page)
        assertTrue(state.open)
        assertFalse(dispatcher.dispatchKeyEvent(pressed(KeyEvent.VK_1, from = field)), "the + menu takes no digits")

        // The listener PagedMenuKeys registers: a real move out of the panel closes the menu.
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listener = focusLeftListener(panel, { editor }) { state.close() }
        listener.propertyChange(PropertyChangeEvent(focusManager, "focusOwner", field, editor))
        assertFalse(state.open)
        assertFalse(dispatcher.dispatchKeyEvent(pressed(KeyEvent.VK_LEFT, from = field)), "a closed menu takes no keys")
    }

    @Test
    fun `a page turn from a click holds the menu open through Jewel's close request`() {
        val state = PagedMenuState()
        state.openAt()
        state.show(PLUS_PLUGINS)
        assertFalse(state.dismiss(InputMode.Keyboard, anchorHovered = false), "the request after the click is refused")
        assertTrue(state.open)
        assertEquals(PLUS_PLUGINS, state.page)
        assertFalse(state.dismiss(InputMode.Touch, anchorHovered = true), "a click on the anchor is the anchor's")
        assertTrue(state.dismiss(InputMode.Keyboard, anchorHovered = false))
        assertFalse(state.open)
        state.openAt(PLUS_FIRST_PLUGIN)
        assertEquals(PLUS_FIRST_PLUGIN, state.page)
    }

    // spec: a switch's click holds the menu open on its page (17.2)
    @Test
    fun `hold keeps the menu open through one dismiss`() {
        val state = PagedMenuState()
        state.openAt(PLUS_CONNECTORS)
        state.hold()
        assertFalse(state.dismiss(InputMode.Keyboard, anchorHovered = false), "the request after the switch's click is refused")
        assertTrue(state.open)
        assertEquals(PLUS_CONNECTORS, state.page)
        assertTrue(state.dismiss(InputMode.Keyboard, anchorHovered = false), "the next one closes as before")
        assertFalse(state.open)
    }

    // spec: the shortcut text shows only on a menu at least 300 dp wide (17.1)
    @Test
    fun `the shortcut text shows only on a menu at least 300 dp wide`() {
        assertTrue(plusShortcutShown(360.dp))
        assertTrue(plusShortcutShown(300.dp))
        assertFalse(plusShortcutShown(284.dp))
        assertTrue(plusShortcutShown(plusMenuWidth(600.dp).second))
        assertFalse(plusShortcutShown(plusMenuWidth(300.dp).second))
    }

    @Test
    fun `the footer menus' digits pick on the first page alone and close the menu`() {
        val panel = JPanel()
        val field = JTextField().also(panel::add)
        val state = PagedMenuState()
        state.openAt()
        val picked = mutableListOf<Int>()
        val dispatcher = pagedMenuDispatcher(state, panel, { e, p -> pageTurn(e, p)?.takeIf { it in 0 until 2 } }, { picked += it; true })
        assertTrue(dispatcher.dispatchKeyEvent(pressed(KeyEvent.VK_RIGHT, from = field)))
        assertEquals(1, state.page)
        assertTrue(dispatcher.dispatchKeyEvent(pressed(KeyEvent.VK_2, from = field)), "a digit is taken on every page")
        assertEquals(emptyList(), picked, "but picks on the first page only")
        assertFalse(dispatcher.dispatchKeyEvent(pressed(KeyEvent.VK_RIGHT, from = field)), "no page past the last")
        assertTrue(dispatcher.dispatchKeyEvent(pressed(KeyEvent.VK_LEFT, from = field)))
        assertTrue(dispatcher.dispatchKeyEvent(pressed(KeyEvent.VK_2, from = field)))
        assertEquals(listOf(2), picked)
        assertFalse(state.open)
    }

    // spec: a long page scrolls and keeps the keyboard selection on screen (open item 6)
    @Test
    fun `a long page scrolls and keeps the keyboard selection on screen`() {
        val commands = (1..20).map { PluginCommand("step-$it", "/kit:step-$it", "Runs step $it of the made-up kit.", "", PluginItemKind.COMMAND) }
        val plugin = PluginEntry("kit", "kit", "kit", "1.0.0", "kit@inline", persistentListOf(), commands.toImmutableList())
        scrollsToLast(persistentListOf(plugin), PLUS_FIRST_PLUGIN, last = "/kit:step-20", back = "‹ kit", closing = null)
    }

    // spec: the ‹ row stays above the scroll and the page's closing line below it (3.3 Size)
    @Test
    fun `the back row and the closing line stay put while the rows scroll`() {
        val plugins = (1..20).map { i ->
            val command = PluginCommand("run", "/kit-$i:run", "Runs the made-up kit $i.", "", PluginItemKind.COMMAND)
            PluginEntry("kit-$i", "kit-$i", "kit-$i", "1.0.$i", "kit-$i@inline", persistentListOf(), persistentListOf(command))
        }
        scrollsToLast(
            plugins.toImmutableList(),
            PLUS_PLUGINS,
            last = "kit-20 plugin",
            back = "‹ Plugins",
            closing = "Add or manage plugins with /plugin in a terminal.",
        )
    }

    /**
     * Opens the `+` menu on [page] of [plugins] in a 300 px scene with 268 dp above the `+` (an ImageComposeScene under the bridge
     * theme), checks that the page fits under that cap with [last] out of view, presses Down until [last] has the focus, and checks
     * that [last], the [back] row and the [closing] line are all inside the page: the rows scrolled, the back row and the closing line
     * did not.
     */
    private fun scrollsToLast(plugins: ImmutableList<PluginEntry>, page: Int, last: String, back: String, closing: String?) =
        timeoutRunBlocking(kotlin.time.Duration.parse("60s")) {
            val state = PagedMenuState().apply { openAt(page) }
            val actions = PlusMenuActions({}, {}, {}, {}, {})
            // Skia's reference cleaner and Java2D's disposer start with the first rendered scene and live as long as the JVM.
            ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Reference Cleaner", "Java2D Disposer")
            withContext(Dispatchers.EDT) {
                // 300 px high at a density of 1: the `+` at the bottom, so the room above it is what the cap allows.
                val scene = ImageComposeScene(400, 300, Density(1f)) {
                    SwingBridgeTheme {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.align(Alignment.BottomStart).size(24.dp)) {
                                PlusMenu(state, plugins, true, 240.dp to 360.dp, maxHeight = 268.dp, gapPx = 8, onDismissRequest = { false }, actions)
                            }
                        }
                    }
                }
                try {
                    scene.render()
                    scene.render(16_000_000L)
                    val menu = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "plus-menu" }.boundsInRoot
                    assertTrue(menu.height <= 268f, "the page is no taller than the room above its anchor: $menu")
                    assertFalse(inside(bounds(scene, last), menu), "the last row starts out of view")
                    assertTrue(inside(textBounds(scene, back), menu), "the back row starts in view")
                    closing?.let { assertTrue(inside(textBounds(scene, it), menu), "the closing line starts in view") }
                    repeat(60) {
                        if (focused(scene) == last) return@repeat
                        for ((type, id) in listOf(KeyEventType.KeyDown to KeyEvent.KEY_PRESSED, KeyEventType.KeyUp to KeyEvent.KEY_RELEASED)) {
                            val awt = KeyEvent(source, id, 0L, 0, KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED)
                            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(key = Key.DirectionDown, type = type, nativeEvent = awt))
                        }
                        scene.render((it + 2) * 50_000_000L)
                    }
                    assertEquals(last, focused(scene), "Down reaches the last row")
                    scene.render(5_000_000_000L)
                    assertTrue(inside(bounds(scene, last), menu), "the selected last row is inside the page: ${bounds(scene, last)} in $menu")
                    assertTrue(inside(textBounds(scene, back), menu), "the back row stays above the scroll: ${textBounds(scene, back)} in $menu")
                    closing?.let {
                        assertTrue(inside(textBounds(scene, it), menu), "the closing line stays below the scroll: ${textBounds(scene, it)} in $menu")
                    }
                } finally {
                    scene.close()
                }
            }
        }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { owner -> all(owner.unmergedRootSemanticsNode) }

    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)

    /** Where the row described as [description] is laid out, unclipped: inside the page's bounds only when scrolled into view. */
    private fun bounds(scene: ImageComposeScene, description: String): Rect {
        val node = nodes(scene).first { description in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        return Rect(node.positionInRoot, node.size.toSize())
    }

    /** Where the text [text] is laid out, unclipped. */
    private fun textBounds(scene: ImageComposeScene, text: String): Rect {
        val node = nodes(scene).first { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
        return Rect(node.positionInRoot, node.size.toSize())
    }

    /** The command row whose item has the focus: the row's description, merged into its focused item. */
    private fun focused(scene: ImageComposeScene): String? = scene.semanticsOwners.flatMap { all(it.rootSemanticsNode) }
        .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
        ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun inside(row: Rect, page: Rect): Boolean = row.top >= page.top - 0.5f && row.bottom <= page.bottom + 0.5f
}
