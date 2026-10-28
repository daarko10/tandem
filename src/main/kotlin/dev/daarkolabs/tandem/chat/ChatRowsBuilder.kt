package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.string
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.PendingStage
import dev.daarkolabs.tandem.session.NoticeKind
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.ShellEnd
import dev.daarkolabs.tandem.session.TaskInfo
import dev.daarkolabs.tandem.session.TurnEnd
import dev.daarkolabs.tandem.session.UserImage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.IdentityHashMap

/** What the rows show beyond the state: [showThinking] is the `Show thinking rows` setting. */
data class RowOptions(val showThinking: Boolean)

/** The chat list's rows, the pending request shown as a card, and how many more requests wait behind it. */
data class ChatRows(val rows: ImmutableList<ChatRow>, val card: PendingCard?, val moreWaiting: Int)

/**
 * Builds the chat rows from a [ChatState] (spec 5.3, 5.4): one row per user message, shell escape (MCP spec 12.1), text block,
 * thinking block and notice, and one per lone tool call or group of consecutive calls ([Grouping], spec 4.6.4), each with its key
 * and its gap. A shell escape starts a turn as a user message does (MCP spec 12.4). A subagent's entries
 * (`parentToolUseId` naming an Agent call) become that call's children, one row per call. While a turn runs the last row is the
 * [WorkingRow] (spec 4.9); once the latest turn has ended its [TurnEndRow] stands where it ended (spec 4.10), and a failed turn's
 * `The turn failed` notice gives way to it when the footer states all the notice says (spec 4.10, 4.11). The oldest pending request
 * comes as the [ChatRows.card] ([Cards.pending]). Once a turn has ended, the last text block of its reply carries the hover row's
 * [MessageActions] (polish spec 2.6), with the turn's fork point ([MessageActions.forkAt], desktop-parity spec 13.1).
 *
 * Rows are cached per turn (a turn runs from one user message or shell escape to the next): a closed turn is built once more when it
 * closes and then kept, and its rows are the same instances in every later build. A build checks the cache by the identity of the
 * last entry it holds and scans only the entries after it, so a long session costs no more per event than a short one. A later entry
 * that refers to a cached turn (a late result, a background agent's lines) opens that turn again, as does a new instance of a
 * cached usage notice (its card's answer, a `Retry`); a state whose entries shrank (a
 * retraction) or whose working directory changed drops the cache. The rows and the calls of the open turns that equal the previous
 * build's keep their previous instance, a result's body is built once and a live call's body once per input instance, so unchanged
 * rows and calls compare by identity (polish spec 6).
 *
 * Not thread-safe: one builder per presenter coroutine. A new [RowOptions] value takes a new builder.
 */
class ChatRowsBuilder(private val options: RowOptions) {
    /** A cached turn: entries [start] until [end] ([last] is the entry at `end - 1`), the rows before it and its own, the calls it owns. */
    private class Frozen(
        val start: Int,
        val end: Int,
        val last: ChatEntry,
        val rowsBefore: PersistentList<ChatRow>,
        val rows: List<ChatRow>,
        val calls: List<String>,
        val usageNotices: List<Pair<Int, ChatEntry.Notice>>,
    )

    /** A result's body and stats, built once per result entry for the call input it was built with. */
    private class Body(val input: JsonObject?, val body: ToolBody, val stats: DiffStats?)

    /** The bodies one build makes or keeps: the results' and the live calls'. The next build looks them up. */
    private class Bodies {
        val results = IdentityHashMap<ChatEntry.ToolResult, Body>()
        val pending = IdentityHashMap<JsonObject, ToolBody>()
    }

    private val frozen = ArrayList<Frozen>()
    private var frozenRows: PersistentList<ChatRow> = persistentListOf()
    private val frozenKeys = HashSet<String>()

    /** The cached turns (indices into [frozen], ascending) that hold a usage notice: the only ones a card's answer reopens. */
    private val usageTurns = ArrayList<Int>()

    /** The cached turn (index into [frozen]) that owns each call of the cached turns. */
    private val callOwner = HashMap<String, Int>()
    private var lastSize = 0
    private var lastCwd: String? = null

    /** The previous build's rows after the cached turns, by key: an equal row keeps this instance. */
    private var live: Map<String, ChatRow> = emptyMap()

    /** The previous build's calls of those rows (an Agent call's nested calls too), by id: an equal call keeps this instance. */
    private var liveCalls: Map<String, ToolCall> = emptyMap()
    private var bodies = IdentityHashMap<ChatEntry.ToolResult, Body>()

    /** The previous build's bodies of live calls, by the identity of the input they were built from (polish spec 6). */
    private var pendingBodies = IdentityHashMap<JsonObject, ToolBody>()

    /** The previous build's working row and card: an equal one keeps this instance. */
    private var lastWorking: WorkingRow? = null
    private var lastCard: PendingCard? = null

    private val frozenEnd: Int get() = frozen.lastOrNull()?.end ?: 0

    fun build(state: ChatState): ChatRows {
        val entries = state.entries
        if (!cacheHolds(state)) reset()
        lastSize = entries.size
        lastCwd = state.cwd
        reopenReferencedTurns(entries)

        val region = Region(state, frozenEnd)
        val nextBodies = Bodies()
        val keys = HashSet<String>()
        val end = if (state.turn == null) turnEnd(entries) else null
        var previous = frozenRows.lastOrNull()
        val turnRows = region.turns.mapIndexed { t, turn ->
            // A responding shell escape's turn is open as a prompt's is: else its running calls would read as interrupted.
            val turnOpen = state.turn != null && t == region.turns.lastIndex && entries[turn.first].startsTurn()
            // The footer of a failed turn says why (spec 4.10), so its "The turn failed" notice may say it twice (spec 4.11): it goes
            // when the footer holds all it says ([saidByFooter]). Not after the mid-response wording, which names no cause, and not in an
            // older turn, which has no footer: there the notice follows.
            val failureInFooter = end != null && end.end == TurnEnd.FAILED && !end.textStreamed && t == region.turns.lastIndex
            val context = TurnContext(turnOpen, failureInFooter, end)
            val rows = Rows(previous, grouped = true) { reuse(withUniqueKey(it, keys)) }
            // The reply that gets the hover row, once the turn has ended (polish spec 2.6), and where a fork at it cuts (spec 13.1).
            val reply = if (turnOpen) -1 else turn.lastWhere(entries::isReply)
            val forkAt = if (reply < 0) null else forkPoint(entries, turn)
            for (index in turn) {
                if (index in region.childEntries) continue
                entryRows(entries[index], context, region, nextBodies, rows, withActions = index == reply, forkAt = forkAt)
            }
            rows.flush()
            previous = rows.previous
            rows.rows
        }
        bodies = nextBodies.results
        pendingBodies = nextBodies.pending
        // Built against the previous build's rows, so an unchanged bubble keeps its instance.
        val queued = queuedRows(state, keys)
        live = turnRows.flatten().associateBy { it.key } + queued.associateBy { it.key }
        liveCalls = callsOf(live.values)

        val all = frozenRows.mutate { list ->
            turnRows.forEach(list::addAll)
            workingRow(state, list)?.let(list::add)
            list.addAll(queued)
        }
        freeze(region, turnRows)
        val (card, moreWaiting) = Cards.pending(state)
        lastCard = lastCard?.takeIf { it == card } ?: card
        return ChatRows(all, lastCard, moreWaiting)
    }

    /**
     * Where a fork at the reply of the turn [turn] cuts (desktop-parity spec 13.1): the [ChatEntry.Assistant.lastUuid] of the turn's
     * last main-thread message that has one, scanning back; never a subagent's line. Null when none has (a reply drafted from deltas
     * only, in a turn with no complete main-thread line).
     */
    private fun forkPoint(entries: List<ChatEntry>, turn: IntRange): String? {
        for (index in turn.reversed()) {
            val entry = entries[index]
            if (entry is ChatEntry.Assistant && entry.parentToolUseId == null) entry.lastUuid?.let { return it }
        }
        return null
    }

    /**
     * What the rows of one turn depend on beyond its entries: whether it runs, whether its footer states its failure (so a
     * `The turn failed` notice that says nothing more is not drawn), and the latest turn's end.
     */
    private class TurnContext(val turnOpen: Boolean, val failureInFooter: Boolean, val end: TurnEndAt?)

    /**
     * The entry the footer stands at ([TurnEndRow] key `e:<entry id>`), how its turn ended, the footer's text and whether any
     * main-thread text of the turn streamed (the `FAILED` footer then reads the mid-response wording).
     */
    private class TurnEndAt(val entry: ChatEntry, val end: TurnEnd, val text: String, val textStreamed: Boolean)

    /**
     * The footer of the latest turn, when it has ended (spec 4.10): at its [ChatEntry.TurnEnded], or, for a reloaded transcript that
     * has none, at its interrupt marker. Only the latest turn has one; null when the last user message (or shell escape) has neither
     * after it.
     */
    private fun turnEnd(entries: List<ChatEntry>): TurnEndAt? {
        var ended: ChatEntry.TurnEnded? = null
        var marker: ChatEntry.Notice? = null
        var textStreamed = false
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            if (entry.startsTurn()) break
            when (entry) {
                is ChatEntry.User, is ChatEntry.Shell -> Unit
                is ChatEntry.TurnEnded -> if (ended == null) ended = entry
                is ChatEntry.Notice -> if (marker == null && entry.kind == NoticeKind.INTERRUPTED) marker = entry
                is ChatEntry.Assistant -> if (entry.parentToolUseId == null && entry.blocks.any { it is AssistantBlock.Text && it.text.isNotBlank() }) {
                    textStreamed = true
                }
                is ChatEntry.ToolResult -> Unit
            }
        }
        if (ended != null) {
            return TurnEndAt(ended, ended.end, WorkingIndicator.footer(ended.end, ended.durationMs, ended.error, textStreamed), textStreamed)
        }
        if (marker != null) {
            return TurnEndAt(marker, TurnEnd.INTERRUPTED, WorkingIndicator.footer(TurnEnd.INTERRUPTED, null, null, textStreamed), textStreamed)
        }
        return null
    }

    /** The working row while a turn runs (spec 4.9), after [rows]; the previous instance when it is unchanged. */
    private fun workingRow(state: ChatState, rows: List<ChatRow>): WorkingRow? {
        val turn = state.turn
        if (turn == null) {
            lastWorking = null
            return null
        }
        val step = WorkingIndicator.step(state, rows, options)
        // When the step is a subagent's progress: the step while its Agent row is open (spec 4.9).
        val withoutAgent = WorkingIndicator.step(state, rows, options, skipAgent = true)
        val row = WorkingRow(
            turnUuid = turn.userUuid,
            startedAtMillis = turn.startedAtMillis,
            step = step,
            outputTokens = turn.outputTokens + turn.thinkingEstimate,
            waiting = state.pendingPermissions.isNotEmpty(),
            gap = gap(Kind.END, rows.lastOrNull()),
            expandedStep = withoutAgent.takeIf { it != step },
        )
        return (lastWorking?.takeIf { it == row } ?: row).also { lastWorking = it }
    }

    /** Whether the cached turns still describe [state]: no entry was removed, the last cached entry is the same, cwd unchanged. */
    private fun cacheHolds(state: ChatState): Boolean {
        val entries = state.entries
        if (state.cwd != lastCwd || entries.size < lastSize) return false
        val end = frozenEnd
        return end == 0 || entries.size >= end && entries[end - 1] === frozen.last().last
    }

    /** Drops the cache. The rows built so far stay candidates for reuse, so a rebuilt row that is equal keeps its instance. */
    private fun reset() {
        if (frozen.isNotEmpty()) {
            val rows = HashMap<String, ChatRow>()
            frozenRows.forEach { rows[it.key] = it }
            rows.putAll(live)
            live = rows
            liveCalls = callsOf(rows.values)
        }
        frozen.clear()
        usageTurns.clear()
        frozenRows = persistentListOf()
        frozenKeys.clear()
        callOwner.clear()
        bodies = IdentityHashMap()
    }

    /**
     * Opens the cached turns that entries after the cache refer to again, from the earliest, and from the earliest cached turn whose
     * usage notice changed (its card's answer came, or `Retry` was pressed, deviation 7); their rows keep their instances.
     */
    private fun reopenReferencedTurns(entries: List<ChatEntry>) {
        var scanFrom = frozenEnd
        while (frozen.isNotEmpty()) {
            var earliest = changedUsageTurn(entries)
            for (index in scanFrom until entries.size) {
                val entry = entries[index]
                val parent = parentOf(entry)
                if (parent != null) callOwner[parent]?.let { earliest = minOf(earliest, it) }
                if (entry is ChatEntry.ToolResult) callOwner[entry.toolUseId]?.let { earliest = minOf(earliest, it) }
            }
            if (earliest == Int.MAX_VALUE) return
            val reopened = frozen.subList(earliest, frozen.size)
            val first = reopened.first()
            scanFrom = first.start
            frozenRows = first.rowsBefore
            val rows = HashMap(live)
            for (turn in reopened) {
                turn.calls.forEach(callOwner::remove)
                turn.rows.forEach {
                    frozenKeys.remove(it.key)
                    rows[it.key] = it
                }
            }
            live = rows
            liveCalls = callsOf(rows.values)
            usageTurns.removeAll { it >= earliest }
            reopened.clear()
        }
    }

    /** The earliest cached turn with a usage notice that is no longer the same instance at its index; [Int.MAX_VALUE] for none. */
    private fun changedUsageTurn(entries: List<ChatEntry>): Int {
        for (t in usageTurns) {
            if (frozen[t].usageNotices.any { (index, notice) -> entries[index] !== notice }) return t
        }
        return Int.MAX_VALUE
    }

    /**
     * Caches the closed turns of [region] that no later entry refers to: every turn but the last, up to the first one that a later
     * turn's entries refer back to.
     */
    private fun freeze(region: Region, turnRows: List<List<ChatRow>>) {
        val turns = region.turns
        if (turns.size < 2) return
        // The earliest turn that turn t or a later one refers to.
        var earliest = Int.MAX_VALUE
        var cut = 0
        for (t in turns.lastIndex downTo 1) {
            earliest = minOf(earliest, region.earliestReference[t])
            if (earliest >= t) {
                cut = t
                break
            }
        }
        for (t in 0 until cut) {
            val turn = turns[t]
            val usageNotices = turn.mapNotNull { index ->
                (region.entries[index] as? ChatEntry.Notice)?.takeIf { it.usage != null }?.let { index to it }
            }
            val cached = Frozen(turn.first, turn.last + 1, region.entries[turn.last], frozenRows, turnRows[t], region.callsOf[t], usageNotices)
            frozen += cached
            if (usageNotices.isNotEmpty()) usageTurns += frozen.lastIndex
            cached.calls.forEach { callOwner[it] = frozen.lastIndex }
            turnRows[t].forEach { frozenKeys += it.key }
            frozenRows = frozenRows.mutate { it.addAll(turnRows[t]) }
        }
    }

    /** [row] under a key no other row of the list has: a repeated key gets `#2`, `#3`, … in the order the rows come. */
    private fun withUniqueKey(row: ChatRow, keys: MutableSet<String>): ChatRow {
        var key = row.key
        var n = 1
        while (key in frozenKeys || key in keys) key = "${row.key}#${++n}"
        keys += key
        return if (key == row.key) row else row.withKey(key)
    }

    /** [images] by id, in the order sent (polish spec 2.7); the shared empty list for a prompt without any. */
    private fun imageRefs(images: List<UserImage>): ImmutableList<ImageRef> =
        if (images.isEmpty()) persistentListOf() else images.mapIndexed { i, image -> ImageRef(image.id, image.mediaType, i) }.toImmutableList()

    /**
     * One [QueuedRow] per message waiting in [state] (desktop-parity spec 12.1), in send order, keyed `q:<uuid>`; each the previous
     * build's instance when it is equal. Every waiting message can be withdrawn: D8's `queue-cancel-steer` recorded a steer withdrawn
     * as a later message is (probes 5.1, spec 12.3).
     */
    private fun queuedRows(state: ChatState, keys: MutableSet<String>): List<QueuedRow> = state.pendingSends.map { send ->
        val row = QueuedRow(
            "q:${send.uuid}",
            send.text,
            imageRefs(send.images),
            queuedCaption(send),
            withdrawable = true,
            withdrawing = send.stage == PendingStage.WITHDRAWING,
        )
        reuse(withUniqueKey(row, keys)) as QueuedRow
    }

    /**
     * The row of notice [entry]. A local command's notice keeps its command; `/mcp`'s (the CLI ran `mcp`) gets the link to the panel in
     * place of the CLI's terminal hint, removed by exact match only (spec 3.5); `/context`'s is Markdown (desktop-parity spec 3.1); a
     * `/usage` notice of the stream carries its card, labelled with the session's [models] (spec 3.2).
     */
    private fun noticeRow(entry: ChatEntry.Notice, gap: RowGap, region: Region): NoticeRow {
        val local = entry.kind == NoticeKind.LOCAL_COMMAND
        val link = if (local && entry.runCommand == "mcp") NoticeLink.OPEN_MCP else null
        val text = if (link == NoticeLink.OPEN_MCP) entry.text.removeSuffix(MCP_TERMINAL_HINT) else entry.text
        return NoticeRow(
            "n:${entry.id}",
            entry.level,
            text,
            gap,
            entry.kind,
            entry.command,
            link,
            usage = entry.usage?.let { usageCard(it, entry.text, region.models, region.activeModel) },
            markdown = local && entry.runCommand == "context",
            sourceUuid = entry.sourceUuid,
        )
    }

    /** The previous build's instance of [row] when it is equal. */
    private fun reuse(row: ChatRow): ChatRow = live[row.key]?.takeIf { it == row } ?: row

    /**
     * Collects rows in order, each with its gap after the row before it. With [grouped] (the main thread), visible tool calls wait in a
     * run that every other row, a thinking block (shown or not) and a notice close (spec 4.6.4 rule 1); [flush] folds the run into
     * rows ([Grouping.fold]). Without it (a subagent's rows), each call is its own row. [finish] gives each row its final key.
     */
    private inner class Rows(var previous: ChatRow?, private val grouped: Boolean, private val finish: (ChatRow) -> ChatRow) {
        val rows = ArrayList<ChatRow>()
        private val run = ArrayList<ToolCall>()

        /** Closes the run, then adds the row [make] builds after the previous one, if it builds one. */
        fun add(make: (previous: ChatRow?) -> ChatRow?) {
            flush()
            make(previous)?.let(::emit)
        }

        fun call(call: ToolCall) {
            if (grouped) run += call else emit(toolRow(listOf(call), gap(Kind.TOOL, previous)))
        }

        /** Closes the run: one row per lone call or group. */
        fun flush() {
            if (run.isEmpty()) return
            for (calls in Grouping.fold(run)) emit(toolRow(calls, gap(Kind.TOOL, previous)))
            run.clear()
        }

        private fun emit(row: ChatRow) {
            val done = finish(row)
            rows += done
            previous = done
        }
    }

    /** A lone call's row with its own label, or a group's (spec 4.6.4) with its sentence, stats and state; keyed by the first call. */
    private fun toolRow(calls: List<ToolCall>, gap: RowGap): ToolRow {
        val first = calls.first()
        return if (calls.size == 1) {
            ToolRow("tool:${first.id}", persistentListOf(first), first.label, first.state, first.stats, gap)
        } else {
            ToolRow("tool:${first.id}", calls.toImmutableList(), Grouping.sentence(calls), Grouping.state(calls), Grouping.stats(calls), gap)
        }
    }

    /** Adds the top-level rows of [entry] to [rows]. */
    private fun entryRows(
        entry: ChatEntry,
        context: TurnContext,
        region: Region,
        nextBodies: Bodies,
        rows: Rows,
        withActions: Boolean,
        forkAt: String?,
    ) {
        val turnOpen = context.turnOpen
        when (entry) {
            is ChatEntry.User -> rows.add {
                UserRow(
                    "u:${entry.id}",
                    entry.text,
                    entry.text.startsWith("/") || entry.text.startsWith("!"),
                    gap(Kind.USER, it),
                    imageRefs(entry.images),
                    caption = if (entry.folded) FOLDED_CAPTION else null,
                )
            }
            // Thinking rows are the main thread's; a subagent message of an unknown agent shows its text and calls only.
            is ChatEntry.Assistant ->
                assistantRows(entry, turnOpen, region, nextBodies, rows, entry.parentToolUseId == null, withActions, forkAt)
            is ChatEntry.ToolResult -> if (entry.toolUseId !in region.calls) rows.add { orphan(entry, gap(Kind.TOOL, it)) }
            // The interrupt marker shows as the turn's end, not as a row; it still closes a group.
            is ChatEntry.Notice -> when {
                entry.kind == NoticeKind.INTERRUPTED ||
                    context.failureInFooter && entry.level == NoticeLevel.ERROR && saidByFooter(entry.text, context.end?.text.orEmpty()) ->
                    rows.add { footer(entry, context, it) }
                else -> rows.add { noticeRow(entry, gap(Kind.NOTICE, it), region) }
            }
            is ChatEntry.TurnEnded -> if (context.end?.entry === entry) rows.add { footer(entry, context, it) }
            is ChatEntry.Shell -> rows.add { shellRow(entry, gap(Kind.SHELL, it)) }
        }
    }

    /**
     * Adds the rows of an assistant message to [rows]: its non-blank text blocks, its thinking blocks (main thread only) and its
     * visible tool calls. A thinking block closes a group whether or not it is shown, so the setting never changes grouping. With
     * [withActions] (the reply of an ended turn) its last text block carries the hover row's [MessageActions], once it no longer streams,
     * with the turn's fork point [forkAt].
     */
    private fun assistantRows(
        entry: ChatEntry.Assistant,
        turnOpen: Boolean,
        region: Region,
        nextBodies: Bodies,
        rows: Rows,
        thinking: Boolean,
        withActions: Boolean = false,
        forkAt: String? = null,
    ) {
        val actionsAt = if (withActions && !entry.streaming) entry.blocks.indexOfLast { it.isText() } else -1
        entry.blocks.forEachIndexed { index, block ->
            when (block) {
                is AssistantBlock.Text -> if (block.text.isNotBlank()) {
                    val actions = if (index == actionsAt) MessageActions(entry.id, entry.markdown(), entry.atMillis, forkAt) else null
                    rows.add { TextRow("t:${entry.id}:$index", block.text, entry.streaming && index >= entry.completedBlocks, gap(Kind.TEXT, it), actions) }
                }
                is AssistantBlock.Thinking -> rows.add { if (thinking) thinkingRow(entry, index, block, it) else null }
                is AssistantBlock.ToolUse -> {
                    val call = call(block, turnOpen, region, nextBodies)
                    // ToolSearch loads a deferred tool: hidden unless it failed (spec 4.6.2), and not counted in a group.
                    if (call.kind != ToolKind.TOOL_SEARCH || call.state == ToolState.FAILED) rows.call(call)
                }
                is AssistantBlock.Other -> Unit
            }
        }
    }

    /** A thinking block's row (spec 4.5); none when thinking is hidden, or for a finished block that shows nothing. */
    private fun thinkingRow(entry: ChatEntry.Assistant, index: Int, block: AssistantBlock.Thinking, before: ChatRow?): ThinkingRow? {
        if (!options.showThinking) return null
        val streaming = entry.streaming && block.completedAtMillis == null
        val tokens = block.estimatedTokens?.takeIf { it > 0 }
        val duration = block.durationMs ?: block.completedAtMillis?.let { end -> block.startedAtMillis?.let { end - it } }
        val label = when {
            streaming -> if (tokens == null) "Thinking…" else "Thinking… · ${formatTokens(tokens.toLong())} tokens"
            duration != null -> "Thought for ${(duration.coerceAtLeast(0) + 500) / 1_000}s"
            block.text.isBlank() && tokens == null -> return null
            else -> "Thinking"
        }
        return ThinkingRow("th:${entry.id}:$index", label, block.text.trim(), streaming, gap(Kind.THINKING, before))
    }

    /** One tool call with its state, labels, body and, for an Agent call, its subagent's rows and progress. */
    private fun call(block: AssistantBlock.ToolUse, turnOpen: Boolean, region: Region, nextBodies: Bodies): ToolCall {
        val result = region.results[block.id]
        val state = ToolStates.of(block, result, block.id in region.pending, turnOpen)
        val named = ToolKinds.of(block.name)
        val shown: JsonElement? = result?.let { it.result ?: JsonPrimitive(it.text) }
        val label = ToolLabels.label(named, block.name, block.input, state, shown, region.cwd)
        val kind = if (named == ToolKind.CREATE && ToolBodies.isWriteUpdate(shown)) ToolKind.EDIT else named
        var body: ToolBody = ToolBody.None
        var stats: DiffStats? = null
        val input = block.input
        if (result != null) {
            val built = bodies[result]?.takeIf { it.input === block.input }
                ?: Body(
                    block.input,
                    ToolBodies.body(named, block.input, result.content, shown, result.isError).let { body ->
                        if (named == ToolKind.PLAN && !result.isError && body !is ToolBody.Markdown) draftedPlan(block, region) ?: body else body
                    },
                    ToolBodies.stats(named, block.input, shown),
                )
            nextBodies.results[result] = built
            body = built.body
            if (state == ToolState.DONE) stats = built.stats
            if (state == ToolState.DENIED) body = deniedBody(named, result, body)
        } else if (input != null && (state == ToolState.RUNNING || state == ToolState.WAITING)) {
            // The body so far (polish spec 2.1), built once per input instance: the lookup reads the previous build's map, so the
            // deltas that rebuild the live turn reuse it. A PENDING call, whose input still streams, and an INTERRUPTED one keep None.
            body = (pendingBodies[input] ?: ToolBodies.pending(named, input)).also { nextBodies.pending[input] = it }
        }
        var children: ImmutableList<ChatRow> = persistentListOf()
        var progress: String? = null
        if (named == ToolKind.AGENT) {
            children = childRows(block.id, turnOpen, region, nextBodies)
            progress = if (result != null) ToolLabels.agentProgress(shown) else region.tasks[block.id]?.let(ToolLabels::agentProgress)
            if (state != ToolState.FAILED && state != ToolState.DENIED) body = agentBody(body, children, region.tasks[block.id])
        }
        val subject = when (kind) {
            ToolKind.READ, ToolKind.EDIT, ToolKind.CREATE -> ToolLabels.filePath(block.input)?.let { ToolLabels.relativePath(it, region.cwd) }
            ToolKind.SHELL -> block.input?.string("command")?.let(ToolLabels::oneLine)
            else -> null
        }
        val built = ToolCall(block.id, block.name, kind, state, label, stats, body, children, progress, subject)
        return liveCalls[block.id]?.takeIf { it == built } ?: built
    }

    /**
     * The plan an answered main-thread `ExitPlanMode` [block] carried no text for (spec 4.6.2): the plan its card showed, drafted
     * earlier in its turn ([Cards.draftedPlan]), as Markdown; null when there is none. Built once per result, as the body is.
     */
    private fun draftedPlan(block: AssistantBlock.ToolUse, region: Region): ToolBody.Markdown? {
        val entries = region.entries
        val at = entries.indexOfLast { entry -> entry is ChatEntry.Assistant && entry.parentToolUseId == null && entry.blocks.any { it === block } }
        if (at < 0) return null
        return Cards.draftedPlan(entries, at + 1, block.id).first?.let { ToolBody.Markdown(it, null) }
    }

    /** A denied call's body: the denial is the user's, not an error; a plan sent back shows the feedback. */
    private fun deniedBody(kind: ToolKind, result: ChatEntry.ToolResult, body: ToolBody): ToolBody = when {
        kind == ToolKind.PLAN -> ToolBodies.plain("Feedback: ${result.text.trim()}", error = false)
        body is ToolBody.Mono -> body.copy(firstLineError = false)
        else -> body
    }

    /** An Agent call's body: nothing when its subagent forwarded text (the children show it), else its final text or the task summary. */
    private fun agentBody(body: ToolBody, children: List<ChatRow>, task: TaskInfo?): ToolBody = when {
        children.any { it is TextRow } -> ToolBody.None
        body is ToolBody.Markdown -> body
        else -> task?.summary?.takeIf { it.isNotBlank() }?.let { ToolBody.Markdown(it, null) } ?: ToolBody.None
    }

    /** The rows of the subagent of Agent call [agentId]: its text and its tool calls, ungrouped; its thinking is not shown. */
    private fun childRows(agentId: String, turnOpen: Boolean, region: Region, nextBodies: Bodies): ImmutableList<ChatRow> {
        val entries = region.children[agentId] ?: return persistentListOf()
        val keys = HashSet<String>()
        val rows = Rows(null, grouped = false) { row ->
            var key = row.key
            var n = 1
            while (key in keys) key = "${row.key}#${++n}"
            keys += key
            if (key == row.key) row else row.withKey(key)
        }
        for (entry in entries) {
            when (entry) {
                is ChatEntry.Assistant -> assistantRows(entry, turnOpen, region, nextBodies, rows, thinking = false)
                is ChatEntry.ToolResult -> if (entry.toolUseId !in region.calls) rows.add { orphan(entry, gap(Kind.TOOL, it)) }
                else -> Unit
            }
        }
        return rows.rows.toImmutableList()
    }

    /** A result whose call is nowhere in the state: a `Used a tool` row with the result as its body, so nothing is dropped. */
    private fun orphan(result: ChatEntry.ToolResult, gap: RowGap): ToolRow {
        val state = when {
            !result.isError -> ToolState.DONE
            ToolStates.isDenial(result) -> ToolState.DENIED
            else -> ToolState.FAILED
        }
        val text = when (state) {
            ToolState.DONE -> "Used a tool"
            ToolState.DENIED -> "Use a tool · Denied"
            else -> "Failed to use a tool"
        }
        val label = RowLabel(text, persistentListOf())
        val body = ToolBodies.plain(result.text, error = state == ToolState.FAILED)
        val call = ToolCall(result.toolUseId, "", ToolKind.OTHER, state, label, null, body, persistentListOf(), null)
        return ToolRow("tool:${result.toolUseId}", persistentListOf(call), label, state, null, gap)
    }

    /**
     * The row of shell escape [entry] (MCP spec 12.1, 12.2): its output with colour escapes stripped, cut as a tool body is
     * ([ToolBodies.clamp]); the frames keep the raw text. Its status says how it ended, unless it exited 0, still runs or was
     * reloaded (a transcript keeps no end).
     */
    private fun shellRow(entry: ChatEntry.Shell, gap: RowGap): ShellRow {
        val (lines, total) = ToolBodies.clamp(ToolLabels.stripAnsi(entry.output))
        val status = when (val end = entry.end) {
            null -> null
            is ShellEnd.Exited -> if (end.code == 0) null else "exit ${end.code}"
            ShellEnd.Stopped -> "Stopped after ${formatDuration(entry.durationMs ?: 0)}"
            ShellEnd.TimedOut -> "Killed after 10 minutes"
            is ShellEnd.NotStarted -> "Could not start ${end.shell}: ${end.reason}"
            is ShellEnd.Failed -> "Could not read the command's output: ${end.reason}"
        }
        return ShellRow("sh:${entry.id}", entry.command, lines, total, entry.running, status, statusError = status != null, gap)
    }

    /** The latest turn's footer when it stands at [entry], else nothing (the entry still closes a group). */
    private fun footer(entry: ChatEntry, context: TurnContext, before: ChatRow?): TurnEndRow? {
        val end = context.end?.takeIf { it.entry === entry } ?: return null
        return TurnEndRow("e:${entry.id}", end.end, end.text, gap(Kind.END, before))
    }

    private enum class Kind { USER, SHELL, TEXT, THINKING, TOOL, NOTICE, END }

    /** The space above a row of [kind] after [previous] (spec 5.3, with the precedence of the plan's T4). */
    private fun gap(kind: Kind, previous: ChatRow?): RowGap = when {
        previous == null -> RowGap.NONE
        kind == Kind.USER || kind == Kind.SHELL -> RowGap.TURN
        kind == Kind.NOTICE || kind == Kind.END -> RowGap.SECTION
        // A shell row has no bubble: what follows it sits a section below.
        previous is ShellRow -> RowGap.SECTION
        previous is UserRow -> RowGap.AFTER_BUBBLE
        previous is ToolRow || previous is ThinkingRow -> if (kind == Kind.TEXT) RowGap.BEFORE_TEXT else RowGap.ROW
        // After text, a notice, the working row or a footer.
        else -> RowGap.SECTION
    }

    /**
     * What one build needs to know about the entries after the cached turns: their turns, the calls and results in them, the
     * subagent entries by Agent call, which turn each call belongs to and which earlier turn each turn's entries refer to.
     */
    private class Region(state: ChatState, start: Int) {
        val entries: List<ChatEntry> = state.entries
        val cwd: String? = state.cwd
        val models: List<ModelOption> = state.models
        val activeModel: String? = state.activeModel
        val pending: Set<String> = state.pendingPermissions.mapNotNullTo(HashSet()) { it.toolUseId }
        val tasks: Map<String, TaskInfo> = state.tasks.values.filter { it.toolUseId != null }.associateBy { it.toolUseId!! }
        val turns = ArrayList<IntRange>()
        val calls = HashSet<String>()
        val results = HashMap<String, ChatEntry.ToolResult>()
        val children = HashMap<String, MutableList<ChatEntry>>()
        val childEntries = HashSet<Int>()
        val callsOf = ArrayList<MutableList<String>>()
        val earliestReference = ArrayList<Int>()

        init {
            val agents = HashSet<String>()
            val callTurn = HashMap<String, Int>()
            var turnStart = start
            for (index in start until entries.size) {
                val entry = entries[index]
                if (entry.startsTurn() && index > turnStart) {
                    turns += turnStart until index
                    turnStart = index
                }
                if (callsOf.size <= turns.size) {
                    callsOf += ArrayList<String>()
                    earliestReference += turns.size
                }
                val t = turns.size
                val parent = parentOf(entry)?.takeIf { it in agents }
                // A subagent's entries belong to the turn of their Agent call.
                val owner = parent?.let { callTurn.getValue(it) } ?: t
                if (parent != null) {
                    children.getOrPut(parent) { ArrayList() } += entry
                    childEntries += index
                }
                var earliest = owner
                when (entry) {
                    is ChatEntry.Assistant -> for (block in entry.blocks) {
                        if (block !is AssistantBlock.ToolUse) continue
                        calls += block.id
                        callTurn[block.id] = owner
                        callsOf[owner] += block.id
                        if (ToolKinds.of(block.name) == ToolKind.AGENT) agents += block.id
                    }
                    is ChatEntry.ToolResult -> {
                        results.putIfAbsent(entry.toolUseId, entry)
                        callTurn[entry.toolUseId]?.let { earliest = minOf(earliest, it) }
                    }
                    else -> Unit
                }
                earliestReference[t] = minOf(earliestReference[t], earliest)
            }
            if (start < entries.size) turns += turnStart until entries.size
        }
    }
}

/** The reducer's notice for a failed result: `The turn failed`, the failure kind in parentheses when there is one, `: ` and the details. */
private val TURN_FAILED = Regex("The turn failed(?: \\(([^()]*)\\))?: (.*)", RegexOption.DOT_MATCHES_ALL)

/**
 * Whether the reducer's `The turn failed (<kind>): <details>` notice [notice] says nothing the failed turn's [footer] does not (spec
 * 4.10, 4.11): its kind, when it has one, and its details are both in the footer's text. Any other notice says more, and stays.
 */
internal fun saidByFooter(notice: String, footer: String): Boolean {
    val match = TURN_FAILED.matchEntire(notice) ?: return false
    val kind = match.groups[1]?.value
    return (kind == null || kind in footer) && match.groupValues[2] in footer
}

/** The calls of the tool rows among [rows] and, recursively, of the tool rows nested in their Agent calls, by id. */
private fun callsOf(rows: Collection<ChatRow>): Map<String, ToolCall> {
    val calls = HashMap<String, ToolCall>()
    fun add(rows: Collection<ChatRow>) {
        for (row in rows) {
            if (row !is ToolRow) continue
            for (call in row.calls) {
                calls[call.id] = call
                if (call.children.isNotEmpty()) add(call.children)
            }
        }
    }
    add(rows)
    return calls
}

/** Whether [this] block is text that shows (not blank). */
private fun AssistantBlock.isText(): Boolean = this is AssistantBlock.Text && text.isNotBlank()

/** Whether the entry at [index] is a main-thread message with text that shows: a turn's reply is the last such message in it. */
private fun List<ChatEntry>.isReply(index: Int): Boolean {
    val entry = this[index]
    return entry is ChatEntry.Assistant && entry.parentToolUseId == null && entry.blocks.any { it.isText() }
}

/** The last index of this range for which [predicate] holds, else -1. */
private inline fun IntRange.lastWhere(predicate: (Int) -> Boolean): Int {
    for (index in reversed()) if (predicate(index)) return index
    return -1
}

/** The message's non-blank text blocks joined by a blank line: what the hover row's Copy puts on the clipboard. */
private fun ChatEntry.Assistant.markdown(): String = blocks.filter { it.isText() }.joinToString("\n\n") { (it as AssistantBlock.Text).text }

/**
 * Whether this entry starts a turn (MCP spec 12.4): a user message, or a shell escape, which stands in its place. Every check of a
 * turn's bounds reads both. A prompt the CLI took into a running turn ([ChatEntry.User.folded]) starts none, so one CLI turn is one
 * visual turn with one footer (desktop-parity spec 12.1).
 */
internal fun ChatEntry.startsTurn(): Boolean = this is ChatEntry.User && !folded || this is ChatEntry.Shell

/** The call a subagent's entry belongs to, if any. */
private fun parentOf(entry: ChatEntry): String? = when (entry) {
    is ChatEntry.Assistant -> entry.parentToolUseId
    is ChatEntry.ToolResult -> entry.parentToolUseId
    else -> null
}

/** This row under [key]. */
private fun ChatRow.withKey(key: String): ChatRow = when (this) {
    is UserRow -> copy(key = key)
    is ShellRow -> copy(key = key)
    is TextRow -> copy(key = key)
    is ThinkingRow -> copy(key = key)
    is ToolRow -> copy(key = key)
    is NoticeRow -> copy(key = key)
    is TurnEndRow -> copy(key = key)
    is WorkingRow -> copy(key = key)
    is QueuedRow -> copy(key = key)
}
