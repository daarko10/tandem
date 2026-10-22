package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.Delta
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.StreamEvent
import dev.daarkolabs.tandem.session.CLI_MODEL_ALIASES
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ContextUsage
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.PendingPermission
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.reduce
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the composer shows, derived from the state (spec 5.4). */
class ComposerModelTest {
    /** The models of model-switch's `initialize` response: `opus` is "Opus" and resolves to claude-opus-5-5, `default` to Sonnet. */
    private val listed: List<ModelOption> = reduce(ChatState(), SessionEvent.Cli(fixtureLine("model-switch", 4))).models

    @Test
    fun `a resumed session with no turns and no live process offers the models and shows a display name`() {
        // Opened without a process (restored with the project, or over the cap): no initialize response of its own, only the model
        // and the effort its transcript recorded.
        val resumed = ChatState(activeModel = "claude-opus-5-5", effort = "medium")
        assertEquals(emptyList(), resumed.models)

        val composer = composerModel(resumed, known = listed)
        assertEquals(listed, composer.models, "the list the last process to answer gave")
        val shown = shownModel(composer.models, composer.requestedModel, composer.activeModel, composer.modelSwitchPending)
        assertEquals("Opus", shown?.displayName)
        assertEquals("claude-sonnet-5-5", composer.defaultModel)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), composer.effortLevels)
        assertEquals(true, composer.autoSupported)

        // A new session that was never sent a prompt has no transcript at all: the default model's entry stands for it.
        val fresh = composerModel(ChatState(), known = listed)
        assertEquals("Sonnet", shownModel(fresh.models, fresh.requestedModel, fresh.activeModel, fresh.modelSwitchPending)?.displayName)

        // Once its own process answers, its own list wins.
        val own = listOf(ModelOption("opus", "Opus 6", null, "claude-opus-5-5"))
        assertEquals(own, composerModel(resumed.copy(models = own), known = listed).models)
    }

    @Test
    fun `before any process has listed its models the CLI's own aliases are offered`() {
        val composer = composerModel(ChatState(activeModel = "claude-opus-5-5"))
        assertEquals(CLI_MODEL_ALIASES, composer.models)
        assertEquals(listOf("default", "opus", "sonnet", "haiku"), composer.models.map { it.value })
        assertEquals("Opus", modelDisplayName("claude-opus-5-5", composer.models))
    }

    @Test
    fun `a raw model id maps to the list's display name`() {
        assertEquals("Opus", modelDisplayName("claude-opus-5-5", listed))
        assertEquals("Haiku", modelDisplayName("claude-haiku-5-5", listed))
        assertEquals("Opus", modelDisplayName("opus", listed), "an entry's own value")
        // Never the `default` entry's "Default (recommended)", though it resolves to Sonnet too.
        assertEquals("Sonnet", modelDisplayName("claude-sonnet-5-5", listed))
        // An entry that names its model stands for that model only: another Opus keeps its wire id.
        assertEquals("claude-opus-4-1-20250805", modelDisplayName("claude-opus-4-1-20250805", listed))
        // An alias, which names no model, stands for the models of its family.
        assertEquals("Opus", modelDisplayName("claude-opus-4-1-20250805", CLI_MODEL_ALIASES))
        assertEquals("Sonnet", modelDisplayName("claude-3-5-sonnet-20241022", CLI_MODEL_ALIASES))
        assertEquals("claude-unknown-9", modelDisplayName("claude-unknown-9", CLI_MODEL_ALIASES))
        assertEquals("claude-unknown-9", modelDisplayName("claude-unknown-9", emptyList()))
    }

    @Test
    fun `composer model changes only on its own fields`() {
        for (fixture in listOf("plain-reply", "agent-sync")) {
            var previous: ChatState? = null
            var compared = 0
            var starts = 0
            val (last, _) = replay(fixture) { line, state, _ ->
                val message = fixtureLine(fixture, line) as? CliMessage.StreamEvent
                val event = message?.event
                val before = previous
                if (before != null && event is StreamEvent.ContentBlockDelta && event.delta is Delta.Text) {
                    assertNotEquals(before, state)
                    assertEquals(composerModel(before), composerModel(state))
                    compared++
                }
                // A main-thread message_start moves the context ring alone: the composer model has no context field.
                if (fixture == "plain-reply" && before != null && event is StreamEvent.MessageStart && message.parentToolUseId == null) {
                    assertNotEquals(before.context, state.context)
                    assertEquals(composerModel(before), composerModel(state))
                    starts++
                }
                previous = state
            }
            assertTrue(compared > 0, fixture)
            if (fixture == "plain-reply") assertTrue(starts > 0, fixture)
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
    fun `context value and fraction`() {
        val usage = ContextUsage(75_600, 1_000_000)
        assertEquals("75.6k / 1M (8%)", ContextFormat.value(usage))
        assertEquals(0.0756f, assertNotNull(ContextFormat.fraction(usage)), 1e-6f)
        assertEquals("312 / 200k (0%)", ContextFormat.value(ContextUsage(312, 200_000)))
        assertEquals(1f, ContextFormat.fraction(ContextUsage(250_000, 200_000)))
        // Either part unknown: no value and no fraction, so the ring stays hidden.
        for (unknown in listOf(ContextUsage(5, null), ContextUsage(null, 1_000_000))) {
            assertNull(ContextFormat.value(unknown), unknown.toString())
            assertNull(ContextFormat.fraction(unknown), unknown.toString())
        }
        assertNull(ContextFormat.fraction(ContextUsage(5, 0)))
    }

    @Test
    @DisplayName("the window is written 200k, 1M, 1.5M")
    fun `the window is written 200k, 1M, 1_5M`() {
        assertEquals("200k", ContextFormat.formatWindow(200_000))
        assertEquals("1M", ContextFormat.formatWindow(1_000_000))
        assertEquals("1.5M", ContextFormat.formatWindow(1_500_000))
        // formatTokens would give these as "200.0k" and "1.0m".
        assertEquals("200.0k", formatTokens(200_000))
        assertEquals("1.0m", formatTokens(1_000_000))
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
                models = persistentListOf(ModelOption("opus", "Opus", null)),
                requestedModel = "opus",
                activeModel = "claude-opus-5-5",
                modelWarning = "w",
                permissionMode = PermissionMode.PLAN,
                effort = "high",
                claudeCodeVersion = "3.0.0",
                defaultModel = null,
                effortLevels = persistentListOf(),
                autoSupported = false,
                modelSwitchPending = true,
            ),
            composerModel(state),
        )
        assertEquals(false, composerModel(ChatState()).cardOpen)
    }
}
