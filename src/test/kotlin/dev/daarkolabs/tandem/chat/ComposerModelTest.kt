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
        var previous: ChatState? = null
        var compared = 0
        replay("plain-reply") { line, state, _ ->
            val event = (fixtureLine("plain-reply", line) as? CliMessage.StreamEvent)?.event
            val before = previous
            if (before != null && event is StreamEvent.ContentBlockDelta && event.delta is Delta.Text) {
                assertNotEquals(before, state)
                assertEquals(composerModel(before), composerModel(state))
                compared++
            }
            previous = state
        }
        assertTrue(compared > 0)
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
            ),
            composerModel(state),
        )
        assertEquals(false, composerModel(ChatState()).cardOpen)
    }
}
