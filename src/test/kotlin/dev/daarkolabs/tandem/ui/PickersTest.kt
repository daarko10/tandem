package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.Modes
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

    /** Three value keys that the PickerList state tests use. */
    private val modeKeys: List<PickerKey> =
        listOf(PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS, PermissionMode.PLAN).map { PickerKey.Value(it) }

    private val plan = PickerKey.Value(PermissionMode.PLAN)

    private fun keys(vararg keys: Any): List<PickerKey> = keys.map { if (it is Int) PickerKey.Position(it) else PickerKey.Value(it) }

    @Test
    fun `the model picker lists Default model, then the models the CLI offers in its order`() {
        val entries = modelEntries(models, requested = null)
        assertEquals(listOf("Default model", "Default (recommended)", "Sonnet", "Opus"), entries.map { it.label })
        assertEquals(listOf(null, "default", "sonnet", "opus"), entries.map { it.value })
    }

    @Test
    fun `without the CLI's models the model picker lists Default model alone`() {
        assertEquals(listOf(PickerEntry<String?>("Default model", null)), modelEntries(emptyList(), requested = null))
    }

    @Test
    fun `the model picker shows the requested model, Default model for none`() {
        assertEquals(0, modelEntries(models, null).positionOf(null))
        assertEquals(2, modelEntries(models, "sonnet").positionOf("sonnet"))
        assertEquals(1, modelEntries(emptyList(), "claude-sonnet-5-5").positionOf("claude-sonnet-5-5"))
    }

    @Test
    fun `a requested model the picker does not list is an entry of its own, which it shows`() {
        val entries = modelEntries(models, requested = "claude-opus-5-5[1m]")
        assertEquals(PickerEntry<String?>("claude-opus-5-5[1m]", "claude-opus-5-5[1m]"), entries.last())
        assertEquals(entries.lastIndex, entries.positionOf("claude-opus-5-5[1m]"))
        assertEquals(models.size + 2, entries.size)

        val withoutModels = modelEntries(emptyList(), requested = "opus")
        assertEquals(listOf("Default model", "opus"), withoutModels.map { it.label })
        assertEquals(1, withoutModels.positionOf("opus"))
    }

    @Test
    fun `the new-session popup uses the footer's mode labels`() {
        val entries = permissionModeEntries(null)
        assertEquals(listOf("Default mode", "Auto", "Manual", "Accept edits", "Plan"), entries.map { it.label })
        assertEquals(
            listOf(null, PermissionMode.AUTO, PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS, PermissionMode.PLAN),
            entries.map { it.value },
        )
        assertEquals(0, entries.positionOf(null))
        assertEquals(3, permissionModeEntries(PermissionMode.ACCEPT_EDITS).positionOf(PermissionMode.ACCEPT_EDITS))
        assertEquals(4, permissionModeEntries(PermissionMode.PLAN).positionOf(PermissionMode.PLAN))
    }

    @Test
    fun `a mode the picker does not offer is an entry of its own, by its footer label, and picking Default mode from it picks`() {
        for (mode in listOf(PermissionMode.BYPASS_PERMISSIONS, PermissionMode.DONT_ASK)) {
            val entries = permissionModeEntries(mode)
            assertEquals(listOf("Default mode", "Auto", "Manual", "Accept edits", "Plan", Modes.label(mode)), entries.map { it.label }, mode.wire)
            assertEquals(5, entries.positionOf(mode), mode.wire)
            // Default mode is not the entry shown, so picking it is a pick.
            val keys = entries.pickerKeys()
            val pickDefault = PickerList().opened().selected(0, keys, shown = 5).list.closed(keys, shown = 5)
            assertEquals(0, pickDefault.pick, mode.wire)
        }
    }

    @Test
    fun `Default mode in the popup passes no permission mode`() {
        assertEquals(PickerEntry<PermissionMode?>("Default mode", null), permissionModeEntries(null)[0])
        assertNull(lastPicks(null, null, null).permissionMode)
    }

    @Test
    fun `the list keys are the entries' values, or their positions where a value is null or shared`() {
        val entries = listOf(
            PickerEntry<String?>("Default model", null),
            PickerEntry("Sonnet", "sonnet"),
            PickerEntry("Opus", "opus"),
            PickerEntry("Opus, again", "opus"),
        )
        assertEquals(keys(0, "sonnet", 2, 3), entries.pickerKeys())
        assertEquals(
            keys(0, PermissionMode.AUTO, PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS, PermissionMode.PLAN),
            permissionModeEntries(null).pickerKeys(),
        )
    }

    @Test
    fun `a value that is a number never takes another entry's position key`() {
        val entries = listOf(PickerEntry<Int?>("None", null), PickerEntry("Zero", 0), PickerEntry("One", 1))
        val keys = entries.pickerKeys()
        assertEquals(3, keys.toSet().size, keys.toString())
        assertEquals(2, PickerList().opened().selected(2, keys, shown = 0).list.position(keys, shown = 0))
    }

    @Test
    fun `a list that closes twice, or selects the same entry twice while closed, picks once`() {
        val held = PickerList().opened().selected(2, modeKeys, shown = 0).list
        val closed = held.closed(modeKeys, shown = 0)
        assertEquals(2, closed.pick)
        assertEquals(PickerStep(closed.list, null), closed.list.closed(modeKeys, shown = 0))
        val selected = PickerList().selected(2, modeKeys, shown = 0)
        assertEquals(2, selected.pick)
        assertEquals(PickerStep(selected.list, null), selected.list.selected(2, modeKeys, shown = 0))
    }

    @Test
    fun `an open list is highlighted on the held entry, else on the one shown, when the entries or the one shown change`() {
        val open = PickerList().opened()
        assertEquals(modeKeys[2], open.highlighted(modeKeys, shown = 2))
        val held = open.selected(1, modeKeys, shown = 0).list
        assertEquals(modeKeys[1], held.highlighted(modeKeys, shown = 2))
        // The held entry left the list: the one shown.
        assertEquals(modeKeys[0], held.highlighted(listOf(modeKeys[0], modeKeys[2]), shown = 0))
        assertNull(open.highlighted(emptyList(), shown = 0))
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
        val before = keys(0, "sonnet", "opus")
        val after = keys(0, "default", "sonnet", "opus")
        val held = PickerList().opened().selected(2, before, shown = 0).list
        assertEquals(3, held.position(after, shown = 0))
        assertEquals(3, held.closed(after, shown = 0).pick)
        // An entry that left the list is not picked.
        assertNull(held.closed(keys(0, "sonnet"), shown = 0).pick)
    }

    @Test
    fun `a selection while the list is closed picks at once, unless it is the entry shown`() {
        assertEquals(PickerStep(PickerList(held = plan), 2), PickerList().selected(2, modeKeys, shown = 0))
        assertEquals(PickerStep(PickerList(), null), PickerList().selected(0, modeKeys, shown = 0))
        assertEquals(PickerStep(PickerList(), null), PickerList().selected(7, modeKeys, shown = 0))
    }

    @Test
    fun `opening the list again forgets a pick on its way, and an open list does not settle`() {
        assertEquals(PickerList(open = true), PickerList(held = plan).opened())
        val open = PickerList(open = true, held = plan)
        assertEquals(open, open.settled())
        assertEquals(PickerList(held = plan), PickerList(held = plan).escaped())
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
        assertEquals(PermissionMode.AUTO, lastPicks(null, null, "auto").permissionMode)
    }

    @Test
    fun `Manual in the popup starts the session in default mode`() {
        assertEquals(PermissionMode.DEFAULT, lastPicks(null, null, "default").permissionMode)
    }

    @Test
    fun `a last permission mode the popup does not offer starts as none`() {
        assertNull(lastPicks(null, null, "bypassPermissions").permissionMode)
        assertNull(lastPicks(null, null, "no such mode").permissionMode)
    }
}
