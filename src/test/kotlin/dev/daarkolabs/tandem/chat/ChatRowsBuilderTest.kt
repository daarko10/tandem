package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ControlPayloads
import dev.daarkolabs.tandem.protocol.Delta
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.StreamEvent
import dev.daarkolabs.tandem.protocol.UsageAnswer
import dev.daarkolabs.tandem.protocol.string
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.EditOutcome
import dev.daarkolabs.tandem.session.EditPhase
import dev.daarkolabs.tandem.session.EditReview
import dev.daarkolabs.tandem.session.McpResult
import dev.daarkolabs.tandem.session.NoticeKind
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.PendingSend
import dev.daarkolabs.tandem.session.PendingStage
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.ShellEnd
import dev.daarkolabs.tandem.session.TurnEnd
import dev.daarkolabs.tandem.session.UsagePart
import dev.daarkolabs.tandem.session.UserImage
import dev.daarkolabs.tandem.session.reduce
import dev.daarkolabs.tandem.session.settled
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The rows [ChatRowsBuilder] makes from the fixtures, replayed through the reducer (spec 4.6.5, 5.3, 5.4). */
class ChatRowsBuilderTest {
    private val options = RowOptions(showThinking = true)

    private fun List<ChatRow>.labels(): List<Pair<String, ToolState>> = toolRows().map { it.label.text to it.state }

    /** The row of the lone Agent call. */
    private fun agent(rows: ChatRows): ToolRow = rows.rows.toolRows().single { it.calls.singleOrNull()?.kind == ToolKind.AGENT }

    @Test
    fun `a user row lists its images in order`() {
        val images = listOf(UserImage("u1#0", "image/png", "A"), UserImage("u1#1", "image/jpeg", "B"))
        val state = ChatState(entries = listOf(ChatEntry.User("u1", "", images = images)))
        val user = assertIs<UserRow>(ChatRowsBuilder(options).build(state).rows.first())
        assertEquals(listOf(ImageRef("u1#0", "image/png", 0), ImageRef("u1#1", "image/jpeg", 1)), user.images)
        assertEquals("", user.text)
    }

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
        assertEquals(ToolBody.Command("touch tandem-fixture.txt", persistentListOf(), 0, false), tools[0].calls.single().body)
        val denial = assertIs<ToolBody.Mono>(tools[1].calls.single().body)
        assertEquals(listOf("The user denied this command."), denial.lines)
        assertFalse(denial.firstLineError)
        assertEquals(listOf("done.", "denied."), rows.content().filterIsInstance<TextRow>().map { it.markdown })
    }

    @Test
    fun `a pending call has no body`() {
        var checked = 0
        replay("tools-read") { line, _, rows ->
            val call = rows.content().toolRows().singleOrNull()?.calls?.singleOrNull()
            when (line) {
                12 -> {
                    assertEquals(ToolState.PENDING to ToolBody.None, call!!.state to call.body)
                    checked++
                }
                17 -> {
                    assertEquals(ToolState.RUNNING to ToolBody.None, call!!.state to call.body)
                    checked++
                }
            }
        }
        assertEquals(2, checked)
    }

    @Test
    fun `a waiting plan has no body`() {
        var checked = false
        replay("plan-approve") { line, _, rows ->
            if (line == 88) {
                val plan = rows.content().toolRows().flatMap { it.calls }.last { it.kind == ToolKind.PLAN }
                assertEquals(ToolState.WAITING to ToolBody.None, plan.state to plan.body)
                checked = true
            }
        }
        assertTrue(checked)
    }

    @Test
    fun `an interrupted call keeps no body`() {
        var running: ChatState? = null
        replay("permission") { line, state, rows ->
            if (line == 19) {
                val call = rows.content().toolRows().single().calls.single()
                assertEquals(ToolState.RUNNING, call.state)
                assertIs<ToolBody.Command>(call.body)
                running = state
            }
        }
        val result = Fixtures.decode("interrupt").filterIsInstance<CliMessage.Result>().single()
        val interrupted = reduce(running!!, SessionEvent.Cli(result))
        val call = ChatRowsBuilder(options).build(interrupted).content().toolRows().single().calls.single()
        assertEquals(ToolState.INTERRUPTED to ToolBody.None, call.state to call.body)
    }

    @Test
    fun `a call's body appears once its input completes`() {
        var started: Int? = null
        var complete = false
        replay("permission") { line, _, rows ->
            val call = rows.content().toolRows().firstOrNull()?.calls?.single()
            val event = (fixtureLine("permission", line) as? CliMessage.StreamEvent)?.event
            if (line > 8 && started == null && event is StreamEvent.ContentBlockStart) {
                started = line
                assertEquals(ToolState.PENDING to ToolBody.None, call!!.state to call.body)
            }
            if (line == 19) {
                assertEquals(
                    ToolState.RUNNING to ToolBody.Command("touch tandem-fixture.txt", persistentListOf(), 0, false),
                    call!!.state to call.body,
                )
                complete = true
            }
        }
        assertNotNull(started)
        assertTrue(complete)
    }

    @Test
    fun `unchanged calls of a live group keep their instances across a result`() {
        var at46: List<ToolCall>? = null
        var at50: List<ToolCall>? = null
        replay("tools-read") { line, _, rows ->
            val calls = rows.content().toolRows().flatMap { it.calls }
            if (line == 46) at46 = calls
            if (line == 50) at50 = calls
        }
        val before = at46!!
        val after = at50!!
        val reads = before.filter { it.kind == ToolKind.READ }
        assertEquals(2, reads.size, before.toString())
        reads.forEach { read -> assertSame(read, after.single { it.id == read.id }, read.id) }
        val glob = before.single { it.kind == ToolKind.FIND }
        val done = after.single { it.id == glob.id }
        assertNotSame(glob, done)
        assertEquals(ToolState.DONE, done.state)
    }

    @Test
    fun `a running call's body is built once per input`() {
        val states = HashMap<Int, ChatState>()
        replay("permission") { line, state, _ -> if (line == 19 || line == 21) states[line] = state }
        fun input(state: ChatState): JsonObject? = state.entries.filterIsInstance<ChatEntry.Assistant>()
            .flatMap { it.blocks }.filterIsInstance<AssistantBlock.ToolUse>().single().input
        assertSame(input(states.getValue(19)), input(states.getValue(21)))
        val b = ChatRowsBuilder(RowOptions(true))
        val call19 = b.build(states.getValue(19)).content().toolRows().single().calls.single()
        val call21 = b.build(states.getValue(21)).content().toolRows().single().calls.single()
        assertEquals(ToolState.RUNNING, call19.state)
        assertEquals(ToolState.WAITING, call21.state)
        assertNotSame(call19, call21)
        assertSame(call19.body, call21.body)
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
        assertEquals(3, read.size, read.toString())
        assertIs<UserRow>(read[0])
        val group = assertIs<ToolRow>(read[1])
        assertEquals("Read a file, found files, searched for a pattern" to ToolState.DONE, group.label.text to group.state)
        assertEquals(
            listOf("Read <path>", "Read <path>:10-14", "Found 1 file", "Searched for seven · 1 match"),
            group.calls.map { it.label.text },
        )
        assertEquals("tool:${group.calls.first().id}", group.key)
        assertNull(group.stats)
        assertEquals("done.", assertIs<TextRow>(read[2]).markdown)
    }

    @Test
    fun `tools-edit`() {
        val group = replay("tools-edit").second.content().toolRows().single()
        assertEquals("Read a file, edited a file, created <path>" to ToolState.DONE, group.label.text to group.state)
        assertEquals(DiffStats(3, 1), group.stats)
        assertEquals(listOf("Read <path>", "Edited <path>", "Created <path>"), group.calls.map { it.label.text })
        assertEquals(listOf(null, DiffStats(1, 1), DiffStats(2, 0)), group.calls.map { it.stats })
        assertEquals(listOf(ToolKind.READ, ToolKind.EDIT, ToolKind.CREATE), group.calls.map { it.kind })
        assertEquals(listOf("<path>", "<path>", "<path>"), group.calls.map { it.subject })
    }

    @Test
    fun `tools-read-unavailable`() {
        val content = replay("tools-read-unavailable").second.content()
        assertEquals(6, content.size, content.toString())
        assertIs<UserRow>(content[0])
        val reads = assertIs<ToolRow>(content[1])
        assertEquals("Read <path>" to ToolState.DONE, reads.label.text to reads.state)
        assertEquals(listOf("<id-280>", "<id-299>"), reads.calls.map { it.id })
        val glob = assertIs<ToolRow>(content[2])
        assertEquals("Failed to use Glob" to ToolState.FAILED, glob.label.text to glob.state)
        assertEquals(1, glob.calls.size)
        assertIs<ThinkingRow>(content[3])
        val bash = assertIs<ToolRow>(content[4])
        assertEquals("Found files, searched for a pattern" to ToolState.DONE, bash.label.text to bash.state)
        assertEquals(2, bash.calls.size)
        assertEquals("done.", assertIs<TextRow>(content[5]).markdown)
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
    fun `an approved plan without plan text shows the plan its card showed`() {
        // plan-disallowed with line 102's Write aimed at a plans directory: the card's plan is that draft (spec 4.14), and so is the
        // body of the `Plan approved` row, whose result carries `plan: null` (spec 4.6.2).
        val lines = Fixtures.lines("plan-disallowed").mapIndexed { i, text ->
            if (i == 101) text.replace("\"file_path\":\"<path>\"", "\"file_path\":\"/home/u/.claude/plans/x.md\"") else text
        }
        val draft = assertNotNull(inputAt("plan-disallowed", 102).string("content"))
        val approved = replay("plan-disallowed", lines = lines).second.content().toolRows().single { it.label.text == "Plan approved" }
        assertEquals(ToolBody.Markdown(draft, null), approved.calls.single().body)
        // Without a draft the body is the CLI's answer, as before.
        val unpatched = replay("plan-disallowed").second.content().toolRows().single { it.label.text == "Plan approved" }
        assertIs<ToolBody.Mono>(unpatched.calls.single().body)

        // No plan file: Claude's last text before the call, as the card's ASSISTANT_TEXT source.
        val exit = AssistantBlock.ToolUse("x1", "ExitPlanMode", JsonObject(emptyMap()), "")
        val state = ChatState(
            entries = listOf(
                ChatEntry.User("u1", "plan it"),
                assistant("a1", AssistantBlock.Text("## Plan\n1. Do it"), exit),
                ChatEntry.ToolResult("r1", "x1", "User has approved exiting plan mode. You can now proceed.", isError = false),
            ),
        )
        val row = ChatRowsBuilder(options).build(state).rows.toolRows().single()
        assertEquals(ToolBody.Markdown("## Plan\n1. Do it", null), row.calls.single().body)
    }

    @Test
    fun `agent-sync`() {
        var after41 = false
        var after43 = false
        var after50 = false
        val (state, rows) = replay("agent-sync") { line, s, rows ->
            when (line) {
                41 -> {
                    val agent = agent(rows)
                    assertEquals(ToolState.RUNNING, agent.state)
                    assertEquals("1 tool use · 21.5k tokens · 2.0s", agent.calls.single().progress)
                    val working = assertIs<WorkingRow>(rows.rows.last())
                    assertEquals("Running wc -l seed.txt && tail -c1 seed.txt | xxd", working.step)
                    // No main-thread tool_use since the Agent's: the verb the working row returns to when the Agent row is open.
                    val turn = assertNotNull(s.turn)
                    assertEquals("<id-12>", turn.lastToolUseId)
                    assertEquals(WorkingIndicator.verb(turn.userUuid, turn.lastToolUseId), working.expandedStep)
                    after41 = true
                }
                43 -> {
                    val agent = agent(rows)
                    val child = agent.calls.single().children.toolRows().single { it.calls.single().id == "<id-49>" }
                    assertEquals(ToolState.WAITING, child.state)
                    assertEquals(ToolState.RUNNING, agent.state)
                    assertEquals("Count lines in seed.txt", assertNotNull(Cards.pending(s).first).agentDescription)
                    val working = assertIs<WorkingRow>(rows.rows.last())
                    assertTrue(working.waiting)
                    after43 = true
                }
                50 -> {
                    // The task has ended: the step is no longer the agent's.
                    assertNull(assertIs<WorkingRow>(rows.rows.last()).expandedStep)
                    after50 = true
                }
            }
        }
        assertTrue(after41 && after43 && after50)
        val content = rows.content()
        val agent = content.toolRows().single()
        val call = agent.calls.single()
        assertEquals("Ran an agent: Count lines in seed.txt", agent.label.text)
        assertEquals(ToolState.DONE, agent.state)
        assertEquals("Done (2 tool uses · 21.8k tokens · 5.8s)", call.progress)
        assertTrue(rows.rows.none { it is WorkingRow })
        assertEquals(
            listOf(
                "Failed to run wc -l seed.txt && tail -c1 seed.txt | xxd" to ToolState.FAILED,
                "Ran tail -c1 seed.txt | od -c" to ToolState.DONE,
            ),
            call.children.map { (it as ToolRow).label.text to it.state },
        )
        assertEquals(ToolBody.Markdown("7 lines. The last line ends with a trailing newline.", null), call.body)
        // No top-level text or tool row comes from a subagent's entry.
        val subagent = state.entries.filter { it is ChatEntry.Assistant && it.parentToolUseId != null || it is ChatEntry.ToolResult && it.parentToolUseId != null }
        assertTrue(subagent.isNotEmpty())
        val textPrefixes = subagent.filterIsInstance<ChatEntry.Assistant>().map { "t:${it.id}:" }
        val callIds = subagent.flatMap { entry ->
            when (entry) {
                is ChatEntry.Assistant -> entry.blocks.filterIsInstance<AssistantBlock.ToolUse>().map { it.id }
                is ChatEntry.ToolResult -> listOf(entry.toolUseId)
                else -> emptyList()
            }
        }.toSet()
        assertTrue(
            rows.rows.none { row ->
                row is TextRow && textPrefixes.any { row.key.startsWith(it) } || row is ToolRow && row.calls.any { it.id in callIds }
            },
            content.toString(),
        )
    }

    @Test
    fun `agent-forwarded`() {
        val forwarded = replay("agent-forwarded").second.content()
        val forwardedCall = forwarded.toolRows().single().calls.single()
        val children = forwardedCall.children
        assertEquals(listOf(ToolRow::class, TextRow::class), children.map { it::class })
        assertEquals("Ran wc -l <path>", (children[0] as ToolRow).label.text)
        assertEquals("7", (children[1] as TextRow).markdown)
        assertTrue(forwarded.none { it is TextRow && it.markdown == "7" })
        assertEquals(ToolBody.None, forwardedCall.body)
    }

    @Test
    fun `a progress line with missing usage shows no sub-line`() {
        // agent-sync:41, the first task_progress, without its usage.
        val lines = Fixtures.lines("agent-sync").mapIndexed { i, l ->
            if (i == 40) JsonObject(Json.parseToJsonElement(l).jsonObject - "usage").toString() else l
        }
        var after41: String? = "unset"
        var after50: String? = null
        replay("agent-sync", lines = lines) { line, _, rows ->
            if (line == 41) after41 = agent(rows).calls.single().progress
            if (line == 50) after50 = agent(rows).calls.single().progress
        }
        assertNull(after41)
        assertEquals("Done (2 tool uses · 21.8k tokens · 5.8s)", after50)
    }

    @Test
    fun `a subagent entry whose agent call is unknown is not dropped`() {
        val s = replay("agent-sync").first
        val stray = ChatEntry.Assistant(
            id = "stray", messageId = "stray", parentToolUseId = "nope", model = null, blocks = listOf(AssistantBlock.Text("stray")),
            completedBlocks = 1, streaming = false, uuids = emptySet(), agentId = "x",
        )
        val rows = ChatRowsBuilder(options).build(s.copy(entries = s.entries + stray, entryIndex = emptyMap())).rows
        assertTrue(rows.any { it is TextRow && it.markdown == "stray" }, rows.toString())
    }

    @Test
    fun `subagent calls are never grouped`() {
        // agent-sync:44, the subagent's first Bash result, made a success: two successful calls in a row would group on the main thread.
        val lines = Fixtures.lines("agent-sync").mapIndexed { i, l ->
            if (i != 43) {
                l
            } else {
                val line = Json.parseToJsonElement(l).jsonObject
                val message = line.getValue("message").jsonObject
                val content = message.getValue("content").jsonArray
                val result = JsonObject(content[0].jsonObject + ("is_error" to JsonPrimitive(false)) + ("content" to JsonPrimitive("7 seed.txt")))
                JsonObject(line + ("message" to JsonObject(message + ("content" to JsonArray(listOf(result) + content.drop(1)))))).toString()
            }
        }
        val children = agent(replay("agent-sync", lines = lines).second).calls.single().children
        assertEquals(2, children.size)
        assertTrue(children.all { it is ToolRow && it.calls.size == 1 }, children.toString())
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
        val invalid = replay("invalid-model").second.rows
        // The model rejection stays above the footer; the footer names the API error, and the "The turn failed" notice follows it,
        // since its kind and the CLI's sentence are not in the footer (spec 4.10, 4.11).
        val (rejected, failed) = invalid.filterIsInstance<NoticeRow>().also { assertEquals(2, it.size, invalid.toString()) }
        assertEquals(NoticeLevel.ERROR, rejected.level)
        assertTrue(rejected.text.startsWith("The CLI rejected the model"), rejected.text)
        assertEquals(TurnEnd.FAILED to "API Error: 404 model_not_found", assertIs<TurnEndRow>(invalid[invalid.size - 2]).let { it.end to it.text })
        assertSame(failed, invalid.last())
        assertEquals(NoticeLevel.ERROR, failed.level)
        assertTrue(failed.text.startsWith("The turn failed (api_error): There's an issue with the selected model"), failed.text)
    }

    @Test
    fun `interrupt`() {
        val (_, rows) = replay("interrupt")
        val interrupt = rows.content()
        assertTrue(interrupt.toolRows().none { it.state == ToolState.PENDING || it.state == ToolState.RUNNING })
        assertTrue(interrupt.none { it is NoticeRow && it.text == "Stopped." })
        assertEquals(listOf("1"), interrupt.filterIsInstance<TextRow>().map { it.markdown })
        // One footer (spec 7.6), last, and nothing else that ends the turn.
        val footer = assertIs<TurnEndRow>(rows.rows.last())
        assertEquals(TurnEnd.INTERRUPTED to "Interrupted · What should Claude do instead?", footer.end to footer.text)
        assertEquals(1, rows.rows.count { it is TurnEndRow })
        assertTrue(rows.rows.none { it is WorkingRow || it is NoticeRow }, rows.rows.toString())
    }

    @Test
    fun `the working row is last while busy and the footer replaces it`() {
        var sawWorking = false
        val (state, rows) = replay("tools-read") { _, state, rows ->
            val turn = state.turn
            if (turn != null) {
                sawWorking = true
                val working = assertIs<WorkingRow>(rows.rows.last())
                assertTrue(working.step.isNotBlank())
                assertFalse(working.waiting)
                assertEquals(turn.userUuid, working.turnUuid)
                assertEquals(turn.startedAtMillis, working.startedAtMillis)
                assertEquals(turn.outputTokens + turn.thinkingEstimate, working.outputTokens)
                assertEquals(1, rows.rows.count { it is WorkingRow })
            } else {
                assertTrue(rows.rows.none { it is WorkingRow })
            }
        }
        assertTrue(sawWorking)
        assertNull(state.turn)
        assertTrue(rows.rows.none { it is WorkingRow })
        val footer = assertIs<TurnEndRow>(rows.rows.last())
        assertEquals(TurnEnd.COMPLETED, footer.end)
        assertTrue(footer.text.startsWith("Worked for "), footer.text)
        val ended = state.entries.filterIsInstance<ChatEntry.TurnEnded>().last()
        assertEquals("e:${ended.id}", footer.key)
    }

    @Test
    fun `the working row waits while a card is pending`() {
        var waited = false
        val (_, rows) = replay("permission") { _, state, rows ->
            val working = rows.rows.lastOrNull() as? WorkingRow ?: return@replay
            assertEquals(state.pendingPermissions.isNotEmpty(), working.waiting)
            assertEquals(state.pendingPermissions.isNotEmpty(), rows.card != null)
            if (working.waiting) waited = true
        }
        assertTrue(waited)
        assertNull(rows.card)
        assertEquals(0, rows.moreWaiting)
    }

    @Test
    fun `a failed turn ends with the failure footer`() {
        val rows = replay("invalid-model").second.rows
        assertEquals(1, rows.count { it is NoticeRow && it.level == NoticeLevel.ERROR && it.text.startsWith("The CLI rejected the model") })
        val footer = assertIs<TurnEndRow>(rows[rows.size - 2])
        assertEquals(TurnEnd.FAILED to "API Error: 404 model_not_found", footer.end to footer.text)
        // The notice says more than the footer, so it follows it rather than going (hard rule 5).
        assertTrue(assertIs<NoticeRow>(rows.last()).text.startsWith("The turn failed (api_error): "))
    }

    @Test
    fun `a process exit keeps its notice after the footer`() {
        var state = reduce(ChatState(), SessionEvent.UserSent("u1", "hi", atMillis = 0))
        state = reduce(state, cliLine("""{"type":"assistant","uuid":"a1","message":{"id":"m1","content":[{"type":"text","text":"partial"}]}}"""))
        state = reduce(state, SessionEvent.Ended("Process exited with code 1.", isError = true, atMillis = 4_000))
        val rows = ChatRowsBuilder(options).build(state).rows
        val footer = assertIs<TurnEndRow>(rows[rows.size - 2])
        assertEquals(TurnEnd.FAILED to "Server error mid-response. The response above may be incomplete.", footer.end to footer.text)
        val exit = assertIs<NoticeRow>(rows.last())
        assertEquals(NoticeLevel.ERROR, exit.level)
        assertTrue(exit.text.startsWith("Process exited with code 1."), exit.text)
        assertEquals(RowGap.SECTION, exit.gap)
    }

    @Test
    fun `a failed result before any text says why`() {
        var state = reduce(ChatState(), SessionEvent.UserSent("u1", "hi", atMillis = 0))
        state = reduce(
            state,
            cliLine("""{"type":"result","subtype":"error_max_turns","is_error":true,"duration_ms":2000,"result":"boom","session_id":"s"}"""),
        )
        val rows = ChatRowsBuilder(options).build(state).rows
        assertEquals(TurnEnd.FAILED to "The turn failed (boom)", assertIs<TurnEndRow>(rows[rows.size - 2]).let { it.end to it.text })
        // The footer lacks the failure kind, so the notice that names it follows (spec 4.10, 4.11).
        val notice = assertIs<NoticeRow>(rows.last())
        assertEquals(NoticeLevel.ERROR to "The turn failed (error_max_turns): boom", notice.level to notice.text)
        assertEquals(1, rows.count { it is NoticeRow }, rows.toString())
    }

    @Test
    fun `the failed turn's notice goes only when the footer holds all it says`() {
        var state = reduce(ChatState(), SessionEvent.UserSent("u1", "hi", atMillis = 0))
        // An error result whose subtype is success and which has no terminal reason: no kind, so the notice is `The turn failed: boom`.
        state = reduce(state, cliLine("""{"type":"result","subtype":"success","is_error":true,"duration_ms":2000,"result":"boom","session_id":"s"}"""))
        assertTrue(state.entries.any { it is ChatEntry.Notice && it.text == "The turn failed: boom" }, state.entries.toString())
        val rows = ChatRowsBuilder(options).build(state).rows
        assertTrue(rows.none { it is NoticeRow }, rows.toString())
        assertEquals(TurnEnd.FAILED to "The turn failed (boom)", assertIs<TurnEndRow>(rows.last()).let { it.end to it.text })

        assertTrue(saidByFooter("The turn failed: boom", "The turn failed (boom)"))
        assertTrue(saidByFooter("The turn failed (api_error): boom", "The turn failed (api_error: boom)"))
        assertFalse(saidByFooter("The turn failed (error_max_turns): boom", "The turn failed (boom)"))
        assertFalse(saidByFooter("The turn failed: no details", "The turn failed"))
        assertFalse(saidByFooter("The turn failed: boom and more", "The turn failed (boom)"))
        assertFalse(saidByFooter("Process exited with code 1.", "The turn failed (Process exited with code 1.)"))
    }

    @Test
    fun `a failure after streamed text keeps its notice after the footer`() {
        var state = reduce(ChatState(), SessionEvent.UserSent("u1", "hi", atMillis = 0))
        state = reduce(state, cliLine("""{"type":"assistant","uuid":"a1","message":{"id":"m1","content":[{"type":"text","text":"partial"}]}}"""))
        state = reduce(
            state,
            cliLine("""{"type":"result","subtype":"error_max_turns","is_error":true,"duration_ms":2000,"result":"boom","session_id":"s"}"""),
        )
        val rows = ChatRowsBuilder(options).build(state).rows
        // The mid-response footer names no cause, so the notice that does follows it (spec 4.10, hard rule 5).
        val footer = assertIs<TurnEndRow>(rows[rows.size - 2])
        assertEquals(TurnEnd.FAILED to "Server error mid-response. The response above may be incomplete.", footer.end to footer.text)
        val notice = assertIs<NoticeRow>(rows.last())
        assertEquals(NoticeLevel.ERROR to "The turn failed (error_max_turns): boom", notice.level to notice.text)
        assertEquals(RowGap.SECTION, notice.gap)
    }

    @Test
    fun `an older failed turn keeps its notice once its footer goes`() {
        val builder = ChatRowsBuilder(options)
        var state = reduce(ChatState(), SessionEvent.UserSent("u1", "hi", atMillis = 0))
        state = reduce(
            state,
            cliLine("""{"type":"result","subtype":"error_max_turns","is_error":true,"duration_ms":2000,"result":"boom","session_id":"s"}"""),
        )
        val failed = builder.build(state).rows
        assertEquals("The turn failed (boom)", assertIs<TurnEndRow>(failed[failed.size - 2]).text)
        assertEquals("The turn failed (error_max_turns): boom", assertIs<NoticeRow>(failed.last()).text)
        // The next turn takes the footer away (spec 4.10), so the failure stays on record as its notice.
        state = reduce(state, SessionEvent.UserSent("u2", "again", atMillis = 3_000))
        val next = builder.build(state).rows
        assertTrue(next.none { it is TurnEndRow }, next.toString())
        val notice = next.filterIsInstance<NoticeRow>().single()
        assertEquals(NoticeLevel.ERROR to "The turn failed (error_max_turns): boom", notice.level to notice.text)
        assertIs<WorkingRow>(next.last())
        state = reduce(state, cliLine("""{"type":"result","subtype":"success","is_error":false,"duration_ms":4000,"result":"ok","session_id":"s"}"""))
        val done = builder.build(state).rows
        assertSame(notice, done.filterIsInstance<NoticeRow>().single())
        assertEquals("Worked for 4s", assertIs<TurnEndRow>(done.last()).text)
        assertEquals(ChatRowsBuilder(options).build(state).rows, done)
    }

    @Test
    fun `no footer while a later turn runs`() {
        val (done, _) = replay("tools-read")
        val state = reduce(done, SessionEvent.UserSent("u2", "again", atMillis = 99_000))
        val rows = ChatRowsBuilder(options).build(state).rows
        assertTrue(rows.none { it is TurnEndRow })
        assertIs<WorkingRow>(rows.last())
        assertEquals(RowGap.SECTION, rows.last().gap)
        assertIs<UserRow>(rows[rows.size - 2])
        // Once that turn ends, only its footer shows.
        val ended = reduce(state, cliLine("""{"type":"result","subtype":"success","is_error":false,"duration_ms":4000,"result":"ok","session_id":"s"}"""))
        val after = ChatRowsBuilder(options).build(ended).rows
        assertEquals(listOf("Worked for 4s"), after.filterIsInstance<TurnEndRow>().map { it.text })
        assertIs<TurnEndRow>(after.last())
    }

    @Test
    fun `a reloaded transcript has neither working row nor footer`() {
        val reloaded = runBlocking { TranscriptReader.read(Fixtures.transcript("history-reply")) }.state
        val rows = ChatRowsBuilder(options).build(reloaded).rows
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.none { it is WorkingRow || it is TurnEndRow }, rows.toString())
    }

    @Test
    fun `a reloaded interrupted transcript shows the Interrupted footer`() {
        val reloaded = runBlocking { TranscriptReader.read(Fixtures.transcript("history-interrupt")) }.state
        val rows = ChatRowsBuilder(options).build(reloaded).rows
        val footer = rows.filterIsInstance<TurnEndRow>().single()
        assertEquals(TurnEnd.INTERRUPTED to "Interrupted · What should Claude do instead?", footer.end to footer.text)
        val marker = reloaded.entries.filterIsInstance<ChatEntry.Notice>().last { it.kind == NoticeKind.INTERRUPTED }
        assertEquals("e:${marker.id}", footer.key)
        assertTrue(rows.none { it is NoticeRow && it.text == "Stopped." })
        assertTrue(rows.none { it is WorkingRow })
    }

    @Test
    fun `a reloaded transcript builds the same tool rows as its stream`() {
        val probe = Fixtures.names().filter { it.substringBefore("-") in setOf("ask", "plan", "think", "tools", "agent", "misc") }
        // The 29 probe recordings of 2026-10-18 plus `ask-two`, a scenario recording that joins the stream/reload parity sweep.
        assertEquals(30, probe.size)

        fun build(s: ChatState): ChatRows = ChatRowsBuilder(RowOptions(showThinking = true)).build(s)
        fun stream(name: String): ChatState = replay(name).first.settled()
        fun reload(name: String): ChatState = runBlocking { TranscriptReader.read(Fixtures.transcript(name)) }.state

        // Children are left out (5.5: a subagent's lines are not in the transcript); thinking counts by presence. The Agent body
        // follows its children: a forwarded TextRow child makes it None live, its absence on disk makes it Markdown.
        fun sig(rows: List<ChatRow>): List<Any> = rows.mapNotNull {
            when (it) {
                is ToolRow -> listOf(
                    it.label.text,
                    it.state,
                    it.stats,
                    it.calls.map { c -> listOf(c.label.text, c.state, c.stats, if (c.kind == ToolKind.AGENT) null else c.body::class, c.progress) },
                )
                is ThinkingRow -> "thinking"
                else -> null
            }
        }

        for (name in probe) {
            assertEquals(sig(build(stream(name)).rows), sig(build(reload(name)).rows), name)
            val reloaded = build(reload(name)).rows
            assertTrue(reloaded.none { it is WorkingRow || (it is TurnEndRow && it.end == TurnEnd.COMPLETED) }, "$name: $reloaded")
        }

        for (rows in listOf(build(stream("think-default")), build(reload("think-default")))) {
            assertEquals("Thought for 2s", rows.rows.filterIsInstance<ThinkingRow>().single().label)
        }
        for (rows in listOf(build(stream("agent-sync")), build(reload("agent-sync")))) {
            assertEquals("Done (2 tool uses · 21.8k tokens · 5.8s)", agent(rows).calls.single().progress)
        }
        assertTrue(agent(build(reload("agent-sync"))).calls.single().children.isEmpty())
        val forwarded = agent(build(reload("agent-forwarded"))).calls.single()
        assertEquals(ToolBody.Markdown("7", null), forwarded.body)
        assertTrue(forwarded.children.isEmpty())
    }

    @Test
    fun `keys are unique and stable across every state of every fixture`() {
        for (name in Fixtures.names()) {
            var before: List<ChatRow> = emptyList()
            var usersBefore = 0
            replay(name) { line, state, rows ->
                val keys = rows.rows.map { it.key }
                assertEquals(keys.distinct(), keys, "$name:$line duplicate keys")
                // One builder across every turn: only the latest turn has a footer, and the working row is the last row.
                assertTrue(rows.rows.count { it is TurnEndRow } <= 1, "$name:$line more than one footer")
                val working = rows.rows.filterIsInstance<WorkingRow>()
                assertTrue(working.size <= 1, "$name:$line more than one working row")
                working.singleOrNull()?.let { assertSame(rows.rows.last(), it, "$name:$line the working row is not last") }
                val now = keys.toSet()
                val callsNow = rows.rows.toolRows().flatMap { row -> row.calls.map { it.id } }.toSet()
                val usersNow = rows.rows.count { it is UserRow }
                for (old in before) {
                    // 5.3's one exception: a call that leaves its group moves the calls after it to a new row; they are still shown.
                    val moved = old.key.startsWith("tool:") && old.key.removePrefix("tool:") in callsNow
                    // The footer goes when the next turn starts, the working row when its turn ends.
                    val gone = when (old) {
                        is TurnEndRow -> usersNow > usersBefore
                        is WorkingRow -> state.turn?.userUuid != old.turnUuid
                        else -> false
                    }
                    assertTrue(old.key in now || moved || gone, "$name:$line lost the key ${old.key}")
                }
                before = rows.rows
                usersBefore = usersNow
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
        assertEquals(3, hidden.toolRows().size)
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
        assertEquals("working" to RowGap.SECTION, rows.last().let { it.key to it.gap })
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
                "working" to RowGap.SECTION,
            ),
            rows.map { it.key to it.gap },
        )
        // A working row right after the user's message, and a footer, are a section apart.
        val sent = reduce(ChatState(), SessionEvent.UserSent("u9", "hi"))
        assertEquals(listOf("u:u9" to RowGap.NONE, "working" to RowGap.SECTION), ChatRowsBuilder(options).build(sent).rows.map { it.key to it.gap })
        val footer = replay("plain-reply").second.rows.last()
        assertEquals(RowGap.SECTION, assertIs<TurnEndRow>(footer).gap)
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
        // The last turn has no result yet, so the working row follows its reply.
        assertEquals(32_000, last.content().size)
        assertEquals(listOf("u:u16000", "t:m16000:0", "working"), last.rows.takeLast(3).map { it.key })
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

    /** The main-thread Assistant entry of [state] whose text is [text]. */
    private fun replyOf(state: ChatState, text: String): ChatEntry.Assistant = state.entries.filterIsInstance<ChatEntry.Assistant>()
        .single { entry -> entry.parentToolUseId == null && entry.blocks.any { it is AssistantBlock.Text && it.text == text } }

    @Test
    fun `only the turn's final reply carries actions`() {
        val (state, rows) = replay("permission-mode")
        // The rows by turn: each starts at a user row.
        val turns = ArrayList<MutableList<ChatRow>>()
        for (row in rows.rows) {
            if (row is UserRow || turns.isEmpty()) turns += ArrayList<ChatRow>()
            turns.last() += row
        }
        val replies = listOf("one", "two", "three").map { replyOf(state, it) }
        assertEquals(3, turns.count { turn -> turn.any { it is TextRow } })
        val withText = turns.filter { turn -> turn.any { it is TextRow } }
        withText.forEachIndexed { t, turn ->
            val texts = turn.filterIsInstance<TextRow>()
            assertEquals(1, texts.count { it.actions != null }, texts.toString())
            val actions = assertNotNull(texts.last().actions)
            assertEquals(replies[t].id, actions.entryId)
            assertEquals(replies[t].atMillis, actions.atMillis)
        }
    }

    @Test
    fun `the reply's actions carry its Markdown, text blocks joined by a blank line, narration excluded`() {
        var firstTurn: ChatRows? = null
        val (state, _) = replay("mcp-tool-call") { line, _, rows -> if (line == 48) firstTurn = rows }
        val texts = assertNotNull(firstTurn).rows.filterIsInstance<TextRow>()
        val narration = texts.single { it.markdown.startsWith("I'll load the echo tool's schema") }
        assertNull(narration.actions)
        val reply = texts.single { it.markdown == "hello tandem" }
        val actions = assertNotNull(reply.actions)
        assertEquals("hello tandem", actions.markdown)
        assertEquals(replyOf(state, "hello tandem").id, actions.entryId)

        // A reply message with two text blocks: one action row, under the last, with both blocks.
        val ended = ChatState(entries = listOf(ChatEntry.User("u1", "hi"), assistant("m1", AssistantBlock.Text("a"), AssistantBlock.Text("b"))))
        val built = ChatRowsBuilder(options).build(ended).rows.filterIsInstance<TextRow>()
        assertEquals(listOf(null, MessageActions("m1", "a\n\nb", null)), built.map { it.actions })
    }

    @Test
    fun `a streaming reply has no actions`() {
        var running = 0
        val (_, rows) = replay("plain-reply") { _, state, rows ->
            if (state.turn != null) {
                running++
                assertTrue(rows.rows.filterIsInstance<TextRow>().all { it.actions == null }, rows.rows.toString())
            }
        }
        assertTrue(running > 0)
        assertEquals("pong", assertNotNull(rows.rows.filterIsInstance<TextRow>().single().actions).markdown)
    }

    /** The reply TextRows of [rows] (those with the hover row's actions), by their reply's text. */
    private fun replyRows(rows: ChatRows): Map<String, TextRow> =
        rows.rows.filterIsInstance<TextRow>().filter { it.actions != null }.associateBy { it.markdown }

    /** The [ChatEntry.Assistant.lastUuid] of the last main-thread message, scanning back from the entry that holds [text]. */
    private fun lastMainThreadUuid(state: ChatState, text: String): String? {
        val reply = state.entries.indexOf(replyOf(state, text))
        val start = (reply downTo 0).first { state.entries[it].startsTurn() }
        val end = (reply + 1..state.entries.lastIndex).firstOrNull { state.entries[it].startsTurn() } ?: state.entries.size
        return (end - 1 downTo start).map { state.entries[it] }
            .firstNotNullOfOrNull { (it as? ChatEntry.Assistant)?.takeIf { a -> a.parentToolUseId == null }?.lastUuid }
    }

    @Test
    fun `every ended turn's reply carries its fork point`() {
        val (state, rows) = replay("permission-mode")
        val replies = replyRows(rows)
        val points = listOf("one", "two", "three").map { assertNotNull(replies[it]).actions!!.forkAt }
        // permission-mode:11, :34 and :49, each turn's last main-thread line.
        assertEquals(listOf("<uuid-14>", "<uuid-41>", "<uuid-58>"), points)
        assertEquals(listOf("one", "two", "three").map { lastMainThreadUuid(state, it) }, points)
        assertEquals(3, points.toSet().size)
    }

    @Test
    fun `earlier replies keep forkAt and their row instances while a turn runs`() {
        val builder = ChatRowsBuilder(options)
        val (state, rows) = replay("permission-mode", builder = builder)
        val before = replyRows(rows)
        var running = reduce(state, SessionEvent.UserSent("u-4", "four"))
        running = reduce(running, SessionEvent.Cli(fixtureLine("permission-mode", 44)))
        assertNotNull(running.turn)
        val during = replyRows(builder.build(running))
        assertEquals(before.keys, during.keys)
        for ((text, row) in before) {
            assertSame(row, during[text], text)
            assertEquals(row.actions!!.forkAt, during.getValue(text).actions!!.forkAt)
        }
    }

    @Test
    fun `a subagent line is not the fork point`() {
        val (state, rows) = replay("agent-sync")
        val reply = rows.rows.filterIsInstance<TextRow>().single { it.actions != null }
        val point = reply.actions!!.forkAt
        assertNotEquals("<uuid-50>", point, "agent-sync:42 is a subagent's line")
        // agent-sync:61, the turn's last main-thread line.
        assertEquals("<uuid-74>", point)
        val text = state.entries.filterIsInstance<ChatEntry.Assistant>().single { it.lastUuid == "<uuid-74>" }
            .blocks.filterIsInstance<AssistantBlock.Text>().first().text
        assertEquals(lastMainThreadUuid(state, text), point)
    }

    @Test
    fun `a stopped turn's reply with only a draft falls back to the turn's last saved line, or null`() {
        val (state, rows) = replay("interrupt")
        assertNull(state.turn)
        val reply = rows.rows.filterIsInstance<TextRow>().single { it.actions != null }
        val entry = state.entries.filterIsInstance<ChatEntry.Assistant>().single { e -> reply.key.startsWith("t:${e.id}:") }
        // interrupt:14 is the turn's only complete main-thread line.
        assertEquals("<uuid-17>", reply.actions!!.forkAt)
        assertEquals(entry.lastUuid ?: lastSavedBefore(state, entry), reply.actions!!.forkAt)

        // With no complete main-thread line in the turn, the reply has no fork point.
        val drafted = state.copy(
            entries = state.entries.map { if (it is ChatEntry.Assistant) it.copy(lastUuid = null) else it },
        )
        val none = ChatRowsBuilder(options).build(drafted).rows.filterIsInstance<TextRow>().single { it.actions != null }
        assertNull(none.actions!!.forkAt)
        assertEquals(ForkState.UNAVAILABLE, forkState(none.actions!!.forkAt))
    }

    /** The lastUuid of the last main-thread message before [entry] in its turn. */
    private fun lastSavedBefore(state: ChatState, entry: ChatEntry.Assistant): String? {
        val at = state.entries.indexOf(entry)
        for (i in at - 1 downTo 0) {
            val e = state.entries[i]
            if (e.startsTurn()) return null
            if (e is ChatEntry.Assistant && e.parentToolUseId == null) e.lastUuid?.let { return it }
        }
        return null
    }

    @Test
    fun `forkState follows the fork point`() {
        // D8's fork-at-busy matches fork-at: a fork while a later turn runs knows only the turns up to its cut, so busy holds nothing back.
        assertEquals(ForkState.ENABLED, forkState("x"))
        assertEquals(ForkState.UNAVAILABLE, forkState(null))
    }

    @Test
    fun `mcp-slash golden`() {
        // The /mcp block (mcp-slash:1-7) under the typed /mcp: the user bubble, then the CLI's own answer as a notice with the link.
        val (state, rows) = replay("mcp-slash", prompt = "/mcp", lines = Fixtures.lines("mcp-slash").take(7))
        assertNull(state.turn)
        assertFalse(state.busy)
        assertEquals(2, rows.rows.size, rows.rows.toString())
        val user = assertIs<UserRow>(rows.rows[0])
        assertEquals("/mcp", user.text)
        assertTrue(user.mono)
        val notice = assertIs<NoticeRow>(rows.rows[1])
        assertEquals(NoticeLevel.INFO, notice.level)
        assertEquals(NoticeKind.LOCAL_COMMAND, notice.kind)
        assertEquals("mcp", notice.command)
        assertEquals(NoticeLink.OPEN_MCP, notice.link)
        assertEquals("Open the MCP panel", notice.link!!.label)
        assertEquals("3 MCP server(s): 1 connected, 2 not connected, 0 disabled.", notice.text)

        // The whole fixture under the sweep's prompt, which is not a command: each notice is named from the command the CLI ran
        // (/help's outcome names none), and the only footer is the /hello turn's, a model turn.
        val footers = ArrayList<Int>()
        val (_, all) = replay("mcp-slash") { line, _, lineRows ->
            if (lineRows.rows.any { it is TurnEndRow }) footers += line
        }
        val notices = all.rows.filterIsInstance<NoticeRow>()
        assertEquals(listOf("mcp", "model", null), notices.map { it.command })
        assertEquals(listOf(NoticeLink.OPEN_MCP, null, null), notices.map { it.link })
        assertTrue(notices.all { it.kind == NoticeKind.LOCAL_COMMAND })
        assertEquals("3 MCP server(s): 1 connected, 2 not connected, 0 disabled.", notices[0].text)
        // The model notice keeps its text verbatim, backticks included.
        assertTrue(notices[1].text.startsWith("Current model: `Fable 5.1 (default)`"), notices[1].text)
        assertEquals("/help isn't available in this environment.", notices[2].text)
        val footer = all.rows.filterIsInstance<TurnEndRow>().single()
        assertEquals(TurnEnd.COMPLETED, footer.end)
        val hello = all.rows.indexOfFirst { it is TextRow && it.markdown == "hello from the hello command" }
        assertTrue(hello >= 0 && all.rows.indexOf(footer) > hello, all.rows.toString())
        assertEquals(34, footers.first(), footers.toString())
    }

    @Test
    fun `the terminal hint is removed by exact match only`() {
        val wire = "3 MCP server(s): 1 connected, 2 not connected, 0 disabled. Use `/mcp` in the terminal for details."
        val noBackticks = "3 MCP server(s): 1 connected, 2 not connected, 0 disabled. Use /mcp in the terminal for details."
        assertEquals(" Use `/mcp` in the terminal for details.", MCP_TERMINAL_HINT)
        fun row(text: String, runCommand: String?): NoticeRow {
            val notice = ChatEntry.Notice("notice-0", NoticeLevel.INFO, text, NoticeKind.LOCAL_COMMAND, command = runCommand, runCommand = runCommand)
            return ChatRowsBuilder(options).build(ChatState(entries = listOf(notice))).rows.filterIsInstance<NoticeRow>().single()
        }
        val linked = row(wire, "mcp")
        assertEquals("3 MCP server(s): 1 connected, 2 not connected, 0 disabled.", linked.text)
        assertEquals(NoticeLink.OPEN_MCP, linked.link)
        val variant = row(noBackticks, "mcp")
        assertEquals(noBackticks, variant.text)
        assertEquals(NoticeLink.OPEN_MCP, variant.link)
        // The hint inside the text, not at its end, stays.
        assertEquals("$wire More.", row("$wire More.", "mcp").text)
        // Another command keeps the sentence and gets no link: the rule keys on the command the CLI ran, not on the text.
        val other = row(wire, "model")
        assertEquals(wire, other.text)
        assertNull(other.link)
        assertEquals("model", other.command)
        val unnamed = row(wire, null)
        assertEquals(wire, unnamed.text)
        assertNull(unnamed.link)
        assertNull(unnamed.command)
        // A generic notice keeps its kind and has neither command nor link.
        val generic = ChatEntry.Notice("notice-1", NoticeLevel.WARNING, wire)
        val plain = ChatRowsBuilder(options).build(ChatState(entries = listOf(generic))).rows.filterIsInstance<NoticeRow>().single()
        assertEquals(NoticeRow("n:notice-1", NoticeLevel.WARNING, wire, RowGap.NONE), plain)
        assertEquals(NoticeKind.GENERIC, plain.kind)
    }

    /** The one shell row [ChatRowsBuilder] makes of [entry] alone. */
    private fun shellRow(entry: ChatEntry.Shell): ShellRow =
        ChatRowsBuilder(options).build(ChatState(entries = listOf(entry))).rows.filterIsInstance<ShellRow>().single()

    @Test
    fun `a shell escape is a shell row with its output and status`() {
        val running = shellRow(ChatEntry.Shell("shell-1", "sleep 1", "", running = true, startedAtMillis = 0))
        assertEquals("sh:shell-1", running.key)
        assertEquals("sleep 1", running.command)
        assertTrue(running.running)
        assertNull(running.status)
        assertFalse(running.statusError)

        fun ended(end: ShellEnd?, durationMs: Long? = null): ShellRow =
            shellRow(ChatEntry.Shell("shell-1", "x", "out\n", running = false, end = end, durationMs = durationMs))
        val failed = ended(ShellEnd.Exited(2), 10)
        assertEquals("exit 2", failed.status)
        assertTrue(failed.statusError)
        assertFalse(failed.running)
        assertEquals(listOf("out"), failed.output)
        val ok = ended(ShellEnd.Exited(0), 10)
        assertNull(ok.status)
        assertFalse(ok.statusError)
        assertEquals("Stopped after 3.0s", ended(ShellEnd.Stopped, 3_000).status)
        assertTrue(ended(ShellEnd.Stopped, 3_000).statusError)
        assertEquals("Killed after 10 minutes", ended(ShellEnd.TimedOut, 600_000).status)
        assertEquals("Could not start /bin/zsh: boom", ended(ShellEnd.NotStarted("/bin/zsh", "boom"), 0).status)
        assertEquals("Could not read the command's output: boom", ended(ShellEnd.Failed("boom"), 5).status)
        // A reloaded escape: the transcript keeps no end, so nothing is said under it.
        val reloaded = ended(null)
        assertNull(reloaded.status)
        assertFalse(reloaded.statusError)
        assertFalse(reloaded.running)
    }

    @Test
    fun `a shell row's output is cut like a body`() {
        fun output(text: String): ShellRow = shellRow(ChatEntry.Shell("shell-1", "x", text, running = false, end = ShellEnd.Exited(0)))
        val long = output((1..401).joinToString("\n") { "line $it" })
        assertEquals(400, long.output.size)
        assertEquals(401, long.totalLines)
        assertEquals("line 1", long.output.first())
        // A command that prints nothing draws no output.
        val empty = output("")
        assertEquals(emptyList(), empty.output)
        assertEquals(0, empty.totalLines)
        // Colour escapes are stripped from the row (the frames keep them).
        assertEquals(listOf("red"), output("\u001b[31mred\u001b[0m").output)
    }

    @Test
    fun `bash-mode-respond golden`() {
        // The shell events as SessionManager and ClaudeSession dispatch them, then the CLI's lines :3-42, folded with reduce directly:
        // replay() would send a UserSent before each init, adding User rows.
        val builder = ChatRowsBuilder(options)
        var state = ChatState()
        state = reduce(state, SessionEvent.ShellStarted("shell-1", "echo bash-mode-probe && printf err >&2", 0))
        state = reduce(state, SessionEvent.ShellOutput("shell-1", "bash-mode-probe\nerr"))
        assertTrue(builder.build(state).rows.single().let { it is ShellRow && it.running })
        state = reduce(state, SessionEvent.ShellEnded("shell-1", ShellEnd.Exited(0), 12))
        val ended = builder.build(state).rows
        assertTrue(ended.none { it is WorkingRow }, ended.toString())
        assertEquals(listOf("bash-mode-probe", "err"), assertIs<ShellRow>(ended.single()).output)
        state = reduce(state, SessionEvent.ShellSent("shell-1", listOf("<command_uuid-4>"), "<command_uuid-10>", 20))
        assertIs<WorkingRow>(builder.build(state).rows.last())
        var rows = builder.build(state)
        Fixtures.lines("bash-mode-respond").subList(2, 42).forEachIndexed { i, line ->
            val n = i + 3
            state = reduce(state, SessionEvent.Cli(Ndjson.decode(line), atMillis = n * 100L))
            rows = builder.build(state)
            if (n < 41) {
                assertIs<WorkingRow>(rows.rows.last(), "line $n: ${rows.rows}")
                assertTrue(rows.rows.none { it is TurnEndRow }, "line $n: ${rows.rows}")
            }
            // The quiet result (:6) ends no turn: the turn the output frame asked for stays open.
            if (n == 6) assertTrue(state.busy && state.turn != null, "line 6")
        }
        assertEquals(
            listOf(ShellRow::class, ThinkingRow::class, TextRow::class, TurnEndRow::class),
            rows.rows.map { it::class },
            rows.rows.toString(),
        )
        val shell = assertIs<ShellRow>(rows.rows[0])
        assertEquals("echo bash-mode-probe && printf err >&2", shell.command)
        assertNull(shell.status)
        assertTrue(assertIs<TextRow>(rows.rows[2]).markdown.startsWith("The probe ran"))
        assertEquals(TurnEnd.COMPLETED, assertIs<TurnEndRow>(rows.rows[3]).end)
        // The reply is the ended turn's, under its hover row, with the turn's fork point.
        val actions = assertNotNull(assertIs<TextRow>(rows.rows[2]).actions)
        assertEquals(state.entries.filterIsInstance<ChatEntry.Assistant>().last { it.parentToolUseId == null }.lastUuid, actions.forkAt)
        assertNotNull(actions.forkAt)
    }

    @Test
    fun `a responding shell turn shows its running tool call as running`() {
        val read = buildJsonObject { put("file_path", "/p/a.kt") }
        var state = ChatState()
        state = reduce(state, SessionEvent.ShellStarted("shell-1", "false", 0))
        state = reduce(state, SessionEvent.ShellEnded("shell-1", ShellEnd.Exited(1), 5))
        state = reduce(state, SessionEvent.ShellSent("shell-1", listOf("a"), "q", 10))
        state = state.withEntry(assistant("a1", AssistantBlock.ToolUse("c1", "Read", read, "")))
        val rows = ChatRowsBuilder(options).build(state).rows
        assertEquals(ToolState.RUNNING, rows.toolRows().single().state, rows.toString())
        assertIs<WorkingRow>(rows.last())
        assertEquals(listOf(ShellRow::class, ToolRow::class, WorkingRow::class), rows.map { it::class })
    }

    @Test
    fun `a shell row starts a turn`() {
        // As with a User entry in the Shell entry's place: each of the three turns' replies carries its hover row.
        fun entries(middle: ChatEntry): List<ChatEntry> = listOf(
            ChatEntry.User("u1", "a"),
            assistant("a1", AssistantBlock.Text("reply a")),
            middle,
            assistant("a2", AssistantBlock.Text("reply shell")),
            ChatEntry.User("u2", "b"),
            assistant("a3", AssistantBlock.Text("reply b")),
        )
        val shell = ChatRowsBuilder(options).build(ChatState(entries = entries(ChatEntry.Shell("s1", "ls", "out", running = false))))
        val user = ChatRowsBuilder(options).build(ChatState(entries = entries(ChatEntry.User("s1", "!ls"))))
        fun withActions(rows: ChatRows): List<String> = rows.rows.filterIsInstance<TextRow>().filter { it.actions != null }.map { it.key }
        assertEquals(listOf("t:a1:0", "t:a2:0", "t:a3:0"), withActions(user))
        assertEquals(withActions(user), withActions(shell))
        assertEquals(
            listOf(
                "u:u1" to RowGap.NONE,
                "t:a1:0" to RowGap.AFTER_BUBBLE,
                "sh:s1" to RowGap.TURN,
                "t:a2:0" to RowGap.SECTION,
                "u:u2" to RowGap.TURN,
                "t:a3:0" to RowGap.AFTER_BUBBLE,
            ),
            shell.rows.map { it.key to it.gap },
        )
        // While the shell's turn runs, the ended turn before it keeps its hover row and the running one has none.
        val running = ChatState(entries = entries(ChatEntry.Shell("s1", "ls", "out", running = false)).take(4))
            .let { reduce(it, SessionEvent.ShellSent("s1", listOf("x"), "q", 0)) }
        assertEquals(listOf("t:a1:0"), withActions(ChatRowsBuilder(options).build(running)))
        // An ended turn's footer belongs to the latest turn: a shell escape after it, as a prompt after it, starts the next one.
        fun footers(next: ChatEntry): List<ChatRow> = ChatRowsBuilder(options).build(
            ChatState(
                entries = listOf(
                    ChatEntry.User("u1", "a"),
                    assistant("a1", AssistantBlock.Text("reply a")),
                    ChatEntry.TurnEnded("end-1", TurnEnd.COMPLETED, 1_000, 5, emptyList()),
                    next,
                ),
            ),
        ).rows.filter { it is TurnEndRow }
        assertEquals(footers(ChatEntry.User("s1", "!ls")), footers(ChatEntry.Shell("s1", "ls", "", running = false)))
        assertTrue(footers(ChatEntry.Shell("s1", "ls", "", running = false)).isEmpty())
    }

    private fun cliLine(json: String): SessionEvent = SessionEvent.Cli(Ndjson.decode(json))

    private fun assistant(id: String, vararg blocks: AssistantBlock): ChatEntry.Assistant =
        ChatEntry.Assistant(id, id, parentToolUseId = null, model = null, blocks = blocks.toList(), completedBlocks = blocks.size, streaming = false, uuids = emptySet())

    private val u17: UsageAnswer = ControlPayloads.usage((fixtureLine("parity-usage", 17) as CliMessage.ControlResponse).payload)!!

    private fun noticeRows(rows: ChatRows): List<NoticeRow> = rows.rows.filterIsInstance<NoticeRow>()

    private fun usageRow(rows: ChatRows): NoticeRow = rows.rows.filterIsInstance<NoticeRow>().single { it.sourceUuid == "<uuid-28>" }

    @Test
    fun `a usage notice row carries its card`() {
        val (_, rows) = replay("parity-usage", prompt = "hi", lines = Fixtures.lines("parity-usage").take(22))
        val row = usageRow(rows)
        assertNotNull(row.usage)
        assertFalse(row.markdown)
        assertEquals(UsageSection.Loading, row.usage!!.facts)
    }

    @Test
    fun `a context notice is markdown`() {
        val (_, rows) = replay("parity-context", prompt = "hi", lines = Fixtures.lines("parity-context").take(24))
        val row = noticeRows(rows).single { it.kind == NoticeKind.LOCAL_COMMAND }
        assertTrue(row.markdown)
        assertNull(row.usage)
        assertNull(row.sourceUuid)
    }

    @Test
    fun `other local notices stay plain`() {
        val (_, rows) = replay("mcp-slash", prompt = "hi", lines = Fixtures.lines("mcp-slash").take(5))
        val row = noticeRows(rows).single { it.kind == NoticeKind.LOCAL_COMMAND }
        assertFalse(row.markdown)
        assertNull(row.usage)
    }

    @Test
    fun `a usage card in an older turn updates when its answer arrives`() {
        // Review Focus 1: three turns, the first two cached.
        val builder = ChatRowsBuilder(options)
        val (state, rows) = replay("parity-usage", prompt = "hi", builder = builder)
        assertEquals(UsageSection.Loading, usageRow(rows).usage!!.history)
        val answered = reduce(state, SessionEvent.UsageFetched("<uuid-28>", UsagePart.HISTORY, McpResult.Ok(u17)))
        assertIs<UsageSection.Shown<*>>(usageRow(builder.build(answered)).usage!!.history)
    }

    @Test
    fun `Retry on an older card shows it loading again`() {
        // Review Focus 1
        val builder = ChatRowsBuilder(options)
        val (state, _) = replay("parity-usage", prompt = "hi", builder = builder)
        val answered = reduce(state, SessionEvent.UsageFetched("<uuid-28>", UsagePart.HISTORY, McpResult.Ok(u17)))
        assertIs<UsageSection.Shown<*>>(usageRow(builder.build(answered)).usage!!.history)
        val retried = reduce(answered, SessionEvent.UsageRetry("<uuid-28>", UsagePart.HISTORY))
        assertEquals(UsageSection.Loading, usageRow(builder.build(retried)).usage!!.history)
    }

    @Test
    fun `a usage card in an earlier frozen turn updates and later turns keep their rows`() {
        // Review Focus 1, deviation 7: the card's turn is neither the last frozen turn nor ended by the card.
        val builder = ChatRowsBuilder(options)
        var (state, _) = replay("parity-usage", prompt = "hi", builder = builder)
        state = replay("plain-reply", prompt = "hi", initial = state, builder = builder).first
        val suffixed = Fixtures.lines("plain-reply").map { it.replace(Regex("\"(<(?:uuid|id)-\\d+>)\""), "\"$1-2\"") }
        state = replay("plain-reply", prompt = "hi", initial = state, lines = suffixed, builder = builder).first
        val rows = builder.build(state)
        val at = rows.rows.indexOf(usageRow(rows))
        assertTrue(rows.rows.size > at + 4, rows.rows.toString())
        val after = builder.build(reduce(state, SessionEvent.UsageFetched("<uuid-28>", UsagePart.HISTORY, McpResult.Ok(u17))))
        assertIs<UsageSection.Shown<*>>(usageRow(after).usage!!.history)
        assertEquals(rows.rows.size, after.rows.size)
        for (i in at + 1 until rows.rows.size) assertSame(rows.rows[i], after.rows[i], "row $i")
        for (i in 0 until at) assertSame(rows.rows[i], after.rows[i], "row $i")
    }

    // Spec 12.1's pending bubbles and folded prompts (D11), on replays that inject only the host's sends at their lines.

    private fun pending(uuid: String, text: String, later: Boolean, stage: PendingStage, images: List<UserImage> = emptyList()) =
        PendingSend(uuid, text, images, 0L, later, stage)

    @Test
    fun `waiting messages draw QueuedRows after the working row, in order`() {
        val image = UserImage("s2#0", "image/png", "iVBORw0KGgo=")
        val running = reduce(ChatState(), SessionEvent.UserSent("p", "Run it", atMillis = 0L))
        val state = running.copy(
            pendingSends = listOf(
                pending("s1", "steer", later = false, stage = PendingStage.QUEUED),
                pending("s2", "later", later = true, stage = PendingStage.QUEUED, images = listOf(image)),
                pending("s3", "held", later = true, stage = PendingStage.HELD),
                pending("s4", "withdrawing", later = true, stage = PendingStage.WITHDRAWING),
            ),
        )
        val rows = ChatRowsBuilder(options).build(state).rows
        val working = rows.indexOfFirst { it is WorkingRow }
        assertTrue(working >= 0, rows.toString())
        val queued = rows.drop(working + 1).map { assertIs<QueuedRow>(it) }
        assertEquals(listOf("q:s1", "q:s2", "q:s3", "q:s4"), queued.map { it.key })
        assertEquals(listOf("steer", "later", "held", "withdrawing"), queued.map { it.text })
        assertEquals(
            listOf(
                "Sent · Claude reads it at its next step",
                "Queued · sends when Claude is done",
                "Waits for the shell command's output to go out",
                "Withdrawing…",
            ),
            queued.map { it.caption },
        )
        // D8's queue-cancel-steer outcome is withdrawable: a steer gets Edit and Cancel too.
        assertEquals(listOf(true, true, true, true), queued.map { it.withdrawable })
        assertEquals(listOf(false, false, false, true), queued.map { it.withdrawing })
        assertEquals(persistentListOf(ImageRef("s2#0", "image/png", 0)), queued[1].images)
        assertTrue(queued[0].images.isEmpty())
        assertTrue(queued.all { it.gap == RowGap.AFTER_BUBBLE })
        // A message waiting after the turn's result (queue-later:51 to :53) still draws, with no working row.
        val gap = ChatRowsBuilder(options).build(state.copy(turn = null, busy = false)).rows
        assertEquals(listOf("q:s1", "q:s2", "q:s3", "q:s4"), gap.filterIsInstance<QueuedRow>().map { it.key })
        assertIs<QueuedRow>(gap.last())
    }

    @Test
    fun `a folded prompt has its caption and the turn one footer`() {
        val footers = HashSet<String>()
        val earlyActions = ArrayList<String>()
        val (state, rows) = replayWithSends(
            "queue-fold",
            mapOf(
                1 to listOf(SessionEvent.UserSent(QP, "Run the two commands")),
                19 to listOf(SessionEvent.UserSent(QF, KIWI, live = true)),
            ),
        ) { line, _, lineRows ->
            lineRows.rows.filterIsInstance<TurnEndRow>().forEach { footers += it.key }
            // From F's started (queue-fold:22) to the result (:53) the turn runs: a split turn would close P's part and give its reply
            // the hover row.
            if (line in 22 until 53) lineRows.rows.filterIsInstance<TextRow>().filter { it.actions != null }.forEach { earlyActions += "$line:${it.key}" }
        }
        // One CLI turn is one visual turn (Review Focus 7): the folded prompt does not start a turn.
        assertFalse(state.entries.single { it.id == QF }.startsTurn())
        assertTrue(earlyActions.isEmpty(), earlyActions.toString())
        val texts = rows.rows.filterIsInstance<TextRow>()
        assertEquals(listOf(texts.last().key), texts.filter { it.actions != null }.map { it.key }, "only the final reply has the hover row")
        val users = rows.rows.filterIsInstance<UserRow>()
        assertEquals(2, users.size, rows.rows.toString())
        val (prompt, folded) = users
        assertNull(prompt.caption)
        assertEquals(KIWI, folded.text)
        assertEquals("Sent while Claude worked", folded.caption)
        assertEquals("u:$QF", folded.key)
        // Right after the first Bash call's row (queue-fold:14's call, its result at :21).
        val at = rows.rows.indexOf(folded)
        val bash = toolUseAt("queue-fold", 14).id
        assertEquals(listOf(bash), assertIs<ToolRow>(rows.rows[at - 1]).calls.map { it.id })
        // Only the latest turn draws a footer, so these alone do not show the turn is unsplit: the assertions above do.
        assertEquals(1, footers.size, footers.toString())
        assertEquals(1, rows.rows.count { it is TurnEndRow })
        assertTrue(rows.rows.none { it is QueuedRow })
    }

    @Test
    fun `queue-later has two footers`() {
        val footers = LinkedHashSet<String>()
        val (state, rows) = replayWithSends(
            "queue-later",
            mapOf(
                1 to listOf(SessionEvent.UserSent(QP, "Run the two commands")),
                19 to listOf(SessionEvent.UserSent(QF, KIWI, later = true, live = true)),
            ),
        ) { _, _, lineRows -> lineRows.rows.filterIsInstance<TurnEndRow>().forEach { footers += it.key } }
        assertEquals(2, footers.size, footers.toString())
        assertEquals(2, state.turnsEnded)
        val user = rows.rows.filterIsInstance<UserRow>().single { it.key == "u:$QF" }
        assertNull(user.caption)
        assertEquals(KIWI, user.text)
        assertTrue(state.entries.none { it is ChatEntry.User && it.text == PROMPT })
    }

    @Test
    fun `the key moves from the queued row to the user row once started`() {
        val seen = HashMap<Int, ChatRows>()
        replayWithSends(
            "queue-later",
            mapOf(
                1 to listOf(SessionEvent.UserSent(QP, "Run the two commands")),
                19 to listOf(SessionEvent.UserSent(QF, KIWI, later = true, live = true)),
            ),
        ) { line, _, lineRows -> seen[line] = lineRows }
        val waiting = seen.getValue(19).rows
        val queued = waiting.filterIsInstance<QueuedRow>().single()
        assertEquals("q:$QF", queued.key)
        assertEquals("Queued · sends when Claude is done", queued.caption)
        assertTrue(waiting.none { it.key == "u:$QF" })
        // Through the gap between the result (:51) and its started (:53) the bubble stays.
        assertEquals(listOf("q:$QF"), seen.getValue(52).rows.filterIsInstance<QueuedRow>().map { it.key })
        val started = seen.getValue(53).rows
        assertTrue(started.none { it is QueuedRow }, started.toString())
        assertEquals(KIWI, started.filterIsInstance<UserRow>().single { it.key == "u:$QF" }.text)
    }

    @Test
    fun `a later context runs as its own local turn after the running one`() {
        // spec: a later /context runs as its own local turn after the running one
        // D8's after-the-turn outcome (probes 5.1): queue-later-command's C (/context) and I (an image message), both later.
        val p = "<command_uuid-4>"
        val c = "<command_uuid-29>"
        val i = "<command_uuid-31>"
        val image = UserImage("$i#0", "image/png", "iVBORw0KGgo=")
        val seen = HashMap<Int, Pair<ChatState, ChatRows>>()
        val (state, rows) = replayWithSends(
            "queue-later-command",
            mapOf(
                1 to listOf(SessionEvent.UserSent(p, "Run the command")),
                23 to listOf(SessionEvent.UserSent(c, "/context", later = true, live = true)),
                24 to listOf(
                    SessionEvent.UserSent(i, "What colour is this image? Reply with one word.", images = listOf(image), later = true, live = true),
                ),
            ),
        ) { line, lineState, lineRows -> seen[line] = lineState to lineRows }
        // The running turn ends as a model turn with its footer (queue-later-command:35), its two messages still waiting.
        val (afterResult, resultRows) = seen.getValue(35)
        assertEquals(1, afterResult.turnsEnded)
        val footer = resultRows.rows.filterIsInstance<TurnEndRow>().single()
        assertEquals(TurnEnd.COMPLETED, footer.end)
        assertEquals(listOf("q:$c", "q:$i"), resultRows.rows.filterIsInstance<QueuedRow>().map { it.key })
        assertTrue(resultRows.rows.none { it is NoticeRow && it.kind == NoticeKind.LOCAL_COMMAND })
        // Then the command runs as its own turn: its bubble, then its local notice.
        val all = rows.rows
        val command = all.indexOfFirst { it is UserRow && it.key == "u:$c" }
        val done = all.indexOfFirst { it is TextRow && it.markdown == "DONE" }
        assertTrue(done in 0 until command, all.toString())
        assertNull((all[command] as UserRow).caption)
        val notice = assertIs<NoticeRow>(all[command + 1])
        assertEquals(NoticeKind.LOCAL_COMMAND, notice.kind)
        assertEquals("context", notice.command)
        // The image message after it, as its own turn, which the last footer closes.
        val imageRow = all.indexOfFirst { it is UserRow && it.key == "u:$i" }
        assertTrue(imageRow > command + 1, all.toString())
        assertEquals(1, (all[imageRow] as UserRow).images.size)
        assertIs<TurnEndRow>(all.last())
        assertTrue(all.none { it is QueuedRow })
        assertEquals(listOf(p, c, i), state.entries.filterIsInstance<ChatEntry.User>().map { it.id })
        assertTrue(state.entries.filterIsInstance<ChatEntry.User>().none { it.folded })
    }

    /** The `ide-edit` first call's id (`ide-edit:35`). */
    private val editId: String = toolUseAt("ide-edit", 35).id

    /** The rows of `ide-edit` replayed up to line [upTo], then [events] reduced on the state, built by the same builder. */
    private fun editRows(upTo: Int, vararg events: SessionEvent, name: String = "ide-edit"): Pair<ChatState, ChatRows> {
        val builder = ChatRowsBuilder(options)
        var (state, _) = replay(name, lines = Fixtures.lines(name).take(upTo), builder = builder)
        for (event in events) state = reduce(state, event)
        return state to builder.build(state)
    }

    private fun ChatRows.edit(id: String): ToolCall = rows.toolRows().flatMap { it.calls }.single { it.id == id }

    private fun phase(id: String, phase: EditPhase, outcome: EditOutcome? = null): SessionEvent =
        SessionEvent.EditPhase(id, EditReview("hello.txt", phase, outcome))

    // spec: a queued edit says it waits
    @Test
    fun `a queued edit says it waits`() {
        // ide-edit:38 is the tools/call; another diff is open, so the call queues.
        val (_, rows) = editRows(38, phase(editId, EditPhase.QUEUED))
        val call = rows.edit(editId)
        assertEquals("Edit of hello.txt waits for the open diff", call.label.text)
        assertEquals(persistentListOf(8..16), call.label.spans)
        assertEquals(ToolState.RUNNING, call.state)
        assertEquals(EditPhase.QUEUED, call.review?.phase)
        assertNull(call.action)
        assertNull(call.tag)
        assertEquals(call.label, rows.rows.toolRows().single { it.calls.single().id == editId }.label)
    }

    // spec: an open edit reviews in the diff with Show diff
    @Test
    fun `an open edit reviews in the diff with Show diff`() {
        val (state, rows) = editRows(38, phase(editId, EditPhase.QUEUED), phase(editId, EditPhase.OPEN))
        val call = rows.edit(editId)
        assertEquals("Reviewing hello.txt in the diff", call.label.text)
        assertEquals(RowAction.ShowDiff(editId), call.action)
        assertEquals(ToolState.RUNNING, call.state)
        // Only the open phase offers it: once decided, the action goes.
        val done = ChatRowsBuilder(options).build(reduce(state, phase(editId, EditPhase.DONE, EditOutcome.ACCEPTED))).edit(editId)
        assertNull(done.action)
        assertEquals("Edited hello.txt", done.label.text)
        // A call without a review (the built-in tools, a reloaded session) has neither.
        val plain = replay("tools-edit").second.rows.toolRows().flatMap { it.calls }
        assertTrue(plain.all { it.action == null && it.review == null && it.tag == null })
    }

    // spec: an accepted edit with changes carries its tag
    @Test
    fun `an accepted edit with changes carries its tag`() {
        // Decided, before its result (ide-edit:41) lands.
        val (_, before) = editRows(38, phase(editId, EditPhase.OPEN), phase(editId, EditPhase.DONE, EditOutcome.ACCEPTED_CHANGED))
        assertEquals("Edited hello.txt", before.edit(editId).label.text)
        assertEquals("with your changes", before.edit(editId).tag)
        // With its result: DONE, the tag stays.
        val (_, after) = editRows(41, phase(editId, EditPhase.DONE, EditOutcome.ACCEPTED_CHANGED))
        val call = after.edit(editId)
        assertEquals(ToolState.DONE, call.state)
        assertEquals("Edited hello.txt", call.label.text)
        assertEquals("with your changes", call.tag)
        assertEquals(DiffStats(1, 1), call.stats)
        // A plain Accept has no tag.
        assertNull(editRows(41, phase(editId, EditPhase.DONE, EditOutcome.ACCEPTED)).second.edit(editId).tag)
        // A review that changes after its turn was cached reopens that turn (the whole fixture: two turns).
        val builder = ChatRowsBuilder(options)
        val (state, _) = replay("ide-edit", builder = builder)
        val reopened = builder.build(reduce(state, phase(editId, EditPhase.DONE, EditOutcome.ACCEPTED_CHANGED)))
        assertEquals("with your changes", reopened.edit(editId).tag)
    }

    // spec: an auto-accepted edit carries its tag
    @Test
    fun `an auto-accepted edit carries its tag`() {
        val (_, rows) = editRows(41, phase(editId, EditPhase.DONE, EditOutcome.AUTO))
        val call = rows.edit(editId)
        assertEquals("Edited hello.txt", call.label.text)
        assertEquals("auto-accepted", call.tag)
        assertNull(call.action)
    }

    // spec: a stopped review says Claude stopped waiting
    @Test
    fun `a stopped review says Claude stopped waiting`() {
        // ide-edit-stop:42 is the CLI's notifications/cancelled for the held call (ide-edit-stop:36); the turn is still open.
        val id = toolUseAt("ide-edit-stop", 36).id
        val (_, open) = editRows(42, phase(id, EditPhase.OPEN), phase(id, EditPhase.DONE, EditOutcome.STOPPED), name = "ide-edit-stop")
        val call = open.edit(id)
        assertEquals("Claude stopped waiting for this edit.", call.label.text)
        assertNull(call.action)
        // Once the turn ends (ide-edit-stop:46), the CLI's user-rejected result makes it an interrupted row.
        val (_, ended) = editRows(46, phase(id, EditPhase.DONE, EditOutcome.STOPPED), name = "ide-edit-stop")
        val interrupted = ended.edit(id)
        assertEquals(ToolState.INTERRUPTED, interrupted.state)
        assertEquals("Edit hello.txt · Interrupted", interrupted.label.text)
        // A rejected review keeps the denied form, before and after its result (ide-edit:105).
        val writeId = toolUseAt("ide-edit", 101).id
        val rejected = SessionEvent.EditPhase(writeId, EditReview("new.txt", EditPhase.DONE, EditOutcome.REJECTED))
        assertEquals("Rejected creating new.txt", editRows(104, rejected).second.edit(writeId).label.text)
        val denied = editRows(105, rejected).second.edit(writeId)
        assertEquals(ToolState.DENIED, denied.state)
        assertEquals("Rejected creating new.txt", denied.label.text)
    }
}

private const val QP: String = "<command_uuid-4>"
private const val QF: String = "<command_uuid-25>"
private const val KIWI: String = "Also say the word KIWI at the end."
