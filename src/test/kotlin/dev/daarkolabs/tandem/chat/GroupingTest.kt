package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.string
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.EditOutcome
import dev.daarkolabs.tandem.session.EditPhase
import dev.daarkolabs.tandem.session.EditReview
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.reduce
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The grouping rules of spec 4.6.4 and the grouped rows of 4.6.5. */
class GroupingTest {
    private val cwd = "/p"

    /** A call as the builder makes it: its label through [ToolLabels.label], its subject as the builder sets it. */
    private fun call(
        id: String,
        name: String,
        input: JsonObject?,
        state: ToolState = ToolState.DONE,
        result: JsonElement? = null,
        stats: DiffStats? = null,
        cwd: String? = this.cwd,
    ): ToolCall {
        val named = ToolKinds.of(name)
        val kind = if (named == ToolKind.CREATE && ToolBodies.isWriteUpdate(result)) ToolKind.EDIT else named
        val subject = when (kind) {
            ToolKind.READ, ToolKind.EDIT, ToolKind.CREATE -> ToolLabels.filePath(input)?.let { ToolLabels.relativePath(it, cwd) }
            ToolKind.SHELL -> input?.string("command")?.let(ToolLabels::oneLine)
            else -> null
        }
        val label = ToolLabels.label(named, name, input, state, result, cwd)
        return ToolCall(id, name, kind, state, label, stats, ToolBody.None, persistentListOf(), null, subject)
    }

    /** The call of fixture [name]'s `assistant` line [use] with its result on line [result], as a replay builds it. */
    private fun fixtureCall(name: String, use: Int, result: Int): ToolCall {
        val block = toolUseAt(name, use)
        val toolResult = toolResultAt(name, result)
        val state = ToolStates.of(block, toolResult, pending = false, turnOpen = false)
        val shown = labelResult(toolResult)
        val stats = if (state == ToolState.DONE) ToolBodies.stats(ToolKinds.of(block.name), block.input, shown) else null
        return call(block.id, block.name, block.input, state, shown, stats, cwd = "<path>")
    }

    private fun path(file: String): JsonObject = buildJsonObject { put("file_path", "$cwd/$file") }

    private fun read(file: String, state: ToolState = ToolState.DONE): ToolCall = call(file, "Read", path(file), state)

    private fun edit(file: String, stats: DiffStats?, state: ToolState = ToolState.DONE): ToolCall = call(file, "Edit", path(file), state, stats = stats)

    private fun create(file: String, stats: DiffStats?): ToolCall = call(file, "Write", path(file), stats = stats)

    private fun bash(id: String, command: String = id, state: ToolState = ToolState.DONE): ToolCall =
        call(id, "Bash", buildJsonObject { put("command", command) }, state)

    private fun grep(pattern: String): ToolCall = call("grep-$pattern", "Grep", buildJsonObject { put("pattern", pattern) })

    /** What the builder shows for one fold result: a lone call's own label, or the group's sentence. */
    private fun rowLabel(group: List<ToolCall>): String = if (group.size == 1) group.single().label.text else Grouping.sentence(group).text

    private fun labels(calls: List<ToolCall>): List<String> = Grouping.fold(calls).map(::rowLabel)

    @Test
    fun `Read a file, found files, searched for a pattern`() {
        val calls = listOf(
            fixtureCall("tools-read", 17, 21),
            fixtureCall("tools-read", 34, 38),
            fixtureCall("tools-read", 46, 50),
            fixtureCall("tools-read", 58, 62),
        )
        assertEquals(listOf(calls), Grouping.fold(calls))
        assertEquals("Read a file, found files, searched for a pattern", Grouping.sentence(calls).text)
    }

    // spec: `Read a file, edited a file, created <path> +3 -1`
    @Test
    fun `Read a file, edited a file, created path +3 -1`() {
        val calls = listOf(fixtureCall("tools-edit", 19, 23), fixtureCall("tools-edit", 40, 44), fixtureCall("tools-edit", 57, 61))
        assertEquals(listOf(calls), Grouping.fold(calls))
        val sentence = Grouping.sentence(calls)
        assertEquals("Read a file, edited a file, created <path>", sentence.text)
        // The created file's name is drawn in the editor font.
        assertEquals(persistentListOf(sentence.text.length - "<path>".length until sentence.text.length), sentence.spans)
        assertEquals(DiffStats(3, 1), Grouping.stats(calls))
        assertEquals(ToolState.DONE, Grouping.state(calls))
    }

    @Test
    fun `Found files, searched for a pattern`() {
        val calls = listOf(fixtureCall("tools-read-unavailable", 73, 79), fixtureCall("tools-read-unavailable", 82, 86))
        assertEquals(listOf(calls), Grouping.fold(calls))
        assertEquals("Found files, searched for a pattern", Grouping.sentence(calls).text)
    }

    @Test
    fun `Read 2 files, searched for a pattern`() {
        assertEquals("Read 2 files, searched for a pattern", Grouping.sentence(listOf(read("a"), read("b"), grep("p"))).text)
    }

    @Test
    fun `Read 4 files, searched for 2 patterns`() {
        val calls = listOf(read("a"), read("b"), read("c"), read("d"), grep("p"), grep("q"))
        assertEquals("Read 4 files, searched for 2 patterns", Grouping.sentence(calls).text)
    }

    // spec: `Edited 2 files, created d.kt +12 -4`
    @Test
    fun `Edited 2 files, created d dot kt +12 -4`() {
        val calls = listOf(edit("a", DiffStats(5, 3)), edit("b", DiffStats(5, 1)), create("d.kt", DiffStats(2, 0)))
        assertEquals(listOf(calls), Grouping.fold(calls))
        assertEquals("Edited 2 files, created d.kt", Grouping.sentence(calls).text)
        assertEquals(DiffStats(12, 4), Grouping.stats(calls))
    }

    /** A Tandem `edit_file` call of [file] under [review], in [state]. */
    private fun tandemEdit(file: String, review: EditReview, state: ToolState): ToolCall =
        call("tandem-$file", "mcp__tandem__edit_file", path(file), state).copy(review = review)

    // ide-edits spec 3.4: a row that speaks for its review is never folded into a group's sentence
    @Test
    fun `a queued or open Tandem edit between two reads stays its own row`() {
        for (phase in listOf(EditPhase.QUEUED, EditPhase.OPEN)) {
            val edit = tandemEdit("e.kt", EditReview("e.kt", phase), ToolState.RUNNING)
            val a = read("a")
            val b = read("b")
            assertEquals(listOf(listOf(a), listOf(edit), listOf(b)), Grouping.fold(listOf(a, edit, b)), phase.name)
        }
    }

    @Test
    fun `a decided Tandem edit that waits for its result stays its own row`() {
        for (outcome in listOf(EditOutcome.ACCEPTED, EditOutcome.REJECTED)) {
            val edit = tandemEdit("e.kt", EditReview("e.kt", EditPhase.DONE, outcome), ToolState.RUNNING)
            val a = read("a")
            val b = read("b")
            assertEquals(listOf(listOf(a), listOf(edit), listOf(b)), Grouping.fold(listOf(a, edit, b)), outcome.name)
        }
    }

    @Test
    fun `a stopped Tandem edit stays its own row`() {
        val edit = tandemEdit("e.kt", EditReview("e.kt", EditPhase.DONE, EditOutcome.STOPPED), ToolState.DONE)
        val a = read("a")
        val b = read("b")
        assertEquals(listOf(listOf(a), listOf(edit), listOf(b)), Grouping.fold(listOf(a, edit, b)))
    }

    @Test
    fun `an accepted Tandem edit with its result folds like any edit`() {
        val edit = tandemEdit("e.kt", EditReview("e.kt", EditPhase.DONE, EditOutcome.ACCEPTED), ToolState.DONE)
        val calls = listOf(read("a"), edit, read("b"))
        assertEquals(listOf(calls), Grouping.fold(calls))
    }

    @Test
    fun `Used 3 tools`() {
        val calls = (1..3).map { call("m$it", "mcp__github__list_issues", buildJsonObject { put("repo", "x") }) }
        assertEquals(listOf(calls), Grouping.fold(calls))
        assertEquals("Used 3 tools", Grouping.sentence(calls).text)
        assertNull(Grouping.stats(calls))
    }

    @Test
    fun `Ran a, Failed to run b, Ran c`() {
        val a = bash("a")
        val b = bash("b", state = ToolState.FAILED)
        val c = bash("c")
        assertEquals(listOf(listOf(a), listOf(b), listOf(c)), Grouping.fold(listOf(a, b, c)))
        assertEquals(listOf("Ran a", "Failed to run b", "Ran c"), labels(listOf(a, b, c)))
    }

    @Test
    fun `Reading x`() {
        val x = read("x", ToolState.RUNNING)
        assertEquals(listOf(listOf(x)), Grouping.fold(listOf(x)))
        assertEquals("Reading x", x.label.text)
        assertEquals(ToolState.RUNNING, Grouping.state(listOf(x)))
    }

    @Test
    fun `a text block closes a group`() {
        val rows = build(AssistantBlock.Text("x"))
        assertEquals(listOf("Read a.kt, b.kt", "x", "Read c.kt, d.kt"), rows.drop(1).map(::text))
        assertIs<TextRow>(rows[2])
        assertEquals(listOf("tool:c1", "tool:c3"), rows.toolRows().map { it.key })
        assertTrue(rows.toolRows().all { it.calls.size == 2 && it.state == ToolState.DONE })
    }

    @Test
    fun `a thinking block closes a group`() {
        val rows = build(AssistantBlock.Thinking("t", durationMs = 1000L))
        assertEquals(listOf("Read a.kt, b.kt", "Thought for 1s", "Read c.kt, d.kt"), rows.drop(1).map(::text))
        assertIs<ThinkingRow>(rows[2])
        val hidden = build(AssistantBlock.Thinking("t", durationMs = 1000L), RowOptions(showThinking = false))
        assertTrue(hidden.none { it is ThinkingRow })
        assertEquals(rows.toolRows().map { it.key to it.label }, hidden.toolRows().map { it.key to it.label })
        assertEquals(2, hidden.toolRows().size)
    }

    @Test
    fun `a failed call stands alone`() {
        val a = read("a")
        val b = read("b", ToolState.FAILED)
        val c = read("c")
        val d = read("d")
        assertEquals(listOf(listOf(a), listOf(b), listOf(c, d)), Grouping.fold(listOf(a, b, c, d)))
        assertEquals(listOf("Read a", "Failed to read b", "Read c, d"), labels(listOf(a, b, c, d)))
    }

    @Test
    fun `a denied call stands alone`() {
        val a = bash("a")
        val b = bash("b", state = ToolState.DENIED)
        val c = bash("c")
        assertEquals(listOf(listOf(a), listOf(b), listOf(c)), Grouping.fold(listOf(a, b, c)))
        assertEquals(listOf("Ran a", "Run b · Denied", "Ran c"), labels(listOf(a, b, c)))
    }

    @Test
    fun `an interrupted call stands alone`() {
        val a = read("a")
        val b = read("b", ToolState.INTERRUPTED)
        val c = read("c")
        val d = read("d")
        assertEquals(listOf(listOf(a), listOf(b), listOf(c, d)), Grouping.fold(listOf(a, b, c, d)))
        assertEquals(listOf("Read a", "Read b · Interrupted", "Read c, d"), labels(listOf(a, b, c, d)))
    }

    @Test
    fun `agents never fold or absorb`() {
        val read1 = read("a")
        val agent = call("ag", "Agent", buildJsonObject { put("description", "Look around") })
        val read2 = read("b")
        assertEquals(listOf(listOf(read1), listOf(agent), listOf(read2)), Grouping.fold(listOf(read1, agent, read2)))
        assertEquals("Ran an agent: Look around", agent.label.text)
    }

    @Test
    fun `hidden tool search is not counted`() {
        fun rows(search: ToolState): List<ToolRow> {
            var state = reduce(ChatState(cwd = cwd), SessionEvent.UserSent("u1", "hi"))
            state = state.copy(
                entries = state.entries + listOf(
                    assistant(
                        "a1",
                        AssistantBlock.ToolUse("c1", "Read", path("a"), ""),
                        AssistantBlock.ToolUse("s1", "ToolSearch", buildJsonObject { put("query", "select:X") }, ""),
                        AssistantBlock.ToolUse("c2", "Grep", buildJsonObject { put("pattern", "p") }, ""),
                    ),
                    ChatEntry.ToolResult("r1", "c1", "1  x", isError = false),
                    ChatEntry.ToolResult("r2", "c2", "a:1:p", isError = false),
                ) + when (search) {
                    ToolState.DONE -> listOf(ChatEntry.ToolResult("r3", "s1", "loaded", isError = false))
                    ToolState.FAILED -> listOf(ChatEntry.ToolResult("r3", "s1", "no such tools", isError = true))
                    else -> emptyList()
                },
                entryIndex = emptyMap(),
            )
            return ChatRowsBuilder(RowOptions(showThinking = true)).build(state).rows.toolRows()
        }
        for (search in listOf(ToolState.DONE, ToolState.RUNNING)) {
            val row = rows(search).single()
            assertEquals("Read a file, searched for a pattern", row.label.text, "$search")
            assertEquals(listOf("c1", "c2"), row.calls.map { it.id })
            assertEquals("tool:c1", row.key)
            assertEquals(ToolState.DONE, row.state)
        }
        val failed = rows(ToolState.FAILED)
        assertEquals(listOf("Read a", "Failed to load tools", "Searched for p"), failed.map { it.label.text })
        assertEquals(listOf("tool:c1", "tool:s1", "tool:c2"), failed.map { it.key })
    }

    @Test
    fun `enter plan mode never joins a group`() {
        val enter = call("e", "EnterPlanMode", buildJsonObject {})
        val calls = listOf(read("a"), enter, read("b"))
        assertEquals(listOf(listOf(calls[0]), listOf(enter), listOf(calls[2])), Grouping.fold(calls))
        assertEquals(listOf("Read a", "Entered plan mode", "Read b"), labels(calls))
    }

    @Test
    fun `a running group uses the participle and ends with an ellipsis`() {
        val reads = listOf(read("a"), read("b", ToolState.RUNNING))
        assertEquals("Reading 2 files…", Grouping.sentence(reads).text)
        assertEquals(ToolState.RUNNING, Grouping.state(reads))
        assertNull(Grouping.stats(reads))
        val mixed = listOf(bash("a"), edit("e", stats = null, state = ToolState.RUNNING))
        assertEquals("Ran a command, editing a file…", Grouping.sentence(mixed).text)
        assertEquals(ToolState.RUNNING, Grouping.state(mixed))
        assertNull(Grouping.stats(mixed))
        // An edit that finished does not give stats while another call of the group still runs.
        val edits = listOf(edit("a", DiffStats(1, 1)), edit("b", stats = null, state = ToolState.PENDING))
        assertEquals("Editing 2 files…", Grouping.sentence(edits).text)
        assertNull(Grouping.stats(edits))
        // A waiting call makes the group wait; a running Write counts as creating.
        val waiting = listOf(read("a"), call("w", "Write", path("w"), ToolState.WAITING))
        assertEquals("Read a file, creating a file…", Grouping.sentence(waiting).text)
        assertEquals(ToolState.WAITING, Grouping.state(waiting))
    }

    @Test
    fun `stats sum across a group`() {
        assertEquals(DiffStats(3, 1), Grouping.stats(listOf(edit("a", DiffStats(1, 1)), create("b", DiffStats(2, 0)))))
    }

    @Test
    fun `bash cat, find and grep count as read, find and search`() {
        val calls = listOf(bash("1", "cat x"), bash("2", "find ."), bash("3", "grep -rn y"))
        assertEquals("Read a file, found files, searched for a pattern", Grouping.sentence(calls).text)
        assertEquals("Read 2 files", Grouping.sentence(listOf(bash("1", "cat x"), bash("2", "cat y"))).text)
        assertEquals("Ran 2 commands", Grouping.sentence(listOf(bash("1", "make"), bash("2", "make test"))).text)
    }

    @Test
    fun `a single call is not a group`() {
        val x = read("x")
        assertEquals(listOf(listOf(x)), Grouping.fold(listOf(x)))
        val state = ChatState(
            cwd = cwd,
            entries = listOf(
                ChatEntry.User("u1", "hi"),
                assistant("a1", AssistantBlock.ToolUse("c1", "Read", path("a.kt"), "")),
                ChatEntry.ToolResult("r1", "c1", "1  x", isError = false),
            ),
        )
        val row = ChatRowsBuilder(RowOptions(showThinking = true)).build(state).rows.toolRows().single()
        assertEquals("Read a.kt", row.label.text)
        assertEquals("tool:c1", row.key)
        assertEquals(1, row.calls.size)
    }

    @Test
    fun `reads use names only in a reads-only group`() {
        val two = Grouping.sentence(listOf(read("a"), read("b")))
        assertEquals("Read a, b", two.text)
        assertEquals(persistentListOf(5..5, 8..8), two.spans)
        assertEquals("Read 3 files", Grouping.sentence(listOf(read("a"), read("b"), read("c"))).text)
        // One file read twice is one name.
        assertEquals("Read a", Grouping.sentence(listOf(read("a"), call("a2", "Read", path("a")))).text)
    }

    @Test
    fun `missing stats are skipped in the sum`() {
        assertEquals(DiffStats(2, 0), Grouping.stats(listOf(edit("a", stats = null), create("b", DiffStats(2, 0)))))
        assertNull(Grouping.stats(listOf(edit("a", stats = null), edit("b", stats = null))))
    }

    @Test
    fun `Ran echo to stdout and stderr, Failed to run failing command with exit code 3`() {
        val ok = fixtureCall("tools-bash", 18, 23)
        val fail = fixtureCall("tools-bash", 34, 39)
        assertEquals(listOf(listOf(ok), listOf(fail)), Grouping.fold(listOf(ok, fail)))
        assertEquals(listOf("Ran echo to stdout and stderr", "Failed to run failing command with exit code 3"), labels(listOf(ok, fail)))
    }

    @Test
    fun `Failed to use Write, Plan approved`() {
        val write = fixtureCall("plan-disallowed", 102, 103)
        val exit = fixtureCall("plan-disallowed", 172, 178)
        assertEquals(listOf(listOf(write), listOf(exit)), Grouping.fold(listOf(write, exit)))
        assertEquals(listOf("Failed to use Write", "Plan approved"), labels(listOf(write, exit)))
    }

    @Test
    fun `questions and plans never join a group`() {
        val a = read("a")
        val b = read("b")
        val ask = call("ask", "AskUserQuestion", buildJsonObject {})
        val exit = call("exit", "ExitPlanMode", buildJsonObject {})
        assertEquals(listOf(listOf(a), listOf(ask), listOf(b)), Grouping.fold(listOf(a, ask, b)))
        assertEquals(listOf(listOf(a), listOf(exit), listOf(b)), Grouping.fold(listOf(a, exit, b)))
    }

    /** The rows of a turn of four reads of `/p/a.kt` … `/p/d.kt` (all done) with [between] after the second. */
    private fun build(between: AssistantBlock, options: RowOptions = RowOptions(showThinking = true)): List<ChatRow> {
        val files = listOf("a.kt", "b.kt", "c.kt", "d.kt")
        val uses = files.mapIndexed { i, f -> AssistantBlock.ToolUse("c${i + 1}", "Read", path(f), "") }
        val state = ChatState(
            cwd = cwd,
            entries = listOf(ChatEntry.User("u1", "hi"), assistant("a1", uses[0], uses[1], between, uses[2], uses[3])) +
                uses.map { ChatEntry.ToolResult("r-${it.id}", it.id, "1  x", isError = false) },
        )
        return ChatRowsBuilder(options).build(state).rows
    }

    private fun text(row: ChatRow): String = when (row) {
        is ToolRow -> row.label.text
        is TextRow -> row.markdown
        is ThinkingRow -> row.label
        else -> row.key
    }

    private fun assistant(id: String, vararg blocks: AssistantBlock): ChatEntry.Assistant =
        ChatEntry.Assistant(id, id, parentToolUseId = null, model = null, blocks = blocks.toList(), completedBlocks = blocks.size, streaming = false, uuids = emptySet())
}
