package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.session.PendingSend
import dev.daarkolabs.tandem.session.PendingStage

// Sending while Claude works (desktop-parity spec 12.1, 12.2, 12.4, 12.7): which sends go `later`, what the composer says, and the
// pending bubbles' captions. Pure, cheap enough for every key press on the EDT.
//
// Written in D8's recorded branches (probes 5.1): `queue-later-command` ran a later `/context` after the running turn ("after the
// turn") and a later image message in a turn of its own ("runs"), so a busy slash command and a busy draft with images go `later`
// and neither is refused; `queue-during-card` waited through the card ("waits"), so Ctrl/Cmd+Enter queues with a card open.

/** The caption under a prompt the CLI took into the running turn (spec 12.1). */
const val FOLDED_CAPTION: String = "Sent while Claude worked"

/**
 * Whether a send goes with `"priority":"later"` (spec 12.1): only while [busy], for the queue key (Ctrl/Cmd+Enter, [queueKey]), a
 * slash command Tandem does not intercept ([command], [isSlashCommand]) or a draft with images ([imageCount]), whichever key.
 */
fun sendsLater(command: Boolean, imageCount: Int, busy: Boolean, queueKey: Boolean): Boolean =
    busy && (queueKey || command || imageCount > 0)

/** A draft that starts with `/` and a command name: its first word, the name being the rest of it. */
private val COMMAND_WORD: Regex = Regex("^/(\\S+)")

/**
 * Whether [text] is a slash command (spec 12.1, MCP 3.4): its first word is `/<name>` with `<name>` in the palette's list, the
 * catalogue's names and aliases ([ComposerModel.commands]), the init's names ([ComposerModel.slashCommandNames]) and Tandem's own
 * rows. A draft that merely starts with `/`, such as a path in `/src/app.kt fails to compile`, is a prompt.
 */
fun isSlashCommand(text: String, model: ComposerModel): Boolean {
    val name = COMMAND_WORD.find(text.trimStart())?.groupValues?.get(1) ?: return false
    return name in TANDEM_NAMES ||
        name in model.slashCommandNames ||
        model.commands.any { it.name == name || name in it.aliases }
}

/**
 * The composer's placeholder (spec 12.1): busy with mid-turn sends, the two keys (macOS's glyphs when [mac]); busy without, how to
 * stop; idle, the palette's hint.
 */
fun composerPlaceholder(model: ComposerModel, mac: Boolean): String = when {
    !model.busy -> "Type / for commands"
    !model.midTurnSends -> "Claude is working · Esc to stop"
    mac -> "↵ to send, ⌘↵ to queue for later"
    else -> "Enter to send, Ctrl+Enter to queue for later"
}

/**
 * Why a send does not go out (spec 12.2, 12.4), for the composer notice; null when it may. A shell escape ([shell]) waits while a
 * turn runs, a message waits or another `!` runs; a card open ([cardOpen]) refuses until it is answered; idle, nothing else refuses.
 * While busy, a process with no init yet is still starting, and one whose init lacks the queue capabilities takes no message while it
 * works. A slash command ([command]) and a draft with images ([images]) are not refused: D8 recorded both running when sent `later`
 * (probes 5.1), so they go `later` ([sendsLater]).
 */
@Suppress("UNUSED_PARAMETER")
fun busyRefusal(
    model: ComposerModel,
    shell: Boolean = false,
    command: Boolean = false,
    images: Boolean = false,
    cardOpen: Boolean = false,
): String? = when {
    shell -> if (model.busy || model.shellRunning) "Shell commands run when Claude is done: wait for the reply, or press Esc to stop." else null
    cardOpen -> "Answer the card above first, then send."
    !model.busy -> null
    !model.capabilitiesKnown -> "Claude is still starting: send again in a moment."
    !model.midTurnSends -> "This claude CLI does not take messages while it works: wait for the reply, or press Esc to stop."
    else -> null
}

/** The caption under a waiting message's bubble (spec 12.1): by its stage, and for a written one by its kind. */
fun queuedCaption(send: PendingSend): String = when (send.stage) {
    PendingStage.WITHDRAWING -> "Withdrawing…"
    PendingStage.HELD -> "Waits for the shell command's output to go out"
    PendingStage.QUEUED -> if (send.later) "Queued · sends when Claude is done" else "Sent · Claude reads it at its next step"
}

/** The tooltip of the Send button beside Stop while busy (spec 12.1): it sends now, and names the queue key. */
fun sendNowTooltip(mac: Boolean): String =
    if (mac) "Send now · ⌘↵ queues it for after this turn" else "Send now · Ctrl+Enter queues it for after this turn"
