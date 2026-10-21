package dev.daarkolabs.tandem.ide

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.xmlb.XmlSerializer
import dev.daarkolabs.tandem.session.ModelOption
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@TestApplication
class TandemWorkspaceStateTest {
    private val models = listOf(
        ModelOption("default", "Default (recommended)", "Sonnet 5.5", "claude-sonnet-5-5", supportsAutoMode = true, effortLevels = listOf("low", "max")),
        ModelOption("opus", "Opus", null),
    )

    @Test
    fun `the last model list is remembered, and survives the workspace file`() {
        val workspace = TandemWorkspaceState()
        assertEquals(emptyList(), workspace.knownModels())
        workspace.rememberModels(models)
        assertEquals(models, workspace.knownModels())

        val element = XmlSerializer.serialize(workspace.state)
        val reloaded = TandemWorkspaceState().apply { loadState(XmlSerializer.deserialize(element, TandemWorkspaceState.State::class.java)) }
        assertEquals(models, reloaded.knownModels())
    }

    @Test
    fun `an empty list leaves the last one, and an entry the file mangled is left out`() {
        val workspace = TandemWorkspaceState()
        workspace.rememberModels(models)
        workspace.rememberModels(emptyList())
        assertEquals(models, workspace.knownModels())
        workspace.state.knownModels.add(TandemWorkspaceState.StoredModel())
        assertEquals(models, workspace.knownModels())
    }
}
