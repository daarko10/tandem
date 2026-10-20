package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.string
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.NoticeKind
import dev.daarkolabs.tandem.session.TaskInfo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.IdentityHashMap

/** What the rows show beyond the state: [showThinking] is the `Show thinking` setting. */
data class RowOptions(val showThinking: Boolean)

/** The chat list's rows, the pending request shown as a card, and how many more requests wait behind it. */
data class ChatRows(val rows: ImmutableList<ChatRow>, val card: PendingCard?, val moreWaiting: Int)

/**
 * Builds the chat rows from a [ChatState] (spec 5.3, 5.4): one row per user message, text block, thinking block, notice and tool
 * call, each with its key and its gap. A subagent's entries (`parentToolUseId` naming an Agent call) become that call's children.
 *
 * Rows are cached per turn (a turn runs from one user message to the next): a closed turn is built once more when it closes and then
 * kept, and its rows are the same instances in every later build. A build checks the cache by the identity of the last entry it holds
 * and scans only the entries after it, so a long session costs no more per event than a short one. A later entry that refers to a
 * cached turn (a late result, a background agent's lines) opens that turn again; a state whose entries shrank (a retraction) or whose
 * working directory changed drops the cache. The rows of the open turns that equal the previous build's keep their previous instance,
 * and a result's body is built once, so unchanged rows compare by identity.
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
    )

    /** A result's body and stats, built once per result entry for the call input it was built with. */
    private class Body(val input: JsonObject?, val body: ToolBody, val stats: DiffStats?)

    private val frozen = ArrayList<Frozen>()
    private var frozenRows: PersistentList<ChatRow> = persistentListOf()
    private val frozenKeys = HashSet<String>()

    /** The cached turn (index into [frozen]) that owns each call of the cached turns. */
    private val callOwner = HashMap<String, Int>()
    private var lastSize = 0
    private var lastCwd: String? = null

    /** The previous build's rows after the cached turns, by key: an equal row keeps this instance. */
    private var live: Map<String, ChatRow> = emptyMap()
    private var bodies = IdentityHashMap<ChatEntry.ToolResult, Body>()

    private val frozenEnd: Int get() = frozen.lastOrNull()?.end ?: 0

    fun build(state: ChatState): ChatRows {
        val entries = state.entries
        if (!cacheHolds(state)) reset()
        lastSize = entries.size
        lastCwd = state.cwd
        reopenReferencedTurns(entries)

        val region = Region(state, frozenEnd)
        val nextBodies = IdentityHashMap<ChatEntry.ToolResult, Body>()
        val keys = HashSet<String>()
        var previous = frozenRows.lastOrNull()
        val turnRows = region.turns.mapIndexed { t, turn ->
            val turnOpen = state.turn != null && t == region.turns.lastIndex && entries[turn.first] is ChatEntry.User
            val rows = ArrayList<ChatRow>()
            for (index in turn) {
                val entry = entries[index]
                if (index in region.childEntries) continue
                for (row in entryRows(entry, turnOpen, region, nextBodies, previous)) {
                    val unique = reuse(withUniqueKey(row, keys))
                    rows += unique
                    previous = unique
                }
            }
            rows
        }
        bodies = nextBodies
        live = turnRows.flatten().associateBy { it.key }

        val all = frozenRows.mutate { list -> turnRows.forEach(list::addAll) }
        freeze(region, turnRows)
        return ChatRows(all, card = null, moreWaiting = 0)
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
        }
        frozen.clear()
        frozenRows = persistentListOf()
        frozenKeys.clear()
        callOwner.clear()
        bodies = IdentityHashMap()
    }

    /** Opens the cached turns that entries after the cache refer to again, from the earliest; their rows keep their instances. */
    private fun reopenReferencedTurns(entries: List<ChatEntry>) {
        var scanFrom = frozenEnd
        while (frozen.isNotEmpty()) {
            var earliest = Int.MAX_VALUE
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
            reopened.clear()
        }
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
            val cached = Frozen(turn.first, turn.last + 1, region.entries[turn.last], frozenRows, turnRows[t], region.callsOf[t])
            frozen += cached
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

    /** The previous build's instance of [row] when it is equal. */
    private fun reuse(row: ChatRow): ChatRow = live[row.key]?.takeIf { it == row } ?: row

    /** The top-level rows of [entry], each with its gap after [before] and the rows before it. */
    private fun entryRows(
        entry: ChatEntry,
        turnOpen: Boolean,
        region: Region,
        nextBodies: IdentityHashMap<ChatEntry.ToolResult, Body>,
        before: ChatRow?,
    ): List<ChatRow> {
        val rows = ArrayList<ChatRow>()
        var previous = before
        fun add(row: ChatRow) {
            rows += row
            previous = row
        }
        when (entry) {
            is ChatEntry.User -> add(UserRow("u:${entry.id}", entry.text, entry.text.startsWith("/") || entry.text.startsWith("!"), gap(Kind.USER, previous)))
            // Thinking rows are the main thread's; a subagent message of an unknown agent shows its text and calls only.
            is ChatEntry.Assistant -> assistantRows(entry, turnOpen, region, nextBodies, previous, thinking = entry.parentToolUseId == null).forEach(::add)
            is ChatEntry.ToolResult -> if (entry.toolUseId !in region.calls) add(orphan(entry, gap(Kind.TOOL, previous)))
            is ChatEntry.Notice -> if (entry.kind != NoticeKind.INTERRUPTED) add(NoticeRow("n:${entry.id}", entry.level, entry.text, gap(Kind.NOTICE, previous)))
            is ChatEntry.TurnEnded -> Unit
        }
        return rows
    }

    /** The rows of an assistant message: its non-blank text blocks, its thinking blocks (main thread only) and its visible tool calls. */
    private fun assistantRows(
        entry: ChatEntry.Assistant,
        turnOpen: Boolean,
        region: Region,
        nextBodies: IdentityHashMap<ChatEntry.ToolResult, Body>,
        before: ChatRow?,
        thinking: Boolean,
    ): List<ChatRow> {
        val rows = ArrayList<ChatRow>()
        var previous = before
        entry.blocks.forEachIndexed { index, block ->
            val row: ChatRow? = when (block) {
                is AssistantBlock.Text -> block.text.takeIf { it.isNotBlank() }?.let {
                    TextRow("t:${entry.id}:$index", it, entry.streaming && index >= entry.completedBlocks, gap(Kind.TEXT, previous))
                }
                is AssistantBlock.Thinking -> if (thinking) thinkingRow(entry, index, block, previous) else null
                is AssistantBlock.ToolUse -> {
                    val call = call(block, turnOpen, region, nextBodies)
                    // ToolSearch loads a deferred tool: hidden unless it failed (spec 4.6.2).
                    if (call.kind == ToolKind.TOOL_SEARCH && call.state != ToolState.FAILED) {
                        null
                    } else {
                        ToolRow("tool:${call.id}", persistentListOf(call), call.label, call.state, call.stats, gap(Kind.TOOL, previous))
                    }
                }
                is AssistantBlock.Other -> null
            }
            if (row != null) {
                rows += row
                previous = row
            }
        }
        return rows
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
    private fun call(block: AssistantBlock.ToolUse, turnOpen: Boolean, region: Region, nextBodies: IdentityHashMap<ChatEntry.ToolResult, Body>): ToolCall {
        val result = region.results[block.id]
        val state = ToolStates.of(block, result, block.id in region.pending, turnOpen)
        val named = ToolKinds.of(block.name)
        val shown: JsonElement? = result?.let { it.result ?: JsonPrimitive(it.text) }
        val label = ToolLabels.label(named, block.name, block.input, state, shown, region.cwd)
        val kind = if (named == ToolKind.CREATE && ToolBodies.isWriteUpdate(shown)) ToolKind.EDIT else named
        var body: ToolBody = ToolBody.None
        var stats: DiffStats? = null
        if (result != null) {
            val built = bodies[result]?.takeIf { it.input === block.input }
                ?: Body(
                    block.input,
                    ToolBodies.body(named, block.input, result.content, shown, result.isError),
                    ToolBodies.stats(named, block.input, shown),
                )
            nextBodies[result] = built
            body = built.body
            if (state == ToolState.DONE) stats = built.stats
            if (state == ToolState.DENIED) body = deniedBody(named, result, body)
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
        return ToolCall(block.id, block.name, kind, state, label, stats, body, children, progress, subject)
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
    private fun childRows(agentId: String, turnOpen: Boolean, region: Region, nextBodies: IdentityHashMap<ChatEntry.ToolResult, Body>): ImmutableList<ChatRow> {
        val entries = region.children[agentId] ?: return persistentListOf()
        val rows = ArrayList<ChatRow>()
        val keys = HashSet<String>()
        var previous: ChatRow? = null
        for (entry in entries) {
            val built = when (entry) {
                is ChatEntry.Assistant -> assistantRows(entry, turnOpen, region, nextBodies, previous, thinking = false)
                is ChatEntry.ToolResult -> if (entry.toolUseId !in region.calls) listOf(orphan(entry, gap(Kind.TOOL, previous))) else emptyList()
                else -> emptyList()
            }
            for (row in built) {
                var key = row.key
                var n = 1
                while (key in keys) key = "${row.key}#${++n}"
                keys += key
                val unique = if (key == row.key) row else row.withKey(key)
                rows += unique
                previous = unique
            }
        }
        return rows.toImmutableList()
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

    private enum class Kind { USER, TEXT, THINKING, TOOL, NOTICE }

    /** The space above a row of [kind] after [previous] (spec 5.3, with the precedence of the plan's T4). */
    private fun gap(kind: Kind, previous: ChatRow?): RowGap = when {
        previous == null -> RowGap.NONE
        kind == Kind.USER -> RowGap.TURN
        kind == Kind.NOTICE -> RowGap.SECTION
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
                if (entry is ChatEntry.User && index > turnStart) {
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

/** The call a subagent's entry belongs to, if any. */
private fun parentOf(entry: ChatEntry): String? = when (entry) {
    is ChatEntry.Assistant -> entry.parentToolUseId
    is ChatEntry.ToolResult -> entry.parentToolUseId
    else -> null
}

/** This row under [key]. */
private fun ChatRow.withKey(key: String): ChatRow = when (this) {
    is UserRow -> copy(key = key)
    is TextRow -> copy(key = key)
    is ThinkingRow -> copy(key = key)
    is ToolRow -> copy(key = key)
    is NoticeRow -> copy(key = key)
    is TurnEndRow -> copy(key = key)
    is WorkingRow -> copy(key = key)
}
