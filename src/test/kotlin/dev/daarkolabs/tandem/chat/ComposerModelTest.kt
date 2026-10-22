package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.Delta
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.SlashCommand
import dev.daarkolabs.tandem.protocol.StreamEvent
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ContextUsage
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.PendingPermission
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
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

    @Test
    fun `commands and terminal names come from the state`() {
        val empty = composerModel(ChatState())
        assertEquals(persistentListOf(), empty.commands)
        assertEquals(persistentListOf(), empty.slashCommandNames)
        assertEquals(persistentSetOf(), empty.terminalCommands)

        // mcp-slash: the initialize answer (:1) lists the commands, the init (:4) the names and the terminal ones.
        val (state, _) = replay("mcp-slash", lines = Fixtures.lines("mcp-slash").take(4))
        val composer = composerModel(state)
        assertEquals(73, composer.commands.size)
        assertEquals(state.commands, composer.commands)
        assertEquals("actions", composer.commands.first().name)
        assertEquals(state.slashCommandNames, composer.slashCommandNames)
        assertEquals(73, composer.slashCommandNames.size)
        assertEquals(setOf("doctor", "color", "focus", "reload-plugins"), composer.terminalCommands)

        val synthetic = ChatState(
            commands = listOf(SlashCommand("hello", "Hi (project)")),
            slashCommandNames = listOf("hello", "mcp"),
            terminalCommands = setOf("doctor"),
        )
        val model = composerModel(synthetic)
        assertEquals(persistentListOf(SlashCommand("hello", "Hi (project)")), model.commands)
        assertEquals(persistentListOf("hello", "mcp"), model.slashCommandNames)
        assertEquals(persistentSetOf("doctor"), model.terminalCommands)
    }
}
