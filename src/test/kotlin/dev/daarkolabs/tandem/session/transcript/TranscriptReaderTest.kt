package dev.daarkolabs.tandem.session.transcript

import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.PendingPermission
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.reduce
import dev.daarkolabs.tandem.session.settled
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.bufferedWriter
import kotlin.io.path.fileSize
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptReaderTest {
    private fun ChatState.userTexts(): List<String> = entries.filterIsInstance<ChatEntry.User>().map { it.text }

    private fun ChatState.assistantTexts(): List<String> =
        entries.filterIsInstance<ChatEntry.Assistant>().flatMap { it.blocks }.filterIsInstance<AssistantBlock.Text>().map { it.text }

    private fun Path.writeLines(vararg lines: String) = writeText(lines.joinToString("\n", postfix = "\n"))

    private fun emptyMeta(id: String, cwd: String? = null, lineCount: Int = 0) =
        TranscriptMeta(id, null, null, null, null, null, null, cwd, lineCount)

    @Test
    fun `loads the same conversation as the stream fixture`() = runBlocking<Unit> {
        val streamed = Fixtures.decode("history-permission").fold(ChatState()) { s, m -> reduce(s, SessionEvent.Cli(m)) }
        val loaded = TranscriptReader.read(Fixtures.transcript("history-permission"))
        assertEquals(
            streamed.entries.filterIsInstance<ChatEntry.Assistant>().map { it.blocks },
            loaded.state.entries.filterIsInstance<ChatEntry.Assistant>().map { it.blocks },
        )
        assertEquals(
            streamed.entries.filterIsInstance<ChatEntry.ToolResult>().map { it.text to it.isError },
            loaded.state.entries.filterIsInstance<ChatEntry.ToolResult>().map { it.text to it.isError },
        )
        assertEquals(2, loaded.state.entries.count { it is ChatEntry.User }) // the prompts the driver typed
        assertFalse(loaded.state.busy)
        assertEquals("claude-sonnet-5-5", loaded.meta.lastModel)
        // history-reply was recorded with --effort high, which its assistant lines carry as "effort":"high".
        assertEquals("high", TranscriptReader.meta(Fixtures.transcript("history-reply")).lastEffort)
    }

    @Test
    fun `title precedence`(@TempDir dir: Path) {
        val f = dir.resolve("11111111-1111-4111-8111-111111111111.jsonl")
        f.writeText("""{"type":"user","uuid":"u1","permissionMode":"plan","message":{"role":"user","content":"first   prompt\nsecond line"}}""" + "\n")
        assertEquals("first   prompt", TranscriptReader.meta(f).title)
        assertEquals(PermissionMode.PLAN, TranscriptReader.meta(f).lastPermissionMode)
        f.appendText("""{"type":"ai-title","aiTitle":"Generated"}""" + "\n")
        assertEquals("Generated", TranscriptReader.meta(f).title)
        f.appendText("""{"type":"summary","summary":"Later summary"}""" + "\n")
        assertEquals("Later summary", TranscriptReader.meta(f).title)
        f.appendText("""{"type":"custom-title","customTitle":"Mine"}""" + "\n")
        assertEquals("Mine", TranscriptReader.meta(f).title)
        f.appendText("""{"type":"ai-title","aiTitle":"Newer generated"}""" + "\n")
        assertEquals("Mine", TranscriptReader.meta(f).title)
    }

    @Test
    fun `a blank title line does not count`(@TempDir dir: Path) {
        val f = dir.resolve("11111111-1111-4111-8111-111111111112.jsonl")
        f.writeLines(
            """{"type":"custom-title","customTitle":"Mine"}""",
            """{"type":"custom-title","customTitle":"  "}""",
            """{"type":"ai-title","aiTitle":""}""",
        )
        val meta = TranscriptReader.meta(f)
        assertEquals("Mine", meta.title)
        assertNull(meta.generatedTitle)
    }

    @Test
    fun `empty transcript falls back to the id and an 81-char prompt is trimmed to 80`(@TempDir dir: Path) = runBlocking<Unit> {
        val id = "22222222-2222-4222-8222-222222222222"
        val f = dir.resolve("$id.jsonl")
        f.writeText("")
        val empty = TranscriptReader.read(f)
        assertEquals(emptyMeta(id), empty.meta)
        assertEquals(id, empty.meta.title)
        assertEquals(ChatState(), empty.state)
        assertEquals(empty.meta, TranscriptReader.meta(f))

        // Leading blank lines and surrounding spaces go, and only the first line is kept, cut to 80 characters.
        val prompt = "a".repeat(81)
        f.writeLines("""{"type":"user","uuid":"u1","message":{"role":"user","content":"\n  $prompt  \nsecond line"}}""")
        assertEquals("a".repeat(80), TranscriptReader.meta(f).title)
        assertEquals("a".repeat(80), TranscriptReader.meta(f).firstPrompt)
        // The history keeps the prompt whole.
        assertEquals(listOf("\n  $prompt  \nsecond line"), TranscriptReader.read(f).state.userTexts())

        // A character outside the basic plane (two UTF-16 units) is never cut in half.
        f.writeLines("""{"type":"user","uuid":"u1","message":{"role":"user","content":"${"a".repeat(79)}\uD83D\uDE00"}}""")
        assertEquals("a".repeat(79), TranscriptReader.meta(f).title)
    }

    @Test
    fun `meta and sidechain lines are skipped`(@TempDir dir: Path) = runBlocking<Unit> {
        val id = "33333333-3333-4333-8333-333333333333"
        val f = dir.resolve("$id.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"m1","isMeta":true,"permissionMode":"plan","cwd":"/work","message":{"role":"user","content":"Injected by the CLI"}}""",
            """{"type":"user","uuid":"s1","isSidechain":true,"permissionMode":"plan","message":{"role":"user","content":[{"type":"text","text":"subagent task"},{"type":"tool_result","tool_use_id":"toolu_s","content":"subagent result"}]}}""",
            """{"type":"assistant","uuid":"s2","isSidechain":true,"effort":"low","message":{"id":"msg_s","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"subagent reply"}]}}""",
        )
        val loaded = TranscriptReader.read(f)
        assertEquals(emptyList(), loaded.state.entries)
        // The cwd still comes from the first line that has one, skipped or not.
        assertEquals(emptyMeta(id, cwd = "/work", lineCount = 3), loaded.meta)
        assertEquals(id, loaded.meta.title)
        assertNull(loaded.state.permissionMode)
        assertNull(loaded.state.effort)
        assertEquals(loaded.meta, TranscriptReader.meta(f))
    }

    @Test
    fun `unknown types, non-object and malformed lines are skipped`(@TempDir dir: Path) = runBlocking<Unit> {
        val id = "44444444-4444-4444-8444-444444444444"
        val f = dir.resolve("$id.jsonl")
        f.writeText(
            listOf(
                "not json",
                "[1]",
                """{"type":"attachment"}""",
                "",
                """{"type":"future-type","message":{"role":"user","content":"not a prompt"}}""",
                // Cut off, as when the CLI is still writing the last line.
                """{"type":"assistant","uuid":"a1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"po""",
            ).joinToString("\n"),
        )
        val loaded = TranscriptReader.read(f)
        assertEquals(emptyList(), loaded.state.entries)
        assertEquals(emptyMeta(id, lineCount = 6), loaded.meta)
        assertEquals(loaded.meta, TranscriptReader.meta(f))
    }

    @Test
    fun `a stray invalid byte does not cut the history short`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("44444444-4444-4444-8444-444444444445.jsonl")
        // 0xC3 starts a two-byte UTF-8 sequence, but a quote follows it.
        val broken = """{"type":"user","uuid":"u1","message":{"role":"user","content":"caf""".toByteArray() +
            0xC3.toByte() + "\"}}\n".toByteArray()
        val next = """{"type":"user","uuid":"u2","message":{"role":"user","content":"after"}}""".toByteArray() + "\n".toByteArray()
        f.writeBytes(broken + next)
        assertEquals(listOf("caf\uFFFD", "after"), TranscriptReader.read(f).state.userTexts())
    }

    @Test
    fun `a transcript that ends mid-turn loads settled`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("55555555-5555-4555-8555-555555555555.jsonl")
        // The process stopped right after asking for a tool: a tool_use with no result after it.
        f.writeLines(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"List the files"}}""",
            """{"type":"assistant","uuid":"a1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"Listing them."},{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls"}}]}}""",
        )
        val state = TranscriptReader.read(f).state
        assertFalse(state.busy)
        assertFalse(state.ended)
        assertTrue(state.pendingPermissions.isEmpty())
        assertTrue(state.entries.filterIsInstance<ChatEntry.Assistant>().none { it.streaming })
        assertIs<AssistantBlock.ToolUse>(state.entries.filterIsInstance<ChatEntry.Assistant>().last().blocks.last())
    }

    @Test
    fun `a prompt that shares a line with a tool result comes after the result`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("66666666-6666-4666-8666-666666666666.jsonl")
        // The API puts the tool_result blocks of a user message first and any text after them.
        f.writeLines(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"file.txt","is_error":false},{"type":"text","text":"also check"},{"type":"text","text":"the docs"}]}}""",
        )
        assertEquals(
            listOf(ChatEntry.ToolResult("result-toolu_1", "toolu_1", "file.txt", false), ChatEntry.User("u1", "also check\nthe docs")),
            TranscriptReader.read(f).state.entries,
        )
    }

    @Test
    fun `a loaded history followed by a live turn keeps ids unique and toggles busy`() = runBlocking<Unit> {
        var state = TranscriptReader.read(Fixtures.transcript("history-reply")).state
        assertEquals(listOf("Reply with the single word: pong"), state.userTexts())
        assertEquals(listOf("pong"), state.assistantTexts())
        state = reduce(state, SessionEvent.UserSent("live-u1", "again"))
        assertTrue(state.busy)
        state = reduce(state, SessionEvent.Cli(Ndjson.decode("""{"type":"assistant","uuid":"live-a1","message":{"id":"live-m1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"pong again"}]}}""")))
        state = reduce(state, SessionEvent.Cli(Ndjson.decode("""{"type":"result","subtype":"success","is_error":false,"session_id":"x"}""")))
        assertFalse(state.busy)
        assertEquals(state.entries.size, state.entries.map { it.id }.toSet().size)
        assertEquals(listOf("pong", "pong again"), state.assistantTexts())
    }

    @Test
    fun `recorded transcripts give their title, last model, effort, permission mode and folder`() = runBlocking<Unit> {
        val reply = TranscriptReader.read(Fixtures.transcript("history-reply"))
        assertEquals(
            TranscriptMeta(
                sessionId = "history-reply", // the fixture's file name stands in for <session id>.jsonl
                customTitle = null,
                generatedTitle = "Pong",
                firstPrompt = "Reply with the single word: pong",
                lastModel = "claude-sonnet-5-5",
                lastEffort = "high",
                lastPermissionMode = PermissionMode.DEFAULT,
                cwd = "<path>", // as redacted by the recorder
                lineCount = 23,
            ),
            reply.meta,
        )
        assertEquals("Pong", reply.meta.title)
        assertEquals(reply.meta, TranscriptReader.meta(Fixtures.transcript("history-reply")))
        // A reopened session shows the effort and permission mode its transcript last recorded.
        assertEquals("high", reply.state.effort)
        assertEquals(PermissionMode.DEFAULT, reply.state.permissionMode)

        val permission = TranscriptReader.meta(Fixtures.transcript("history-permission"))
        assertEquals("Touch tandem-fixture.txt file", permission.title)
        // The first prompt as recorded is 82 characters long.
        assertEquals("Use the Bash tool to run exactly: touch tandem-fixture.txt. Then reply with: don", permission.firstPrompt)
        assertEquals(35, permission.lineCount)

        // The host renamed the original session; its custom-title beats the generated one, and the fork carries it over.
        val origin = TranscriptReader.meta(Fixtures.transcript("fork-origin"))
        assertEquals("Renamed by host", origin.customTitle)
        assertEquals("Alpha reply", origin.generatedTitle)
        assertEquals("Renamed by host", origin.title)
        val fork = TranscriptReader.read(Fixtures.transcript("fork"))
        assertEquals("Renamed by host", fork.meta.title)
        assertEquals(listOf("Reply with the single word: alpha", "Reply with the single word: beta"), fork.state.userTexts())
        assertEquals(listOf("alpha", "beta"), fork.state.assistantTexts())
    }

    @Test
    fun `reads a large transcript in linear time`(@TempDir dir: Path) = runBlocking<Unit> {
        // Long sessions are what the sidebar is for: 4,000 turns of lines shaped like the CLI's, about 2.8 MB.
        val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val f = dir.resolve("$id.jsonl")
        f.bufferedWriter().use { out ->
            for (n in 1..4_000) {
                out.write("""{"parentUuid":"a-${n - 1}","isSidechain":false,"type":"user","message":{"role":"user","content":"prompt $n"},"uuid":"u-$n","timestamp":"2026-10-16T12:00:00.000Z","permissionMode":"default","cwd":"/work/app","sessionId":"$id"}""" + "\n")
                out.write("""{"parentUuid":"u-$n","isSidechain":false,"message":{"model":"claude-sonnet-5-5","id":"m-$n","type":"message","role":"assistant","content":[{"type":"text","text":"reply $n"}],"stop_reason":"end_turn","usage":{"input_tokens":2,"output_tokens":3}},"requestId":"req-$n","type":"assistant","uuid":"a-$n","timestamp":"2026-10-16T12:00:01.000Z","effort":"medium","cwd":"/work/app","sessionId":"$id"}""" + "\n")
            }
            out.write("""{"type":"custom-title","customTitle":"Long session","sessionId":"$id"}""" + "\n")
        }
        assertTrue(f.fileSize() in 2_000_000L..3_000_000L, "generated ${f.fileSize()} bytes")

        val loaded: LoadedTranscript
        val millis = measureTimeMillis { loaded = TranscriptReader.read(f) }
        assertEquals(8_000, loaded.state.entries.size)
        assertEquals(listOf("u-4000", "m-4000"), loaded.state.entries.takeLast(2).map { it.id })
        assertEquals("Long session", loaded.meta.title)
        assertTrue(millis < 5_000, "reading took $millis ms")
    }

    @Test
    fun `a file that cannot be opened makes read throw and meta fall back to the id`(@TempDir dir: Path) {
        val id = "77777777-7777-4777-8777-777777777777"
        val missing = dir.resolve("$id.jsonl")
        assertFailsWith<IOException> { runBlocking { TranscriptReader.read(missing) } }
        val meta = TranscriptReader.meta(missing)
        assertEquals(emptyMeta(id), meta)
        assertEquals(id, meta.title)
    }

    @Test
    fun `an IOException partway through keeps what was read before it`() = runBlocking<Unit> {
        val lines = listOf(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"hello"}}""",
            """{"type":"assistant","uuid":"a1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"hi"}]}}""",
        )
        val failing = object : BufferedReader(StringReader(lines.joinToString("\n"))) {
            private var calls = 0

            override fun readLine(): String? = if (++calls > lines.size) throw IOException("device gone") else super.readLine()
        }
        val loaded = TranscriptReader.readFrom(failing, "88888888-8888-4888-8888-888888888888")
        assertEquals(listOf("u1", "msg_1"), loaded.state.entries.map { it.id })
        assertFalse(loaded.state.busy)
        assertEquals(2, loaded.meta.lineCount)
        assertEquals("claude-sonnet-5-5", loaded.meta.lastModel)
    }

    @Test
    fun `read checks for cancellation before every line`() = runBlocking<Unit> {
        var linesRead = 0
        val reading = launch {
            val self = coroutineContext.job
            val endless = object : BufferedReader(StringReader("")) {
                override fun readLine(): String? {
                    linesRead++
                    if (linesRead == 2) self.cancel()
                    // Ends after 100 lines, so a reader that ignored cancellation fails the test instead of hanging it.
                    return if (linesRead > 100) null else """{"type":"queue-operation"}"""
                }
            }
            TranscriptReader.readFrom(endless, "99999999-9999-4999-8999-999999999999")
        }
        reading.join()
        assertTrue(reading.isCancelled)
        assertEquals(2, linesRead)
    }

    @Test
    fun `settled ends a turn in progress`() {
        val draft = ChatEntry.Assistant(
            id = "msg_1",
            messageId = "msg_1",
            parentToolUseId = null,
            model = "claude-sonnet-5-5",
            blocks = listOf(AssistantBlock.Text("Hal")),
            completedBlocks = 0,
            streaming = true,
            uuids = emptySet(),
        )
        val ask = PendingPermission("req_1", "Bash", JsonObject(emptyMap()), "toolu_1", null, emptyList())
        val running = ChatState(
            entries = listOf(ChatEntry.User("u1", "hi"), draft),
            activeModel = "claude-sonnet-5-5",
            busy = true,
            pendingPermissions = listOf(ask),
            ended = true,
        )
        assertEquals(
            running.copy(
                entries = listOf(ChatEntry.User("u1", "hi"), draft.copy(streaming = false)),
                busy = false,
                pendingPermissions = emptyList(),
                ended = false,
            ),
            running.settled(),
        )
    }
}
