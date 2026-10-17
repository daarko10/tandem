package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.NewSessionOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** What the pickers of the prompt bar and the New-session popup list and show. */
class PromptBarTest {
    private val models = listOf(
        ModelOption("default", "Default (recommended)", "Sonnet 5.5"),
        ModelOption("sonnet", "Sonnet", null),
        ModelOption("opus", "Opus", null),
    )

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
    fun `the permission mode picker offers Default, Accept edits and Plan`() {
        assertEquals(listOf("Default", "Accept edits", "Plan"), PERMISSION_MODE_ENTRIES.map { it.label })
        assertEquals(
            listOf(PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS, PermissionMode.PLAN),
            PERMISSION_MODE_ENTRIES.map { it.value },
        )
    }

    @Test
    fun `the permission mode picker shows Default for none and for a mode it does not offer`() {
        assertEquals(2, PERMISSION_MODE_ENTRIES.positionOf(PermissionMode.PLAN))
        assertEquals(1, PERMISSION_MODE_ENTRIES.positionOf(PermissionMode.ACCEPT_EDITS))
        assertEquals(0, PERMISSION_MODE_ENTRIES.positionOf(null))
        for (mode in listOf(PermissionMode.BYPASS_PERMISSIONS, PermissionMode.DONT_ASK, PermissionMode.AUTO)) {
            assertEquals(0, PERMISSION_MODE_ENTRIES.positionOf(mode), mode.wire)
        }
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
    fun `a last permission mode the picker does not offer starts as none, as the picker shows it`() {
        assertEquals(null, lastPicks(null, null, "bypassPermissions").permissionMode)
        assertEquals(null, lastPicks(null, null, "auto").permissionMode)
        assertEquals(null, lastPicks(null, null, "no such mode").permissionMode)
    }
}
