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
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the footer's mode and model controls show and list (spec 2.3), and where their menus open. */
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
        assertNull(effortMenu(composer(models = emptyList(), effort = null)), "with neither, no Effort item")
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
        assertEquals(emptyList(), modelMenu(composer))
        assertNull(effortMenu(composer))
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
}
