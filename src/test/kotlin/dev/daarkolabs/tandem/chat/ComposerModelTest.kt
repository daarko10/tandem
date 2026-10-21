package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.Delta
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.StreamEvent
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.PendingPermission
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What the composer shows, derived from the state (spec 5.4). */
class ComposerModelTest {
    @Test
    fun `composer model changes only on its own fields`() {
        for (fixture in listOf("plain-reply", "agent-sync")) {
            var previous: ChatState? = null
            var compared = 0
            val (last, _) = replay(fixture) { line, state, _ ->
                val event = (fixtureLine(fixture, line) as? CliMessage.StreamEvent)?.event
                val before = previous
                if (before != null && event is StreamEvent.ContentBlockDelta && event.delta is Delta.Text) {
                    assertNotEquals(before, state)
                    assertEquals(composerModel(before), composerModel(state))
                    compared++
                }
                previous = state
            }
            assertTrue(compared > 0, fixture)
            if (fixture == "agent-sync") {
                // The shown model is Sonnet 5.5, the one init reports; the default entry resolves to Fable 5.1.
                val composer = composerModel(last)
                assertEquals("claude-fable-5-1", composer.defaultModel)
                assertEquals(listOf("low", "medium", "high", "xhigh", "max"), composer.effortLevels)
                assertEquals(true, composer.autoSupported)
            }
        }
    }

    @Test
    fun `composer model copies the state`() {
        val state = ChatState(
            busy = true,
            ended = false,
            pendingPermissions = listOf(PendingPermission("r1", "Bash", JsonObject(emptyMap()), "t1", null, emptyList())),
            models = listOf(ModelOption("opus", "Opus", null)),
            requestedModel = "opus",
            activeModel = "claude-opus-5-5",
            modelWarning = "w",
            permissionMode = PermissionMode.PLAN,
            effort = "high",
            claudeCodeVersion = "3.0.0",
            modelSwitchPending = true,
        )
        assertEquals(
            ComposerModel(
                busy = true,
                ended = false,
                cardOpen = true,
                models = kotlinx.collections.immutable.persistentListOf(ModelOption("opus", "Opus", null)),
                requestedModel = "opus",
                activeModel = "claude-opus-5-5",
                modelWarning = "w",
                permissionMode = PermissionMode.PLAN,
                effort = "high",
                claudeCodeVersion = "3.0.0",
                defaultModel = null,
                effortLevels = kotlinx.collections.immutable.persistentListOf(),
                autoSupported = false,
                modelSwitchPending = true,
            ),
            composerModel(state),
        )
        assertEquals(false, composerModel(ChatState()).cardOpen)
    }
}
