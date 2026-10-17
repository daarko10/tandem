package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.NewSessionOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the pickers of the prompt bar and the New-session popup list, show and pick. */
class PickersTest {
    private val models = listOf(
        ModelOption("default", "Default (recommended)", "Sonnet 5.5"),
        ModelOption("sonnet", "Sonnet", null),
        ModelOption("opus", "Opus", null),
    )

    /** The permission mode picker's list keys, which are its modes. */
    private val modeKeys: List<Any> = listOf(PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS, PermissionMode.PLAN)

    @Test
    fun `the model picker lists Default model, then the models the CLI offers in its order`() {
        val entries = modelEntries(models, requested = null, active = "claude-sonnet-5-5")
        assertEquals(listOf("Default model", "Default (recommended)", "Sonnet", "Opus"), entries.map { it.label })
        assertEquals(listOf(null, "default", "sonnet", "opus"), entries.map { it.value })
    }

    @Test
    fun `without the CLI's models the model picker lists Default model and the model the CLI runs`() {
        assertEquals(
            listOf(PickerEntry<String?>("Default model", null), PickerEntry<String?>("claude-sonnet-5-5", "claude-sonnet-5-5")),
            modelEntries(emptyList(), requested = null, active = "claude-sonnet-5-5"),
        )
        assertEquals(listOf(PickerEntry<String?>("Default model", null)), modelEntries(emptyList(), requested = null, active = null))
    }

    @Test
    fun `the model picker shows the requested model, Default model for none`() {
        assertEquals(0, modelEntries(models, null, null).positionOf(null))
        assertEquals(2, modelEntries(models, "sonnet", null).positionOf("sonnet"))
        assertEquals(1, modelEntries(emptyList(), "claude-sonnet-5-5", "claude-sonnet-5-5").positionOf("claude-sonnet-5-5"))
    }

    @Test
    fun `a requested model the picker does not list is an entry of its own, which it shows`() {
        val entries = modelEntries(models, requested = "claude-opus-5-5[1m]", active = null)
        assertEquals(PickerEntry<String?>("claude-opus-5-5[1m]", "claude-opus-5-5[1m]"), entries.last())
        assertEquals(entries.lastIndex, entries.positionOf("claude-opus-5-5[1m]"))
        assertEquals(models.size + 2, entries.size)

        val withoutModels = modelEntries(emptyList(), requested = "opus", active = "claude-sonnet-5-5")
        assertEquals(listOf("Default model", "claude-sonnet-5-5", "opus"), withoutModels.map { it.label })
        assertEquals(2, withoutModels.positionOf("opus"))
    }

    @Test
    fun `the permission mode picker offers Default, Accept edits and Plan, and shows Default for none`() {
        val entries = permissionModeEntries(null)
        assertEquals(listOf("Default", "Accept edits", "Plan"), entries.map { it.label })
        assertEquals(listOf(PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS, PermissionMode.PLAN), entries.map { it.value })
        assertEquals(0, entries.positionOf(null))
        assertEquals(1, permissionModeEntries(PermissionMode.ACCEPT_EDITS).positionOf(PermissionMode.ACCEPT_EDITS))
        assertEquals(2, permissionModeEntries(PermissionMode.PLAN).positionOf(PermissionMode.PLAN))
    }

    @Test
    fun `a mode the picker does not offer is an entry of its own, by its wire name, and picking Default from it picks`() {
        for (mode in listOf(PermissionMode.BYPASS_PERMISSIONS, PermissionMode.DONT_ASK, PermissionMode.AUTO)) {
            val entries = permissionModeEntries(mode)
            assertEquals(listOf("Default", "Accept edits", "Plan", mode.wire), entries.map { it.label }, mode.wire)
            assertEquals(3, entries.positionOf(mode), mode.wire)
            // Default is not the entry shown, so picking it asks the CLI to switch.
            val keys = entries.pickerKeys()
            val pickDefault = PickerList().opened().selected(0, keys, shown = 3).list.closed(keys, shown = 3)
            assertEquals(0, pickDefault.pick, mode.wire)
        }
    }

    @Test
    fun `the list keys are the entries' values, or their positions where a value is null or shared`() {
        val entries = listOf(
            PickerEntry<String?>("Default model", null),
            PickerEntry("Sonnet", "sonnet"),
            PickerEntry("Opus", "opus"),
            PickerEntry("Opus, again", "opus"),
        )
        assertEquals(listOf<Any>(0, "sonnet", 2, 3), entries.pickerKeys())
        assertEquals(modeKeys, permissionModeEntries(null).pickerKeys())
    }

    @Test
    fun `while the list is open, moving the highlight only holds the entry, which the picker shows`() {
        var list = PickerList().opened()
        for (index in listOf(1, 2, 1)) {
            val step = list.selected(index, modeKeys, shown = 0)
            assertNull(step.pick)
            list = step.list
        }
        assertEquals(1, list.position(modeKeys, shown = 0))
    }

    @Test
    fun `closing the list picks the held entry once, and the picker shows it until the pick arrives`() {
        val held = PickerList().opened().selected(1, modeKeys, 0).list.selected(2, modeKeys, 0).list
        val closed = held.closed(modeKeys, shown = 0)
        assertEquals(2, closed.pick)
        assertEquals(2, closed.list.position(modeKeys, shown = 0))
        assertEquals(PickerList(), closed.list.settled())
        assertEquals(2, closed.list.settled().position(modeKeys, shown = 2))
    }

    @Test
    fun `Escape drops the held entry, so that closing the list picks nothing`() {
        val escaped = PickerList().opened().selected(1, modeKeys, 0).list.escaped()
        assertEquals(0, escaped.position(modeKeys, shown = 0))
        assertEquals(PickerStep(PickerList(), null), escaped.closed(modeKeys, shown = 0))
    }

    @Test
    fun `back on the entry shown, or never moved, closing the list picks nothing`() {
        val back = PickerList().opened().selected(1, modeKeys, 0).list.selected(0, modeKeys, 0).list
        assertEquals(PickerStep(PickerList(), null), back.closed(modeKeys, shown = 0))
        assertEquals(PickerStep(PickerList(), null), PickerList().opened().closed(modeKeys, shown = 0))
    }

    @Test
    fun `the held entry stays the same entry when the entries change while the list is open`() {
        val before = listOf<Any>(0, "sonnet", "opus")
        val after = listOf<Any>(0, "default", "sonnet", "opus")
        val held = PickerList().opened().selected(2, before, shown = 0).list
        assertEquals(3, held.position(after, shown = 0))
        assertEquals(3, held.closed(after, shown = 0).pick)
        // An entry that left the list is not picked.
        assertNull(held.closed(listOf<Any>(0, "sonnet"), shown = 0).pick)
    }

    @Test
    fun `a selection while the list is closed picks at once, unless it is the entry shown`() {
        assertEquals(PickerStep(PickerList(held = PermissionMode.PLAN), 2), PickerList().selected(2, modeKeys, shown = 0))
        assertEquals(PickerStep(PickerList(), null), PickerList().selected(0, modeKeys, shown = 0))
        assertEquals(PickerStep(PickerList(), null), PickerList().selected(7, modeKeys, shown = 0))
    }

    @Test
    fun `opening the list again forgets a pick on its way, and an open list does not settle`() {
        assertEquals(PickerList(open = true), PickerList(held = PermissionMode.PLAN).opened())
        val open = PickerList(open = true, held = PermissionMode.PLAN)
        assertEquals(open, open.settled())
        assertEquals(PickerList(held = PermissionMode.PLAN), PickerList(held = PermissionMode.PLAN).escaped())
    }

    @Test
    fun `the effort picker lists Default effort, then low, medium, high and max`() {
        val entries = effortEntries(null)
        assertEquals(listOf("Default effort", "low", "medium", "high", "max"), entries.map { it.label })
        assertEquals(listOf(null, "low", "medium", "high", "max"), entries.map { it.value })
        assertEquals(3, entries.positionOf("high"))
        assertEquals(0, entries.positionOf(null))
    }

    @Test
    fun `an effort the picker does not list is an entry of its own`() {
        val entries = effortEntries("xhigh")
        assertEquals(PickerEntry<String?>("xhigh", "xhigh"), entries.last())
        assertEquals(entries.lastIndex, entries.positionOf("xhigh"))
    }

    @Test
    fun `the popup starts from the project's last picks`() {
        assertEquals(NewSessionOptions("sonnet", "high", PermissionMode.PLAN), lastPicks("sonnet", "high", "plan"))
        assertEquals(NewSessionOptions(null, null, PermissionMode.ACCEPT_EDITS), lastPicks(null, null, "acceptEdits"))
        assertEquals(NewSessionOptions(), lastPicks(null, null, null))
    }

    @Test
    fun `the popup's Default permission mode is none, whether picked or remembered`() {
        assertNull(newSessionMode(PermissionMode.DEFAULT))
        assertNull(newSessionMode(null))
        assertEquals(PermissionMode.PLAN, newSessionMode(PermissionMode.PLAN))
        assertEquals(PermissionMode.ACCEPT_EDITS, newSessionMode(PermissionMode.ACCEPT_EDITS))
        assertNull(lastPicks(null, null, "default").permissionMode)
    }

    @Test
    fun `a last permission mode the popup does not offer starts as none`() {
        assertNull(lastPicks(null, null, "bypassPermissions").permissionMode)
        assertNull(lastPicks(null, null, "auto").permissionMode)
        assertNull(lastPicks(null, null, "no such mode").permissionMode)
    }
}
