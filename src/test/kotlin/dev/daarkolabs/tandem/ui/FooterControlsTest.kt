package dev.daarkolabs.tandem.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.daarkolabs.tandem.chat.ComposerModel
import dev.daarkolabs.tandem.chat.composerModel
import dev.daarkolabs.tandem.chat.fixtureLine
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.reduce
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.beans.PropertyChangeEvent
import javax.accessibility.Accessible
import javax.accessibility.AccessibleContext
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTextField
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the footer's mode and model controls show and list (spec 2.3), where their menus open, and which keys the menus take. */
class FooterControlsTest {
    /** The models of agent-sync's `initialize` response: its `default` entry resolves to Fable 5.1, while init reports Sonnet 5.5. */
    private val models: List<ModelOption> = reduce(ChatState(), SessionEvent.Cli(fixtureLine("agent-sync", 2))).models

    private val sonnet = "claude-sonnet-5-5"
    private val haiku45 = "claude-haiku-4-5-20251001"
    private val levels = listOf("low", "medium", "high", "xhigh", "max")

    private fun composer(
        models: List<ModelOption> = this.models,
        activeModel: String? = null,
        requestedModel: String? = null,
        modelSwitchPending: Boolean = false,
        effort: String? = null,
        permissionMode: PermissionMode? = null,
        busy: Boolean = false,
    ): ComposerModel = composerModel(
        ChatState(
            models = models,
            activeModel = activeModel,
            requestedModel = requestedModel,
            modelSwitchPending = modelSwitchPending,
            effort = effort,
            permissionMode = permissionMode,
            busy = busy,
        ),
    )

    private fun List<ModelItem>.item(label: String): ModelItem = single { it.label == label }

    @Test
    fun `the model menu lists the init's models with the default badged`() {
        // No entry stands for this model (rule 4): none is checked, so every item keeps its digit.
        val menu = modelMenu(composer(activeModel = "claude-unknown-9"))
        assertEquals(listOf("Opus 5.5", "Fable 5.1", "Sonnet 5.5", "Haiku 5.5", "Haiku 4.5"), menu.map { it.label }.take(5))
        assertEquals(12, menu.size)
        assertTrue(menu.none { it.label == "Default (recommended)" })
        assertEquals("Fable 5.1", menu.single { it.isDefault }.label)
        assertTrue(menu.none { it.checked })
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, null, null, null), menu.map { it.digit })
        assertEquals(ModelLabel("claude-unknown-9", null), modelLabel(composer(activeModel = "claude-unknown-9")), "the label reads the wire id")
    }

    @Test
    fun `the Default-badged entry sends null while it runs and its value while another model runs`() {
        assertNull(modelMenu(composer(activeModel = "claude-fable-5-1")).item("Fable 5.1").value)
        assertEquals("fable", modelMenu(composer(activeModel = sonnet)).item("Fable 5.1").value)
    }

    @Test
    fun `the shown model is checked in place of its digit`() {
        val menu = modelMenu(composer(activeModel = sonnet))
        val shown = menu.item("Sonnet 5.5")
        assertTrue(shown.checked)
        assertNull(shown.digit)
        assertEquals(1, menu.item("Opus 5.5").digit)
        assertEquals(2, menu.item("Fable 5.1").digit)
        assertEquals(4, menu.item("Haiku 5.5").digit)
        assertEquals(listOf(shown), menu.filter { it.checked })
    }

    @Test
    fun `the model label follows init's model by resolvedModel (agent-sync - Sonnet 5_5, not Fable 5_1)`() {
        assertEquals(ModelLabel("Sonnet 5.5", null), modelLabel(composer(activeModel = sonnet)))
    }

    @Test
    fun `a requested model is shown until the next init, then the running one`() {
        assertEquals("Opus 5.5", modelLabel(composer(activeModel = sonnet, requestedModel = "opus", modelSwitchPending = true)).model)
        assertEquals("Opus 5.5", modelLabel(composer(activeModel = "claude-opus-5-5", requestedModel = "opus")).model)
        assertEquals("Sonnet 5.5", modelLabel(composer(activeModel = sonnet, requestedModel = "opus")).model)
        // The Default-badged pick in flight (set_model null): the entry the default entry resolves to, never the wire id.
        val defaultPick = composer(activeModel = "claude-fable-5-1", requestedModel = null, modelSwitchPending = true)
        assertEquals("Fable 5.1", modelLabel(defaultPick).model)
        assertTrue(modelMenu(defaultPick).item("Fable 5.1").checked)
    }

    @Test
    fun `the popup's Default (recommended) pick is the Default-badged model, not an item of its own`() {
        // The new-session popup lists the CLI's own `default` entry, so a session can start with requestedModel "default".
        val beforeInit = composer(requestedModel = "default")
        assertEquals("Fable 5.1", modelLabel(beforeInit).model)
        val menu = modelMenu(beforeInit)
        assertEquals(12, menu.size)
        assertTrue(menu.none { it.label == "default" })
        assertEquals(listOf("Fable 5.1"), menu.filter { it.checked }.map { it.label })
        assertEquals("Sonnet 5.5", modelLabel(composer(activeModel = sonnet, requestedModel = "default")).model)
    }

    @Test
    fun `a session started with a model pick reads that model before its first init, Model with none`() {
        // NewSessionOptions.model: no system/init and no initialize answer yet, and no process has ever listed the models, so the
        // list is the CLI's aliases and there is no active model (spec 2.3, <Model> 3 and 4).
        assertEquals("Opus", modelLabel(composer(models = emptyList(), requestedModel = "opus")).model)
        val defaultPick = modelLabel(composer(models = emptyList(), requestedModel = "default")).model
        assertEquals("Model", defaultPick, "the CLI's own default is no pick")
        assertEquals("Model", modelLabel(composer(models = emptyList())).model)
        val running = modelLabel(composer(models = emptyList(), activeModel = sonnet, requestedModel = "opus")).model
        assertEquals("Sonnet", running, "then what the CLI runs, by its alias's name")
        assertEquals("Opus 5.5", modelLabel(composer(requestedModel = "opus")).model, "with the list, the requested entry's name")
    }

    @Test
    fun `a pick made during a turn is shown until the next init, not the reply's model`() {
        val composer = composer(busy = true, activeModel = sonnet, requestedModel = "opus", modelSwitchPending = true)
        assertEquals("Opus 5.5", modelLabel(composer).model)
        assertEquals(listOf("Opus 5.5"), modelMenu(composer).filter { it.checked }.map { it.label })
    }

    @Test
    fun `entries sharing a resolvedModel resolve to the requested one, never the default entry`() {
        assertEquals("Fable 5.1", modelLabel(composer(activeModel = "claude-fable-5-1")).model)

        val shared = listOf(ModelOption("a", "Alpha", null, "claude-x"), ModelOption("b", "Beta", null, "claude-x"))
        val beta = composer(models = shared, activeModel = "claude-x", requestedModel = "b")
        assertEquals("Beta", modelLabel(beta).model)
        assertEquals(listOf("Beta"), modelMenu(beta).filter { it.checked }.map { it.label })
        assertEquals("Alpha", modelLabel(composer(models = shared, activeModel = "claude-x")).model)
    }

    @Test
    fun `the effort submenu lists the shown model's levels`() {
        val menu = effortMenu(composer(activeModel = sonnet, effort = "high"))!!
        assertEquals("High", menu.trailing)
        assertEquals(levels, menu.items.map { it.level })
        assertEquals(listOf("Low", "Medium", "High", "Extra high", "Max"), menu.items.map { it.label })
        assertEquals(listOf("high"), menu.items.filter { it.checked }.map { it.level })
        assertTrue(menu.items.all { it.enabled })
    }

    @Test
    fun `an effort null shows the model alone, Default as the submenu's text and no check`() {
        val composer = composer(activeModel = sonnet, effort = null)
        assertEquals(ModelLabel("Sonnet 5.5", null), modelLabel(composer))
        assertEquals(
            EffortMenu("Default", listOf("Low", "Medium", "High", "Extra high", "Max").zip(levels) { label, level -> EffortItem(level, label, false, true) }),
            effortMenu(composer),
        )
    }

    @Test
    fun `a model without effort levels has no effort submenu`() {
        val composer = composer(activeModel = haiku45, effort = "high")
        assertNull(effortMenu(composer))
        assertNull(modelLabel(composer).effort)
        assertEquals("Haiku 4.5", modelLabel(composer).model)
    }

    @Test
    fun `before the models arrive a known effort lists low, medium, high and max`() {
        assertEquals(
            EffortMenu(
                "High",
                listOf(
                    EffortItem("low", "Low", false, true),
                    EffortItem("medium", "Medium", false, true),
                    EffortItem("high", "High", true, true),
                    EffortItem("max", "Max", false, true),
                ),
            ),
            effortMenu(composer(models = emptyList(), effort = "high")),
        )
        assertEquals(ModelLabel("Model", "High"), modelLabel(composer(models = emptyList(), effort = "high")), "the label shows the known effort")
        assertNull(effortMenu(composer(models = emptyList(), effort = null)), "with neither, no Effort item")
    }

    @Test
    fun `a running model no entry stands for keeps the effort in the label`() {
        // Models are listed, but none resolves to the running one (rule 4: the footer shows its wire id).
        val composer = composer(activeModel = "claude-x", effort = "high")
        assertEquals(ModelLabel("claude-x", "High"), modelLabel(composer))
        assertEquals(listOf("low", "medium", "high", "max"), effortMenu(composer)!!.items.map { it.level })
        assertNull(effortMenu(composer(activeModel = "claude-x", effort = null)), "with no effort known, no Effort item")
    }

    @Test
    fun `a level the shown model lacks stays checked and disabled`() {
        val opus46 = listOf(ModelOption("opus46", "Opus 4.6", null, "claude-opus-4-6", true, listOf("low", "medium", "high", "max")))
        val composer = composer(models = opus46, activeModel = "claude-opus-4-6", effort = "xhigh")
        assertEquals(EffortItem("xhigh", "Extra high", checked = true, enabled = false), effortMenu(composer)!!.items.last())
        assertEquals("Extra high", modelLabel(composer).effort)
    }

    @Test
    fun `the mode menu lists Auto, Manual, Accept edits and Plan, plus the session's unoffered mode checked`() {
        val menu = modeMenu(composer(activeModel = sonnet, permissionMode = PermissionMode.PLAN))
        assertEquals(listOf("Auto", "Manual", "Accept edits", "Plan"), menu.map { it.label })
        assertEquals(listOf(1, 2, 3, 4), menu.map { it.digit })
        assertEquals(listOf("Plan"), menu.filter { it.checked }.map { it.label })

        val bypass = modeMenu(composer(activeModel = sonnet, permissionMode = PermissionMode.BYPASS_PERMISSIONS))
        assertEquals(ModeItem(PermissionMode.BYPASS_PERMISSIONS, "Bypass permissions", null, true, null), bypass.last())
    }

    @Test
    fun `before the first init the mode control reads Mode and checks nothing`() {
        val composer = composerModel(ChatState())
        assertEquals("Mode", modeLabel(composer))
        assertTrue(modeMenu(composer).none { it.checked })
        assertEquals(ModelLabel("Model", null), modelLabel(composer))
        // No process has listed its models yet: the CLI's own aliases, none badged, their Default checked since nothing is picked.
        assertEquals(listOf("Default", "Opus", "Fable", "Sonnet", "Haiku"), modelMenu(composer).map { it.label })
        assertTrue(modelMenu(composer).none { it.isDefault })
        assertEquals(listOf("Default"), modelMenu(composer).filter { it.checked }.map { it.label })
        assertNull(effortMenu(composer))
    }

    @Test
    fun `a session without a list of its own labels and lists its model from the list another process gave`() {
        // Resumed without a process: the transcript's model and effort, no initialize response of its own.
        val resumed = ChatState(activeModel = "claude-opus-5-5", effort = "medium")
        val known = composerModel(resumed, known = models)
        assertEquals(ModelLabel("Opus 5.5", "Medium"), modelLabel(known))
        assertEquals(12, modelMenu(known).size)
        assertTrue(modelMenu(known).item("Opus 5.5").checked)
        assertEquals("Fable 5.1", modelMenu(known).single { it.isDefault }.label)

        // Before any process has answered: the aliases, and the raw id by its alias's name, never "claude-opus-5-5".
        val first = composerModel(resumed)
        assertEquals(ModelLabel("Opus", "Medium"), modelLabel(first))
        assertEquals(listOf("Default", "Opus", "Fable", "Sonnet", "Haiku"), modelMenu(first).map { it.label })
        assertEquals(listOf("low", "medium", "high", "max"), effortMenu(first)?.items?.map { it.level })
    }

    @Test
    fun `an alias-only menu lists the CLI's default, which sends no model and goes back to it`() {
        // A stopped session that picked Sonnet from the aliases: Sonnet checked, Default pickable, and picking it sends null.
        val sonnetPicked = composerModel(ChatState(requestedModel = "sonnet", modelSwitchPending = true))
        val menu = modelMenu(sonnetPicked)
        assertEquals(ModelItem(value = null, label = "Default", isDefault = false, checked = false, digit = 1), menu.item("Default"))
        assertTrue(menu.item("Sonnet").checked)
        assertEquals(listOf("Default"), menu.filter { it.value == null }.map { it.label })
        // Back on the default, the popup's own `default` pick included: Default checked, the aliases picked by their values.
        for (requested in listOf(null, "default")) {
            val back = modelMenu(composerModel(ChatState(requestedModel = requested)))
            assertTrue(back.item("Default").checked, "requested $requested")
            assertEquals(listOf("opus", "fable", "sonnet", "haiku"), back.filter { !it.checked }.map { it.value }, "requested $requested")
        }
        // A list that resolves its `default` entry badges that entry instead: no Default item of its own.
        assertTrue(modelMenu(composer()).all { it.isDefault || it.value != null })
    }

    @Test
    fun `an alias picked before any process listed its models keeps the effort`() {
        // A stopped session's alias-only menu: picking Sonnet shows the alias, whose effort levels are unknown, not absent.
        val composer = composerModel(ChatState(activeModel = "claude-opus-5-5", effort = "medium", requestedModel = "sonnet", modelSwitchPending = true))
        assertEquals(ModelLabel("Sonnet", "Medium"), modelLabel(composer))
        assertEquals(listOf("low", "medium", "high", "max"), effortMenu(composer)?.items?.map { it.level })
        assertEquals("Medium", effortMenu(composer)?.trailing)
    }

    @Test
    fun `a digit of a hidden mode picks nothing`() {
        // Auto is hidden for Haiku 4.5, which has no supportsAutoMode: 1 is Auto's digit, never the first item's.
        assertNull(byDigit(modeMenu(composer(permissionMode = PermissionMode.DEFAULT, activeModel = haiku45)), 1, ModeItem::digit))
        val three = listOf(ModelOption("a", "Alpha", null, "claude-a"), ModelOption("b", "Beta", null, "claude-b"), ModelOption("c", "Gamma", null, "claude-c"))
        assertNull(byDigit(modelMenu(composer(models = three)), 4, ModelItem::digit))
        assertEquals("Beta", byDigit(modelMenu(composer(models = three)), 2, ModelItem::digit)?.label)
    }

    @Test
    fun `a footer menu opens above its control, left- or right-aligned`() {
        val window = IntSize(400, 600)
        assertEquals(IntOffset(100, 258), aboveAnchor(IntRect(100, 500, 140, 523), window, IntSize(278, 234), alignEnd = false, gapPx = 8))
        assertEquals(IntOffset(194, 392), aboveAnchor(IntRect(300, 500, 360, 523), window, IntSize(166, 100), alignEnd = true, gapPx = 8))
        assertEquals(IntOffset(234, 392), aboveAnchor(IntRect(380, 500, 400, 523), window, IntSize(166, 100), alignEnd = false, gapPx = 8))
        assertEquals(IntOffset(100, 0), aboveAnchor(IntRect(100, 50, 140, 73), window, IntSize(166, 100), alignEnd = false, gapPx = 8))
    }

    /** A headless Swing source for the AWT key events the footer menus read. */
    private val source = JPanel()

    private fun pressed(keyCode: Int, modifiers: Int = 0): KeyEvent = KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)

    private fun released(keyCode: Int): KeyEvent = KeyEvent(source, KeyEvent.KEY_RELEASED, 0L, 0, keyCode, KeyEvent.CHAR_UNDEFINED)

    private fun typed(char: Char, modifiers: Int = 0): KeyEvent = KeyEvent(source, KeyEvent.KEY_TYPED, 0L, modifiers, KeyEvent.VK_UNDEFINED, char)

    @Test
    fun `a digit is read from the number row, the keypad or the typed character, never with a modifier`() {
        assertEquals(3, digitOf(pressed(KeyEvent.VK_3)))
        assertEquals(3, digitOf(pressed(KeyEvent.VK_NUMPAD3)))
        assertEquals(3, digitOf(released(KeyEvent.VK_3)), "every event of the key is taken, so the release reads the same")
        assertEquals(3, digitOf(typed('3')))
        assertEquals(1, digitOf(pressed(KeyEvent.VK_1)))
        assertEquals(9, digitOf(pressed(KeyEvent.VK_NUMPAD9)))
        assertNull(digitOf(pressed(KeyEvent.VK_0)), "0 picks nothing")
        assertNull(digitOf(pressed(KeyEvent.VK_3, KeyEvent.SHIFT_DOWN_MASK)))
        assertNull(digitOf(pressed(KeyEvent.VK_3, KeyEvent.CTRL_DOWN_MASK)))
        assertNull(digitOf(pressed(KeyEvent.VK_3, KeyEvent.ALT_DOWN_MASK)))
        assertNull(digitOf(pressed(KeyEvent.VK_3, KeyEvent.META_DOWN_MASK)))
        assertNull(digitOf(typed('3', KeyEvent.CTRL_DOWN_MASK)))
        assertNull(digitOf(typed('a')))
        assertNull(digitOf(pressed(KeyEvent.VK_RIGHT)))
    }

    @Test
    fun `the arrows turn the pages on their press alone, never with a modifier`() {
        assertEquals(1, pageTurn(pressed(KeyEvent.VK_RIGHT), 0))
        assertEquals(0, pageTurn(pressed(KeyEvent.VK_LEFT), 1))
        assertEquals(-1, pageTurn(pressed(KeyEvent.VK_LEFT), 0), "the control clamps the page to the ones it has")
        assertNull(pageTurn(released(KeyEvent.VK_RIGHT), 0))
        assertNull(pageTurn(pressed(KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK), 1))
        assertNull(pageTurn(pressed(KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK), 0))
        assertNull(pageTurn(pressed(KeyEvent.VK_RIGHT, KeyEvent.CTRL_DOWN_MASK), 0))
        assertNull(pageTurn(pressed(KeyEvent.VK_RIGHT, KeyEvent.META_DOWN_MASK), 0))
        assertNull(pageTurn(pressed(KeyEvent.VK_UP), 0))
        assertNull(pageTurn(pressed(KeyEvent.VK_3), 0))
    }

    @Test
    fun `a footer menu takes keys from its own panel alone and closes when the focus leaves it`() {
        val panel = JPanel()
        val inner = JPanel().also(panel::add)
        val field = JTextField().also(inner::add)
        val sibling = JPanel()
        val editor = JTextField().also(sibling::add)
        assertTrue(takesKey(field, panel))
        assertTrue(takesKey(panel, panel))
        assertFalse(takesKey(editor, panel), "a key typed in another component is left alone")
        assertFalse(takesKey(null, panel))
        assertFalse(focusLeft(field, field, panel))
        assertFalse(focusLeft(null, null, panel), "no focus owner: the window lost the focus, the menu stays")
        assertTrue(focusLeft(editor, editor, panel))
    }

    @Test
    fun `a footer menu stays open on skiko's accessibility focus event on macOS`() {
        val panel = JPanel()
        val field = JTextField().also(panel::add)
        val editor = JTextField().also(JPanel()::add)
        // What skiko's NativeAccessibleFocusHelper.requestMacOSFocusOnAccessible names as the new owner when a Compose node takes the
        // focus (a menu item, on hover or an arrow key): a ComposeAccessible, a Component with no parent, while AWT's focus owner stays.
        val accessible = object : Component(), Accessible {
            override fun getAccessibleContext(): AccessibleContext? = null
        }
        assertFalse(focusLeft(accessible, field, panel), "the real focus owner is still in the panel")
        assertFalse(focusLeft(accessible, null, panel))
        assertFalse(focusLeft(accessible, editor, panel), "the event names a component that does not hold the focus")
        assertTrue(focusLeft(editor, editor, panel), "a real move out of the panel")
        assertFalse(focusLeft(field, field, panel), "a real move within the panel")

        // The listener both footer menus register, sent the event exactly as skiko builds it, then a real move.
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        var owner: Component? = field
        var closed = 0
        val listener = focusLeftListener(panel, { owner }) { closed++ }
        listener.propertyChange(PropertyChangeEvent(focusManager, "focusOwner", null, accessible))
        assertEquals(0, closed, "the menu stays open")
        owner = editor
        listener.propertyChange(PropertyChangeEvent(focusManager, "focusOwner", field, editor))
        assertEquals(1, closed, "the menu closes once the focus has left the panel")
    }

    @Test
    fun `a footer menu takes no keys from a dialog its own window owns`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "a JFrame and a JDialog need a display")
        val frame = JFrame()
        try {
            val panel = JPanel().also { frame.contentPane.add(it) }
            val dialog = JDialog(frame)
            try {
                val field = JTextField().also { dialog.contentPane.add(it) }
                assertFalse(takesKey(field, panel))
                assertTrue(focusLeft(field, field, panel))
            } finally {
                dialog.dispose()
            }
        } finally {
            frame.dispose()
        }
    }

    // spec: the mode menu hint names the shortcut
    @Test
    fun `the mode menu hint names the shortcut`() {
        assertEquals("⇧⇥ to cycle", modeMenuHint("⇧⇥"))
        assertEquals("Shift+Tab to cycle", modeMenuHint("Shift+Tab"))
        assertNull(modeMenuHint(""))
        assertNull(modeMenuHint(" "))
    }

    // spec: the Accept all hint shows beside the mode name
    @Test
    fun `the Accept all hint shows beside the mode name`() {
        val manual = composer(permissionMode = PermissionMode.DEFAULT)
        assertNull(acceptAllHint(manual))
        val on = manual.copy(acceptAll = true)
        assertEquals("Accepting all edits this turn", acceptAllHint(on))
        // The mode name itself is unchanged: the hint sits beside it.
        assertEquals(modeLabel(manual), modeLabel(on))
        // It follows the state: the turn's result clears it.
        val state = reduce(ChatState(permissionMode = PermissionMode.DEFAULT), SessionEvent.AcceptAll(true))
        assertEquals("Accepting all edits this turn", acceptAllHint(composerModel(state)))
    }
}
