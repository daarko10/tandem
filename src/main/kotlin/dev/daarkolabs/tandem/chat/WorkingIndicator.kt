package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.TurnEnd
import kotlin.math.abs

/** The working row's text while a turn runs (spec 4.9) and the footer that replaces it when the turn ends (spec 4.10). */
object WorkingIndicator {
    private const val STEP_MAX = 60

    /** The present participles the working row picks from (appendix A). */
    val VERBS: List<String> = SPINNER_VERBS

    /**
     * The verb of the turn [turnUuid] after its main-thread tool call [lastToolUseId]: the same for the whole turn, re-rolled on each
     * tool call. The modulo comes before `abs`, so a hash of `Int.MIN_VALUE` never gives a negative index.
     */
    fun verb(turnUuid: String, lastToolUseId: String?): String =
        VERBS[abs((turnUuid + (lastToolUseId ?: "")).hashCode() % VERBS.size)]

    /**
     * What the working row says the turn does now, never what the row above it already says: the latest progress of a subagent of
     * this turn, else `Thinking` while a thinking block streams that no row shows, else the turn's [verb]. [rows] are the rows above
     * the working row (a trailing working row is skipped). [skipAgent] leaves the subagent's progress out: the step while its Agent
     * row is open, whose nested rows show it (spec 4.9).
     */
    fun step(state: ChatState, rows: List<ChatRow>, options: RowOptions, skipAgent: Boolean = false): String {
        val turn = state.turn
        if (!skipAgent) agentStep(state, rows)?.let { return it }
        if (!options.showThinking && thinkingStreams(state)) return "Thinking"
        val verb = verb(turn?.userUuid.orEmpty(), turn?.lastToolUseId)
        // The row above: the last one, or the one before it when [rows] already end with the working row.
        val last = rows.lastOrNull().let { if (it is WorkingRow) rows.getOrNull(rows.size - 2) else it }
        val above = when (last) {
            is ToolRow -> last.label.text
            is ThinkingRow -> last.label
            else -> null
        }
        // `Thinking` under a `Thinking… · 50 tokens` row would say the same twice: the next verb then.
        val repeats = above != null && (above == verb || above.startsWith("$verb…"))
        return if (repeats) VERBS[(VERBS.indexOf(verb) + 1) % VERBS.size] else verb
    }

    /**
     * The latest progress description of a subagent that runs (no status, or `running`) and has reported progress, middle-truncated;
     * only for a subagent whose Agent call is in the turn that runs, so a subagent an earlier turn left without its end shows nothing.
     */
    private fun agentStep(state: ChatState, rows: List<ChatRow>): String? {
        val task = state.tasks.values.lastOrNull { task ->
            (task.status == null || task.status == "running") && task.lastToolName != null && task.description != null &&
                task.toolUseId != null && inLastTurn(task.toolUseId, rows)
        } ?: return null
        return ToolLabels.middleTruncate(task.description!!, STEP_MAX)
    }

    /** Whether a top-level row after the last user message (or shell escape) holds the call [callId]. */
    private fun inLastTurn(callId: String, rows: List<ChatRow>): Boolean {
        for (i in rows.indices.reversed()) {
            when (val row = rows[i]) {
                is UserRow, is ShellRow -> return false
                is ToolRow -> if (row.calls.any { it.id == callId }) return true
                else -> Unit
            }
        }
        return false
    }

    /** Whether a main-thread thinking block of the turn that runs still streams. */
    private fun thinkingStreams(state: ChatState): Boolean {
        val entries = state.entries
        for (i in entries.indices.reversed()) {
            when (val entry = entries[i]) {
                is ChatEntry.User, is ChatEntry.Shell -> return false
                is ChatEntry.Assistant -> if (entry.parentToolUseId == null && entry.streaming &&
                    entry.blocks.any { it is AssistantBlock.Thinking && it.completedAtMillis == null }
                ) {
                    return true
                }
                else -> Unit
            }
        }
        return false
    }

    /** How the working row looks: [RUNNING] with the step, [WAITING] for the user's answer, [NOT_RESPONDING] after a silence. */
    enum class Mode { RUNNING, WAITING, NOT_RESPONDING }

    /** The working row as drawn: its [mode], whether its glyph cycles, its one line of [text] and what a screen reader says. */
    data class View(val mode: Mode, val animated: Boolean, val text: String, val accessibleLabel: String)

    /**
     * The working [row] at [nowMillis]. The CLI is silent since the later of its last line ([lastFrameAtMillis]) and the send, so a
     * turn after a long idle never starts as not responding; from [notRespondingAfterMs] of silence it is not responding, until its
     * next line. A pending card makes it wait for the user. Only a running row animates, and not under [reducedMotion].
     */
    fun view(row: WorkingRow, nowMillis: Long, lastFrameAtMillis: Long, reducedMotion: Boolean = false, notRespondingAfterMs: Long = 60_000): View {
        val elapsed = formatElapsed(nowMillis - row.startedAtMillis)
        val silence = nowMillis - maxOf(lastFrameAtMillis, row.startedAtMillis)
        return when {
            row.waiting -> View(Mode.WAITING, animated = false, "Waiting for you · $elapsed", "Claude is waiting for you")
            silence >= notRespondingAfterMs -> View(Mode.NOT_RESPONDING, animated = false, "Not responding · $elapsed", "Claude is not responding")
            else -> {
                val tokens = if (row.outputTokens > 0) " · ${formatTokens(row.outputTokens.toLong())} tokens" else ""
                View(Mode.RUNNING, animated = !reducedMotion, "${row.step}… · $elapsed$tokens", "Claude is working")
            }
        }
    }

    // Package-qualified: an unqualified call would resolve to the member and call itself.
    fun formatElapsed(ms: Long): String = dev.daarkolabs.tandem.chat.formatElapsed(ms)

    fun formatTokens(n: Long): String = dev.daarkolabs.tandem.chat.formatTokens(n)

    fun formatDuration(ms: Long): String = dev.daarkolabs.tandem.chat.formatDuration(ms)

    /**
     * The footer of a turn that ended as [end]: how long a completed one worked ([durationMs]), the question after an interrupt, and
     * for a failure the mid-response wording when text had streamed ([textStreamed]), else the API error as it came, else [error].
     */
    fun footer(end: TurnEnd, durationMs: Long?, error: String?, textStreamed: Boolean): String = when (end) {
        TurnEnd.COMPLETED -> "Worked for ${formatElapsed(durationMs ?: 0)}"
        TurnEnd.INTERRUPTED -> "Interrupted · What should Claude do instead?"
        TurnEnd.FAILED -> when {
            textStreamed -> "Server error mid-response. The response above may be incomplete."
            error == null -> "The turn failed"
            error.startsWith("API Error: ") -> error
            else -> "The turn failed ($error)"
        }
    }
}
