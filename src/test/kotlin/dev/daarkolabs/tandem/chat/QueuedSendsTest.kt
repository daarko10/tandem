package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.SlashCommand
import dev.daarkolabs.tandem.session.PendingSend
import dev.daarkolabs.tandem.session.PendingStage
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sending while Claude works (desktop-parity spec 12.1, 12.2, 12.4): which sends go `later`, what a slash command is, the placeholder,
 * the refusals and the captions. Written in D8's recorded branches (probes 5.1): `queue-later-command` ran the later `/context` after
 * the turn and the later image message ran, so both clauses of [sendsLater] hold and neither refusal of a command or an image applies.
 */
class QueuedSendsTest {
    private val idle = ComposerModel(
        busy = false,
        ended = false,
        cardOpen = false,
        models = persistentListOf(),
        requestedModel = null,
        activeModel = null,
        modelWarning = null,
        permissionMode = null,
        effort = null,
        claudeCodeVersion = null,
    )
    private val starting = idle.copy(busy = true)
    private val noCapabilities = starting.copy(capabilitiesKnown = true)
    private val working = noCapabilities.copy(midTurnSends = true)

    @Test
    fun `sendsLater is busy with the queue key, a slash command or an image`() {
        assertFalse(sendsLater(command = false, imageCount = 0, busy = false, queueKey = true))
        assertFalse(sendsLater(command = false, imageCount = 0, busy = true, queueKey = false))
        assertTrue(sendsLater(command = false, imageCount = 0, busy = true, queueKey = true))
        // D8's after-the-turn outcome for queue-later-command: a busy slash command goes later.
        assertTrue(sendsLater(command = true, imageCount = 0, busy = true, queueKey = false))
        // D8's runs outcome for its image message: a busy draft with images goes later.
        assertTrue(sendsLater(command = false, imageCount = 1, busy = true, queueKey = false))
        assertFalse(sendsLater(command = true, imageCount = 0, busy = false, queueKey = false))
        assertFalse(sendsLater(command = false, imageCount = 2, busy = false, queueKey = false))
    }

    @Test
    fun `a slash command is a palette name, not any leading slash`() {
        val model = working.copy(
            commands = persistentListOf(
                SlashCommand("usage", aliases = listOf("cost")),
                SlashCommand("probe-kit:greet"),
            ),
        )
        assertTrue(isSlashCommand("/usage", model))
        assertTrue(isSlashCommand("/probe-kit:greet Ada", model))
        assertTrue(isSlashCommand("  /usage\n", model))
        assertTrue(isSlashCommand("/cost", model), "an alias names the command too")
        assertTrue(isSlashCommand("/mcp", model), "Tandem's row")
        assertFalse(isSlashCommand("/src/app.kt fails to compile", model))
        assertFalse(isSlashCommand("/ ", model))
        assertFalse(isSlashCommand("/", model))
        assertFalse(isSlashCommand("usage", model))
        assertFalse(isSlashCommand("/nosuch", model))
        // Before the catalogue, the init's names count.
        assertTrue(isSlashCommand("/compact now", idle.copy(slashCommandNames = persistentListOf("compact"))))
        // So a busy Enter on a path sends it now as a steer: never later, and never refused.
        val path = "/src/app.kt fails to compile"
        assertFalse(sendsLater(isSlashCommand(path, model), imageCount = 0, busy = true, queueKey = false))
        assertNull(busyRefusal(model, command = isSlashCommand(path, model)))
    }

    @Test
    fun `the placeholder follows busy, mid-turn sends and the OS`() {
        assertEquals("↵ to send, ⌘↵ to queue for later", composerPlaceholder(working, mac = true))
        assertEquals("Enter to send, Ctrl+Enter to queue for later", composerPlaceholder(working, mac = false))
        assertEquals("Claude is working · Esc to stop", composerPlaceholder(noCapabilities, mac = true))
        assertEquals("Claude is working · Esc to stop", composerPlaceholder(starting, mac = false))
        assertEquals("Type / for commands", composerPlaceholder(idle, mac = true))
        assertEquals("Type / for commands", composerPlaceholder(idle.copy(midTurnSends = true), mac = false))
    }

    @Test
    fun `a busy Enter that cannot send says why`() {
        assertEquals("Claude is still starting: send again in a moment.", busyRefusal(starting))
        assertEquals(
            "This claude CLI does not take messages while it works: wait for the reply, or press Esc to stop.",
            busyRefusal(noCapabilities),
        )
        val shell = "Shell commands run when Claude is done: wait for the reply, or press Esc to stop."
        assertEquals(shell, busyRefusal(working, shell = true))
        assertEquals(shell, busyRefusal(noCapabilities, shell = true))
        assertEquals(shell, busyRefusal(idle.copy(shellRunning = true), shell = true), "only a ! running")
        assertNull(busyRefusal(idle, shell = true))
        assertEquals("Answer the card above first, then send.", busyRefusal(working, cardOpen = true))
        assertEquals("Answer the card above first, then send.", busyRefusal(idle, cardOpen = true))
        // D8's after-the-turn and runs outcomes: a busy slash command and a busy draft with images go later, so neither is refused.
        assertNull(busyRefusal(working, command = true))
        assertNull(busyRefusal(working, images = true))
        assertNull(busyRefusal(working))
        assertNull(busyRefusal(idle))
    }

    @Test
    fun `captions follow the stage and the kind`() {
        fun send(stage: PendingStage, later: Boolean) = PendingSend("u", "text", emptyList(), 0L, later, stage)
        assertEquals("Sent · Claude reads it at its next step", queuedCaption(send(PendingStage.QUEUED, later = false)))
        assertEquals("Queued · sends when Claude is done", queuedCaption(send(PendingStage.QUEUED, later = true)))
        assertEquals("Waits for the shell command's output to go out", queuedCaption(send(PendingStage.HELD, later = false)))
        assertEquals("Waits for the shell command's output to go out", queuedCaption(send(PendingStage.HELD, later = true)))
        assertEquals("Withdrawing…", queuedCaption(send(PendingStage.WITHDRAWING, later = true)))
        assertEquals("Withdrawing…", queuedCaption(send(PendingStage.WITHDRAWING, later = false)))
    }

    @Test
    fun `the Send tooltip names the queue key`() {
        assertEquals("Send now · ⌘↵ queues it for after this turn", sendNowTooltip(true))
        assertEquals("Send now · Ctrl+Enter queues it for after this turn", sendNowTooltip(false))
        assertEquals("Sent while Claude worked", FOLDED_CAPTION)
    }
}
