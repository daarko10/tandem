package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.Delta
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.StreamEvent
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.reduce
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The rows [ChatRowsBuilder] makes from the fixtures, replayed through the reducer (spec 4.6.5, 5.3, 5.4). */
class ChatRowsBuilderTest {
    private val options = RowOptions(showThinking = true)

    private fun List<ChatRow>.labels(): List<Pair<String, ToolState>> = toolRows().map { it.label.text to it.state }

    @Test
    fun `plain-reply`() {
        val content = replay("plain-reply").second.content()
        assertEquals(2, content.size, content.toString())
        val user = assertIs<UserRow>(content[0])
        assertEquals(PROMPT, user.text)
        assertFalse(user.mono)
        val text = assertIs<TextRow>(content[1])
        assertEquals("pong", text.markdown)
        assertFalse(text.streaming)
    }

    @Test
    fun `permission`() {
        val (_, rows) = replay("permission") { line, _, rows ->
            val tool = rows.content().toolRows().firstOrNull()
            when (line) {
                11 -> {
                    assertEquals(ToolState.PENDING, tool!!.state)
                    assertEquals("Running…", tool.label.text)
                }
                19 -> {
                    assertEquals(ToolState.RUNNING, tool!!.state)
                    assertEquals("Creating empty fixture file", tool.label.text)
                }
                21 -> assertEquals(ToolState.WAITING, tool!!.state)
            }
        }
        val tools = rows.content().toolRows()
        assertEquals(
            listOf("Ran Create empty fixture file" to ToolState.DONE, "Remove fixture file · Denied" to ToolState.DENIED),
            rows.content().labels(),
        )
        assertEquals(ToolBody.None, tools[0].calls.single().body)
        val denial = assertIs<ToolBody.Mono>(tools[1].calls.single().body)
        assertEquals(listOf("The user denied this command."), denial.lines)
        assertFalse(denial.firstLineError)
        assertEquals(listOf("done.", "denied."), rows.content().filterIsInstance<TextRow>().map { it.markdown })
    }

    @Test
    fun `tools-bash`() {
        assertEquals(
            listOf("Ran echo to stdout and stderr" to ToolState.DONE, "Failed to run failing command with exit code 3" to ToolState.FAILED),
            replay("tools-bash").second.content().labels(),
        )
    }

    @Test
    fun `tools-read`() {
        val read = replay("tools-read").second.content()
        assertEquals(
            listOf("Read <path>", "Read <path>:10-14", "Found 1 file", "Searched for seven · 1 match"),
            read.toolRows().map { it.label.text },
        )
    }

    @Test
    fun `tools-edit`() {
        val edit = replay("tools-edit").second.content().toolRows()
        assertEquals(listOf("Read <path>", "Edited <path>", "Created <path>"), edit.map { it.label.text })
        assertEquals(listOf(null, DiffStats(1, 1), DiffStats(2, 0)), edit.map { it.stats })
        assertEquals(listOf(ToolKind.READ, ToolKind.EDIT, ToolKind.CREATE), edit.map { it.calls.single().kind })
        assertEquals(listOf("<path>", "<path>", "<path>"), edit.map { it.calls.single().subject })
    }

    @Test
    fun `ask-single`() {
        val single = replay("ask-single").second.content().toolRows()
        assertEquals(listOf("Questions" to ToolState.DONE), single.map { it.label.text to it.state })
        assertIs<ToolBody.Pairs>(single.single().calls.single().body)
    }

    @Test
    fun `ask-deny`() {
        assertEquals(listOf("Question cancelled" to ToolState.DENIED), replay("ask-deny").second.content().labels())
    }

    @Test
    fun `plan-approve`() {
        assertEquals(
            listOf("Created <path>" to ToolState.DONE, "Plan approved" to ToolState.DONE, "Created <path>" to ToolState.DONE),
            replay("plan-approve").second.content().labels(),
        )
    }

    @Test
    fun `plan-deny`() {
        val deny = replay("plan-deny").second.content()
        assertEquals(
            listOf(
                "Created <path>" to ToolState.DONE,
                "Plan sent back" to ToolState.DENIED,
                "Edited <path>" to ToolState.DONE,
                "Plan approved" to ToolState.DONE,
                "Created <path>" to ToolState.DONE,
            ),
            deny.labels(),
        )
        val sentBack = deny.toolRows()[1].calls.single()
        assertEquals(ToolBody.Mono(kotlinx.collections.immutable.persistentListOf("Feedback: No, keep planning: also add a date"), 1), sentBack.body)
        // An updating Write counts as an edit.
        assertEquals(ToolKind.EDIT, deny.toolRows()[2].calls.single().kind)
    }

    @Test
    fun `plan-disallowed`() {
        val disallowed = replay("plan-disallowed").second.content().labels()
        assertEquals("Failed to use Write" to ToolState.FAILED, disallowed[0])
        assertEquals("Plan approved" to ToolState.DONE, disallowed[1])
    }

    @Test
    fun `agent-sync`() {
        val (_, rows) = replay("agent-sync") { line, _, rows ->
            if (line == 41) {
                val agent = rows.content().toolRows().single { it.calls.single().kind == ToolKind.AGENT }
                assertEquals(ToolState.RUNNING, agent.state)
                assertEquals("1 tool use · 21.5k tokens · 2.0s", agent.calls.single().progress)
            }
        }
        val content = rows.content()
        val agent = content.toolRows().single()
        val call = agent.calls.single()
        assertEquals("Ran an agent: Count lines in seed.txt", agent.label.text)
        assertEquals(ToolState.DONE, agent.state)
        assertEquals("Done (2 tool uses · 21.8k tokens · 5.8s)", call.progress)
        assertTrue(call.children.isNotEmpty())
        assertEquals(
            listOf("Failed to run wc -l seed.txt && tail -c1 seed.txt | xxd", "Ran tail -c1 seed.txt | od -c"),
            call.children.toolRows().map { it.label.text },
        )
        assertEquals(ToolBody.Markdown("7 lines. The last line ends with a trailing newline.", null), call.body)
        assertTrue(content.none { it.key.startsWith("t:<id-12>/") || it.key == "tool:<id-49>" || it.key == "tool:<id-56>" }, content.toString())
    }

    @Test
    fun `agent-forwarded`() {
        val forwarded = replay("agent-forwarded").second.content()
        val forwardedCall = forwarded.toolRows().single().calls.single()
        assertTrue(forwardedCall.children.any { it is TextRow && it.markdown == "7" })
        assertTrue(forwarded.none { it is TextRow && it.markdown == "7" })
        assertEquals(ToolBody.None, forwardedCall.body)
    }

    @Test
    fun `think-display-summarized`() {
        var sawStreaming = false
        val (_, summarized) = replay("think-display-summarized") { _, _, rows ->
            rows.content().filterIsInstance<ThinkingRow>().filter { it.streaming }.forEach {
                sawStreaming = true
                assertTrue(it.label.startsWith("Thinking…"), it.label)
            }
        }
        assertTrue(sawStreaming)
        val thought = summarized.content().filterIsInstance<ThinkingRow>().single()
        assertEquals("Thought for 2s", thought.label)
        assertTrue(thought.summary.startsWith("47×83"), thought.summary)
        assertFalse(thought.streaming)
    }

    @Test
    fun `think-default`() {
        val (_, default) = replay("think-default") { line, _, rows ->
            val thinking = rows.content().filterIsInstance<ThinkingRow>().singleOrNull()
            when (line) {
                8 -> {
                    assertEquals("Thinking…", thinking!!.label)
                    assertTrue(thinking.streaming)
                }
                9 -> assertEquals("Thinking… · 50 tokens", thinking!!.label)
                14 -> assertEquals("Thinking… · 285 tokens", thinking!!.label)
            }
        }
        val done = default.content().filterIsInstance<ThinkingRow>().single()
        assertEquals("Thought for 2s", done.label)
        assertEquals("", done.summary)
    }

    @Test
    fun `a thinking block without a duration reads Thinking`() {
        fun rowsOf(vararg blocks: AssistantBlock) = ChatRowsBuilder(options).build(
            ChatState(entries = listOf(ChatEntry.User("u1", "hi"), assistant("a1", *blocks))),
        ).rows
        assertEquals("Thinking", rowsOf(AssistantBlock.Thinking("x")).filterIsInstance<ThinkingRow>().single().label)
        assertTrue(rowsOf(AssistantBlock.Thinking("")).none { it is ThinkingRow })
        // The host's clock gives a duration when the line has none.
        val clocked = AssistantBlock.Thinking("x", startedAtMillis = 1_000, completedAtMillis = 4_400)
        assertEquals("Thought for 3s", rowsOf(clocked).filterIsInstance<ThinkingRow>().single().label)
        assertEquals("Thought for 3s", rowsOf(AssistantBlock.Thinking("", durationMs = 2_500)).filterIsInstance<ThinkingRow>().single().label)
    }

    @Test
    fun `a retraction drops the cached rows`() {
        val builder = ChatRowsBuilder(options)
        var state = ChatState()
        Fixtures.lines("plain-reply").forEachIndexed { i, line ->
            val message = Ndjson.decode(line)
            if (message is CliMessage.SystemInit) state = reduce(state, SessionEvent.UserSent("u${i + 1}", PROMPT))
            state = reduce(state, SessionEvent.Cli(message))
            builder.build(state)
        }
        val reply = state.entries.filterIsInstance<ChatEntry.Assistant>().single()
        val key = "t:${reply.id}:0"
        assertTrue(builder.build(state).rows.any { it.key == key })
        val refusal = Ndjson.decode(
            """{"type":"system","subtype":"model_refusal_fallback","original_model":"claude-sonnet-5-5","fallback_model":"claude-opus-5-5",""" +
                """"scope":"session","retracted_message_uuids":["${reply.uuids.single()}"]}""",
        )
        state = reduce(state, SessionEvent.Cli(refusal))
        val after = builder.build(state)
        assertTrue(after.rows.none { it.key == key })
        assertEquals(ChatRowsBuilder(options).build(state), after)
    }

    @Test
    fun `a retraction in an earlier turn drops the cache`() {
        val builder = ChatRowsBuilder(options)
        var state = ChatState()
        for (i in 1..3) {
            state = reduce(state, SessionEvent.UserSent("u$i", "p$i"))
            builder.build(state)
            state = reduce(state, cliLine("""{"type":"assistant","uuid":"a$i","message":{"id":"m$i","content":[{"type":"text","text":"r$i"}]}}"""))
            builder.build(state)
        }
        state = reduce(state, cliLine("""{"type":"system","subtype":"model_refusal_fallback","original_model":"a","fallback_model":"b","retracted_message_uuids":["a1"]}"""))
        val after = builder.build(state)
        assertTrue(after.rows.none { it.key == "t:m1:0" })
        assertEquals(ChatRowsBuilder(options).build(state), after)
    }

    @Test
    fun `misc-image`() {
        val image = replay("misc-image").second.content().toolRows()
        assertEquals("Read <path>" to ToolState.DONE, image[0].label.text to image[0].state)
        assertEquals(ToolBody.Image(8, 8, "image/png"), image[0].calls.single().body)
    }

    @Test
    fun `misc-web`() {
        val (_, web) = replay("misc-web") { _, _, rows ->
            assertTrue(rows.rows.toolRows().none { row -> row.calls.any { it.kind == ToolKind.TOOL_SEARCH } })
            assertTrue(rows.rows.none { it is ToolRow && it.label.text.isEmpty() || it is ThinkingRow && it.label.isEmpty() })
        }
        val labels = web.content().toolRows().map { it.label.text }
        assertEquals(2, labels.size, labels.toString())
        assertTrue(labels[0].startsWith("Fetched "))
        assertTrue(labels[1].startsWith("Searched the web for"))
    }

    @Test
    fun `a failed tool search is a row`() {
        val search = AssistantBlock.ToolUse("s1", "ToolSearch", buildJsonObject { put("query", "select:X") }, "")
        val state = ChatState(
            entries = listOf(
                ChatEntry.User("u1", "hi"),
                assistant("a1", search),
                ChatEntry.ToolResult("r1", "s1", "no such tools", isError = true),
            ),
        )
        val row = ChatRowsBuilder(options).build(state).rows.toolRows().single()
        assertEquals("Failed to load tools" to ToolState.FAILED, row.label.text to row.state)
        assertEquals("tool:s1", row.key)
    }

    @Test
    fun `invalid-model`() {
        val invalid = replay("invalid-model").second.content()
        val failed = invalid.filterIsInstance<NoticeRow>().single { it.text.startsWith("The turn failed") }
        assertEquals(NoticeLevel.ERROR, failed.level)
    }

    @Test
    fun `interrupt`() {
        val interrupt = replay("interrupt").second.content()
        assertTrue(interrupt.toolRows().none { it.state == ToolState.PENDING || it.state == ToolState.RUNNING })
        assertTrue(interrupt.none { it is NoticeRow && it.text == "Stopped." })
        assertEquals(listOf("1"), interrupt.filterIsInstance<TextRow>().map { it.markdown })
    }

    @Test
    fun `keys are unique and stable across every state of every fixture`() {
        for (name in Fixtures.names()) {
            var before: List<ChatRow> = emptyList()
            replay(name) { line, _, rows ->
                val keys = rows.rows.map { it.key }
                assertEquals(keys.distinct(), keys, "$name:$line duplicate keys")
                val now = keys.toSet()
                val callsNow = rows.rows.toolRows().flatMap { row -> row.calls.map { it.id } }.toSet()
                for (old in before.map { it.key }) {
                    // 5.3's one exception: a call that leaves its group moves the calls after it to a new row; they are still shown.
                    val moved = old.startsWith("tool:") && old.removePrefix("tool:") in callsNow
                    assertTrue(old in now || moved, "$name:$line lost the key $old")
                }
                before = rows.rows
            }
        }
    }

    @Test
    fun `previous turns return the same row instances`() {
        val reloaded = runBlocking { TranscriptReader.read(Fixtures.transcript("history-reply")) }.state
        val history = ChatRowsBuilder(options).build(reloaded).rows.size
        assertTrue(history > 0)
        var first: List<ChatRow>? = null
        replay("plain-reply", initial = reloaded) { line, _, rows ->
            val head = rows.rows.take(history)
            val seen = first
            if (seen == null) {
                first = head
            } else {
                head.forEachIndexed { i, row -> assertSame(seen[i], row, "line $line row $i") }
            }
        }
        assertNotNull(first)
    }

    @Test
    fun `unchanged rows of the live turn keep their instances across a text delta`() {
        var previous: List<ToolRow> = emptyList()
        var deltas = 0
        replay("tools-read") { line, _, rows ->
            val tools = rows.rows.toolRows()
            val message = fixtureLine("tools-read", line)
            val event = (message as? CliMessage.StreamEvent)?.event
            if (event is StreamEvent.ContentBlockDelta && event.delta is Delta.Text) {
                deltas++
                assertTrue(previous.isNotEmpty())
                previous.forEach { old -> assertSame(old, tools.single { it.key == old.key }, "line $line ${old.key}") }
            }
            previous = tools
        }
        assertTrue(deltas > 0)
    }

    @Test
    fun `showThinking false drops thinking rows without changing groups`() {
        val shown = replay("tools-read-unavailable").second.content()
        val hidden = replay("tools-read-unavailable", options = RowOptions(showThinking = false)).second.content()
        assertTrue(shown.any { it is ThinkingRow })
        assertTrue(hidden.none { it is ThinkingRow })
        assertEquals(shown.toolRows().map { it.key to it.label }, hidden.toolRows().map { it.key to it.label })
    }

    @Test
    fun `a result without a matching call renders a Used a tool row`() {
        val state = ChatState(entries = listOf(ChatEntry.User("u1", "hi"), ChatEntry.ToolResult("r1", toolUseId = "orphan", text = "x", isError = false)))
        val row = ChatRowsBuilder(options).build(state).rows.toolRows().single()
        assertEquals("Used a tool", row.label.text)
        assertEquals(ToolState.DONE, row.state)
        assertEquals(ToolBody.Mono(kotlinx.collections.immutable.persistentListOf("x"), 1), row.calls.single().body)
        val failed = ChatState(entries = listOf(ChatEntry.ToolResult("r2", toolUseId = "lost", text = "boom", isError = true)))
        val failedRow = ChatRowsBuilder(options).build(failed).rows.toolRows().single()
        assertEquals("Failed to use a tool" to ToolState.FAILED, failedRow.label.text to failedRow.state)
    }

    @Test
    fun `gaps follow the previous row`() {
        val read = buildJsonObject { put("file_path", "/p/a.kt") }
        val entries = listOf(
            ChatEntry.User("u1", "hi"),
            assistant(
                "a1",
                AssistantBlock.Thinking("t", durationMs = 1_000),
                AssistantBlock.ToolUse("c1", "Read", read, ""),
                AssistantBlock.ToolUse("c2", "Read", read, ""),
                AssistantBlock.Thinking("u", durationMs = 1_000),
                AssistantBlock.Text("x"),
                AssistantBlock.ToolUse("c3", "Read", read, ""),
                AssistantBlock.Text("y"),
                AssistantBlock.Text("z"),
            ),
            ChatEntry.Notice("notice-0", NoticeLevel.INFO, "n"),
            assistant("a2", AssistantBlock.Text("after notice")),
        )
        var state = ChatState(entries = entries, noticeCount = 1)
        state = reduce(state, SessionEvent.UserSent("u2", "/compact"))
        state = reduce(state, SessionEvent.HostNotice(NoticeLevel.WARNING, "w"))
        val rows = ChatRowsBuilder(options).build(state).rows
        assertEquals(
            listOf(
                "u:u1" to RowGap.NONE,
                "th:a1:0" to RowGap.AFTER_BUBBLE,
                "tool:c1" to RowGap.ROW,
                "tool:c2" to RowGap.ROW,
                "th:a1:3" to RowGap.ROW,
                "t:a1:4" to RowGap.BEFORE_TEXT,
                "tool:c3" to RowGap.SECTION,
                "t:a1:6" to RowGap.BEFORE_TEXT,
                "t:a1:7" to RowGap.SECTION,
                "n:notice-0" to RowGap.SECTION,
                "t:a2:0" to RowGap.SECTION,
                "u:u2" to RowGap.TURN,
                "n:${state.entries.last().id}" to RowGap.SECTION,
            ),
            rows.map { it.key to it.gap },
        )
        assertTrue(rows.filterIsInstance<UserRow>().single { it.key == "u:u2" }.mono)
        assertFalse(rows.filterIsInstance<UserRow>().single { it.key == "u:u1" }.mono)
        assertTrue(ChatRowsBuilder(options).build(ChatState(entries = listOf(ChatEntry.User("u", "!ls")))).rows.filterIsInstance<UserRow>().single().mono)

        val invalid = replay("invalid-model").second.content()
        val firstNotice = invalid.indexOfFirst { it is NoticeRow }
        assertIs<UserRow>(invalid[firstNotice - 1])
        assertEquals(RowGap.SECTION, invalid[firstNotice].gap)
        assertEquals(RowGap.NONE, invalid.first().gap)
    }

    @Test
    fun `building 32000 events stays linear`() {
        val events = (1..16_000).flatMap { i ->
            listOf(
                SessionEvent.UserSent("u$i", "prompt $i"),
                cliLine("""{"type":"assistant","uuid":"a$i","message":{"id":"m$i","model":"claude-sonnet-5-5","content":[{"type":"text","text":"reply $i"}]}}"""),
            )
        }
        val states = ArrayList<ChatState>(events.size)
        events.fold(ChatState()) { state, event -> reduce(state, event).also(states::add) }
        val builder = ChatRowsBuilder(options)
        var built: ChatRows? = null
        val millis = measureTimeMillis { for (state in states) built = builder.build(state) }
        val last = built!!
        assertEquals(32_000, last.rows.size)
        assertEquals(listOf("u:u16000", "t:m16000:0"), last.rows.takeLast(2).map { it.key })
        assertTrue(millis < 2_000, "building took $millis ms")
    }

    @Test
    fun `a late result reopens its cached turn`() {
        val read = buildJsonObject { put("file_path", "/p/a.kt") }
        val builder = ChatRowsBuilder(options)
        var state = ChatState(cwd = "/p")
        state = reduce(state, SessionEvent.UserSent("u1", "one"))
        state = state.withEntry(assistant("a1", AssistantBlock.ToolUse("c1", "Read", read, "")))
        builder.build(state)
        state = reduce(state, SessionEvent.UserSent("u2", "two"))
        state = state.withEntry(assistant("a2", AssistantBlock.Text("reply")))
        state = state.withEntry(assistant("a3", AssistantBlock.Text("more")))
        val interrupted = builder.build(state)
        assertEquals(listOf("Read a.kt · Interrupted" to ToolState.INTERRUPTED), interrupted.rows.labels())
        state = state.withEntry(ChatEntry.ToolResult("result-c1", "c1", "1  x", isError = false))
        val late = builder.build(state)
        assertEquals(listOf("Read a.kt" to ToolState.DONE), late.rows.labels())
        assertEquals(ChatRowsBuilder(options).build(state), late)
        // The rows of the reopened turn that did not change keep their instances.
        assertSame(interrupted.rows.first { it.key == "u:u1" }, late.rows.first { it.key == "u:u1" })
        assertSame(late.rows.first { it.key == "t:a2:0" }, builder.build(state).rows.first { it.key == "t:a2:0" })
    }

    @Test
    fun `a background agent's lines in a later turn stay inside its call`() {
        val builder = ChatRowsBuilder(options)
        var state = ChatState()
        state = reduce(state, SessionEvent.UserSent("u1", "one"))
        state = state.withEntry(assistant("a1", AssistantBlock.ToolUse("ag", "Agent", buildJsonObject { put("description", "Look around") }, "")))
        state = state.withEntry(ChatEntry.ToolResult("result-ag", "ag", "launched", isError = false))
        builder.build(state)
        state = reduce(state, SessionEvent.UserSent("u2", "two"))
        state = state.withEntry(assistant("a2", AssistantBlock.Text("reply")))
        builder.build(state)
        val sub = ChatEntry.Assistant("ag/s1", "s1", "ag", null, listOf(AssistantBlock.Text("found it")), 1, false, emptySet())
        state = state.withEntry(sub)
        val rows = builder.build(state)
        val agent = rows.rows.toolRows().single().calls.single()
        assertTrue(agent.children.any { it is TextRow && it.markdown == "found it" })
        assertTrue(rows.rows.none { it is TextRow && it.markdown == "found it" })
        assertEquals(ChatRowsBuilder(options).build(state), rows)
    }

    @Test
    fun `an entry of an unknown agent is built at top level`() {
        val stray = ChatEntry.Assistant(
            "x/s1", "s1", "x", null,
            listOf(AssistantBlock.Thinking("hmm", durationMs = 1_000), AssistantBlock.Text("stray")), 2, false, emptySet(),
        )
        val strayResult = ChatEntry.ToolResult("result-y", "y", "out", isError = false, parentToolUseId = "x")
        val rows = ChatRowsBuilder(options).build(ChatState(entries = listOf(ChatEntry.User("u1", "hi"), stray, strayResult))).rows
        assertEquals(listOf("u:u1", "t:x/s1:1", "tool:y"), rows.map { it.key })
    }

    @Test
    fun `repeated keys get a suffix`() {
        val state = ChatState(
            entries = listOf(
                ChatEntry.User("u1", "hi"),
                ChatEntry.ToolResult("result-z", "z", "a", isError = false),
                ChatEntry.ToolResult("result-z", "z", "b", isError = true),
                ChatEntry.User("u1", "again"),
            ),
        )
        val rows = ChatRowsBuilder(options).build(state).rows
        assertEquals(listOf("u:u1", "tool:z", "tool:z#2", "u:u1#2"), rows.map { it.key })
        assertEquals(listOf("Used a tool" to ToolState.DONE, "Failed to use a tool" to ToolState.FAILED), rows.labels())
    }

    @Test
    fun `a new working directory rebuilds the names`() {
        val read = buildJsonObject { put("file_path", "/p/q/a.kt") }
        val builder = ChatRowsBuilder(options)
        var state = ChatState(cwd = "/p")
        state = reduce(state, SessionEvent.UserSent("u1", "one"))
        state = state.withEntry(assistant("a1", AssistantBlock.ToolUse("c1", "Read", read, "")))
        state = state.withEntry(ChatEntry.ToolResult("result-c1", "c1", "x", isError = false))
        state = reduce(state, SessionEvent.UserSent("u2", "two"))
        assertEquals("Read q/a.kt", builder.build(state).rows.toolRows().single().label.text)
        assertEquals("Read a.kt", builder.build(state.copy(cwd = "/p/q")).rows.toolRows().single().label.text)
    }

    /** [state] with [entry] appended as the reducer would, for entries no fixture line makes. */
    private fun ChatState.withEntry(entry: ChatEntry): ChatState = copy(entries = entries + entry, entryIndex = emptyMap())

    private fun cliLine(json: String): SessionEvent = SessionEvent.Cli(Ndjson.decode(json))

    private fun assistant(id: String, vararg blocks: AssistantBlock): ChatEntry.Assistant =
        ChatEntry.Assistant(id, id, parentToolUseId = null, model = null, blocks = blocks.toList(), completedBlocks = blocks.size, streaming = false, uuids = emptySet())
}
