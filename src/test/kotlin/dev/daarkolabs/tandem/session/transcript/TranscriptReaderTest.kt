package dev.daarkolabs.tandem.session.transcript

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.NoticeKind
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.PendingPermission
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.UserImage
import dev.daarkolabs.tandem.session.reduce
import dev.daarkolabs.tandem.session.settled
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import java.util.BitSet
import kotlin.io.path.appendText
import kotlin.io.path.bufferedWriter
import kotlin.io.path.fileSize
import kotlin.io.path.readLines
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The base64 of `scripts/drive_cli.py`'s `tiny_png()`, the image the `user-image` fixture sent; `test_drive_cli.py` pins the same. */
private const val TINY_PNG_BASE64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGP4z8AARAwQCgAf7gP9i18U1AAAAABJRU5ErkJggg=="

class TranscriptReaderTest {
    private fun ChatState.userTexts(): List<String> = entries.filterIsInstance<ChatEntry.User>().map { it.text }

    private fun ChatState.assistantTexts(): List<String> =
        entries.filterIsInstance<ChatEntry.Assistant>().flatMap { it.blocks }.filterIsInstance<AssistantBlock.Text>().map { it.text }

    /** The entries as short strings, so one assertion can state a whole history. */
    private fun ChatState.shape(): List<String> = entries.map { entry ->
        when (entry) {
            is ChatEntry.User -> "user: ${entry.text}"
            is ChatEntry.Assistant -> "assistant: " + entry.blocks.joinToString { block ->
                when (block) {
                    is AssistantBlock.Text -> block.text
                    is AssistantBlock.ToolUse -> "tool ${block.name}"
                    is AssistantBlock.Thinking -> "thinking"
                    is AssistantBlock.Other -> block.type
                }
            }
            is ChatEntry.ToolResult -> "result: ${entry.text}" + if (entry.isError) " (error)" else ""
            is ChatEntry.Notice -> "${entry.level}: ${entry.text}"
            is ChatEntry.TurnEnded -> "end: ${entry.end}"
        }
    }

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

        // The MCP fixtures: the same replies and tool results, and the same local-command notices by level and text. The stream's
        // other notices (mcp-status's `The CLI rejected a request: …` for its error answers) have no line on disk, so they stay out
        // of the comparison; `command` differs, since the stream is folded without the prompt the CLI does not echo.
        for (name in listOf("mcp-status", "mcp-slash", "mcp-tool-call")) {
            val stream = Fixtures.decode(name).fold(ChatState()) { s, m -> reduce(s, SessionEvent.Cli(m)) }
            val disk = TranscriptReader.read(Fixtures.transcript(name)).state
            assertEquals(
                stream.entries.filterIsInstance<ChatEntry.Assistant>().map { it.blocks },
                disk.entries.filterIsInstance<ChatEntry.Assistant>().map { it.blocks },
                name,
            )
            assertEquals(
                stream.entries.filterIsInstance<ChatEntry.ToolResult>().map { it.text to it.isError },
                disk.entries.filterIsInstance<ChatEntry.ToolResult>().map { it.text to it.isError },
                name,
            )
            assertEquals(stream.localNotices(), disk.localNotices(), name)
        }
        assertEquals(3, TranscriptReader.read(Fixtures.transcript("mcp-slash")).state.localNotices().size)
    }

    private fun ChatState.localNotices(): List<Pair<NoticeLevel, String>> =
        entries.filterIsInstance<ChatEntry.Notice>().filter { it.kind == NoticeKind.LOCAL_COMMAND }.map { it.level to it.text }

    @Test
    fun `a local command on disk is the same notice as its stream`() = runBlocking<Unit> {
        val loaded = TranscriptReader.read(Fixtures.transcript("mcp-slash")).state
        val notices = loaded.entries.filterIsInstance<ChatEntry.Notice>()
        assertEquals(
            listOf(
                "user: /mcp", "INFO: ${notices[0].text}", "user: /model", "INFO: ${notices[1].text}",
                "user: /help", "INFO: /help isn't available in this environment.", "user: /hello", "assistant: hello from the hello command",
            ),
            loaded.shape(),
        )
        assertTrue(notices.all { it.kind == NoticeKind.LOCAL_COMMAND })
        assertEquals(listOf("mcp", "model", "help"), notices.map { it.command })
        assertEquals(listOf("mcp", "model", null), notices.map { it.runCommand })
        // transcripts/mcp-slash.jsonl:13, the typed /help, which has no <command-name> line.
        assertEquals("<uuid-50>", loaded.entries.filterIsInstance<ChatEntry.User>()[2].id)

        // The stream's notices, with the prompts the CLI does not echo: the same but for their ids.
        val lines = Fixtures.decode("mcp-slash")
        fun streamed(prompt: String, from: Int, to: Int): ChatEntry.Notice =
            lines.subList(from - 1, to).fold(reduce(ChatState(), SessionEvent.UserSent("u1", prompt))) { s, m -> reduce(s, SessionEvent.Cli(m)) }
                .entries.filterIsInstance<ChatEntry.Notice>().single()
        assertEquals(streamed("/mcp", 2, 6).copy(id = "n"), notices[0].copy(id = "n"))
        assertEquals(streamed("/help", 14, 18).copy(id = "n"), notices[2].copy(id = "n"))
    }

    @Test
    fun `local command lines load by their shape and other system lines are skipped`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("31313131-3131-4131-8131-313131313131.jsonl")
        f.writeLines(
            // The typed command without a uuid, then its error output (unrecorded: no fixture has <local-command-stderr>).
            """{"type":"system","subtype":"local_command","content":"/nope now","submitUuid":"s1","timestamp":"2026-10-21T10:23:55.130Z"}""",
            """{"type":"system","subtype":"local_command","uuid":"o1","content":"<local-command-stderr>Unknown command: /nope</local-command-stderr>"}""",
            // Bare content without a submitUuid, a meta line and a line without content are no rows.
            """{"type":"system","subtype":"local_command","uuid":"x1","content":"just text"}""",
            """{"type":"system","subtype":"local_command","uuid":"x2","isMeta":true,"content":"<local-command-stdout>hidden</local-command-stdout>"}""",
            """{"type":"system","subtype":"local_command","uuid":"x3","commandRun":{"command":"mcp"}}""",
            """{"type":"system","subtype":"informational","uuid":"x4","content":"<local-command-stdout>other</local-command-stdout>"}""",
            // Output with a run and no wrapper is still the command's.
            """{"type":"system","subtype":"local_command","uuid":"o2","content":"plain","commandRun":{"command":"cost","args":""}}""",
        )
        val loaded = TranscriptReader.read(f).state
        assertEquals(listOf("user: /nope now", "INFO: Unknown command: /nope", "INFO: plain"), loaded.shape())
        val user = loaded.entries.first() as ChatEntry.User
        assertEquals("line-1", user.id)
        assertEquals(Instant.parse("2026-10-21T10:23:55.130Z").toEpochMilli(), user.sentAtMillis)
        val notices = loaded.entries.filterIsInstance<ChatEntry.Notice>()
        assertEquals(listOf("nope", "nope"), notices.map { it.command })
        assertEquals(listOf(null, "cost"), notices.map { it.runCommand })
        assertFalse(loaded.busy)
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
        assertEquals(ChatState(sessionId = id), empty.state)
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
        val next = """{"type":"user","uuid":"u2","parentUuid":"u1","message":{"role":"user","content":"after"}}""".toByteArray() +
            "\n".toByteArray()
        f.writeBytes(broken + next)
        assertEquals(listOf("caf\uFFFD", "after"), TranscriptReader.read(f).state.userTexts())
    }

    @Test
    fun `a transcript that ends mid-turn loads settled`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("55555555-5555-4555-8555-555555555555.jsonl")
        // The process stopped right after asking for a tool: a tool_use with no result after it.
        f.writeLines(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"List the files"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"Listing them."},{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls"}}]}}""",
        )
        val state = TranscriptReader.read(f).state
        assertFalse(state.busy)
        assertFalse(state.ended)
        assertTrue(state.pendingPermissions.isEmpty())
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
            listOf(
                ChatEntry.ToolResult("result-toolu_1", "toolu_1", "file.txt", false, content = JsonPrimitive("file.txt"), atMillis = 0L),
                ChatEntry.User("u1", "also check\nthe docs", sentAtMillis = 0L),
            ),
            TranscriptReader.read(f).state.entries,
        )
    }

    @Test
    fun `an interrupt marker loads as the Stopped notice and is never a prompt`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("12121212-1212-4121-8121-121212121212.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"Write a long story"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"Once upon"}]}}""",
            """{"type":"user","uuid":"u2","parentUuid":"a1","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}""",
            """{"type":"user","uuid":"u3","parentUuid":"u2","message":{"role":"user","content":"List the files instead"}}""",
            """{"type":"assistant","uuid":"a3","parentUuid":"u3","message":{"id":"msg_2","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls"}}]}}""",
            // A tool result and the marker on one line are delivered once, so the result is not shown twice.
            """{"type":"user","uuid":"u4","parentUuid":"a3","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"Interrupted.","is_error":true},{"type":"text","text":"[Request interrupted by user for tool use]"}]}}""",
        )
        assertEquals(
            listOf(
                "user: Write a long story",
                "assistant: Once upon",
                "INFO: Stopped.",
                "user: List the files instead",
                "assistant: tool Bash",
                "result: Interrupted. (error)",
                "INFO: Stopped.",
            ),
            TranscriptReader.read(f).state.shape(),
        )

        // The marker never stands in for the title either.
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"[Request interrupted by user]"}}""",
            """{"type":"user","uuid":"u2","parentUuid":"u1","message":{"role":"user","content":"The real first prompt"}}""",
        )
        assertEquals("The real first prompt", TranscriptReader.meta(f).firstPrompt)
    }

    @Test
    fun `a marker in any text block of a user line is an interrupt, as the live stream shows it`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("31313131-3131-4313-8313-313131313131.jsonl")
        val marked = """{"type":"user","uuid":"u2","parentUuid":"u1","message":{"role":"user","content":[{"type":"text","text":"Partial words"},{"type":"text","text":"[Request interrupted by user]"}]}}"""
        f.writeLines("""{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"Write a long story"}}""", marked)
        val live = reduce(ChatState(), SessionEvent.Cli(Ndjson.decode(marked)))
        assertEquals(listOf("INFO: Stopped."), live.shape())
        assertEquals(listOf("user: Write a long story", "INFO: Stopped."), TranscriptReader.read(f).state.shape())
    }

    @Test
    fun `a recorded interrupt loads as the Stopped notice and not as a prompt`() = runBlocking<Unit> {
        // CLI 2.1.301 cut the reply off after its first text delta and wrote its marker the way the test above does:
        // a user line whose content is [{"type":"text","text":"[Request interrupted by user]"}], parented to the reply.
        val loaded = TranscriptReader.read(Fixtures.transcript("history-interrupt"))
        assertEquals(
            listOf("user: Count from 1 to 300, one number per line, nothing else.", "assistant: 1", "INFO: Stopped."),
            loaded.state.shape(),
        )
        assertFalse(loaded.state.busy)
        assertEquals("Count from 1 to 300, one number per line, nothing else.", loaded.meta.firstPrompt)
    }

    @Test
    fun `a compact summary is skipped entirely`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("13131313-1313-4131-8131-131313131313.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"c1","isCompactSummary":true,"isVisibleInTranscriptOnly":true,"permissionMode":"plan","message":{"role":"user","content":"This session is being continued from a previous conversation that ran out of context."}}""",
            """{"type":"user","uuid":"u1","parentUuid":"c1","message":{"role":"user","content":"Now run the tests"}}""",
        )
        val loaded = TranscriptReader.read(f)
        assertEquals(listOf("user: Now run the tests"), loaded.state.shape())
        assertEquals("Now run the tests", loaded.meta.firstPrompt)
        assertNull(loaded.meta.lastPermissionMode)
    }

    @Test
    fun `a slash command loads as the command and its local output is skipped`(@TempDir dir: Path) = runBlocking<Unit> {
        val id = "14141414-1414-4141-8141-141414141414"
        val f = dir.resolve("$id.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"m1","parentUuid":null,"isMeta":true,"message":{"role":"user","content":"<local-command-caveat>Caveat: generated by local commands.</local-command-caveat>"}}""",
            """{"type":"user","uuid":"c1","parentUuid":"m1","message":{"role":"user","content":"<command-name>/model</command-name>\n            <command-message>model</command-message>\n            <command-args>opus</command-args>"}}""",
            """{"type":"user","uuid":"o1","parentUuid":"c1","message":{"role":"user","content":"<local-command-stdout>Set model to opus</local-command-stdout>"}}""",
            """{"type":"user","uuid":"c2","parentUuid":"o1","message":{"role":"user","content":[{"type":"text","text":"<command-name>/clear</command-name>\n<command-message>clear</command-message>\n<command-args></command-args>"}]}}""",
            """{"type":"user","uuid":"o2","parentUuid":"c2","message":{"role":"user","content":"<local-command-stdout></local-command-stdout>"}}""",
            // The CLI also writes the message tag first, and counts a text that starts with either tag as a command.
            """{"type":"user","uuid":"c3","parentUuid":"o2","message":{"role":"user","content":"<command-message>review</command-message>\n<command-name>/review</command-name>\n<command-args>src/</command-args>"}}""",
        )
        val loaded = TranscriptReader.read(f)
        assertEquals(listOf("user: /model opus", "user: /clear", "user: /review src/"), loaded.state.shape())
        // A command does not stand in for the title.
        assertNull(loaded.meta.firstPrompt)
        assertEquals(id, loaded.meta.title)

        // The first prompt is the first text that is not a command.
        f.writeLines(
            """{"type":"user","uuid":"c1","parentUuid":null,"message":{"role":"user","content":"<command-name>/clear</command-name>\n<command-message>clear</command-message>\n<command-args></command-args>"}}""",
            """{"type":"user","uuid":"q1","parentUuid":"c1","message":{"role":"user","content":"real question"}}""",
        )
        val next = TranscriptReader.read(f)
        assertEquals(listOf("user: /clear", "user: real question"), next.state.shape())
        assertEquals("real question", next.meta.firstPrompt)
        assertEquals("real question", next.meta.title)
    }

    @Test
    fun `an assistant line's requestedModel is no pick, so a resume leaves the model to the CLI`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("12121212-1212-4212-8212-121212121212.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"hello"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","requestedModel":"claude-opus-5-5","message":{"id":"msg_1","role":"assistant","model":"claude-opus-5-5","content":[{"type":"text","text":"hi"}]}}""",
        )
        val loaded = TranscriptReader.read(f)
        // The CLI resumes a session with the model it last ran (docs/protocol.md 8): Tandem passes no --model unless one is picked.
        assertNull(loaded.state.requestedModel)
        assertEquals("claude-opus-5-5", loaded.state.activeModel)
        assertEquals("claude-opus-5-5", loaded.meta.lastModel)
    }

    @Test
    fun `a synthetic assistant line changes neither the last model nor the last effort`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("15151515-1515-4151-8151-151515151515.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"hi"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","effort":"high","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"hello"}]}}""",
            """{"type":"user","uuid":"u2","parentUuid":"a1","message":{"role":"user","content":"again"}}""",
            // Written by the CLI itself, e.g. for an API error: a model that is not one, and no effort.
            """{"type":"assistant","uuid":"a2","parentUuid":"u2","isApiErrorMessage":true,"message":{"id":"msg_2","role":"assistant","model":"<synthetic>","content":[{"type":"text","text":"API Error: 500"}]}}""",
        )
        val loaded = TranscriptReader.read(f)
        assertEquals("claude-sonnet-5-5", loaded.meta.lastModel)
        assertEquals("high", loaded.meta.lastEffort)
        assertEquals("high", loaded.state.effort)
        // The model the session shows is the last real one too.
        assertEquals("claude-sonnet-5-5", loaded.state.activeModel)
        // Still part of the history, as it is live.
        assertEquals(listOf("hello", "API Error: 500"), loaded.state.assistantTexts())
    }

    @Test
    fun `a rewind leaves the abandoned turns out`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("16161616-1616-4161-8161-161616161616.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"first"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"one"}]}}""",
            """{"type":"user","uuid":"u2","parentUuid":"a1","permissionMode":"plan","message":{"role":"user","content":"abandoned"}}""",
            """{"type":"assistant","uuid":"a2","parentUuid":"u2","effort":"low","message":{"id":"msg_2","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"tool_use","id":"t9","name":"Bash","input":{"command":"ls"}}]}}""",
            """{"type":"user","uuid":"r9","parentUuid":"a2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t9","content":"abandoned result"}]}}""",
            // Rewound to a1 in the terminal: the next prompt continues from there, and u2, a2 and r9 stay in the file.
            """{"type":"user","uuid":"u3","parentUuid":"a1","message":{"role":"user","content":"second"}}""",
            // A side branch with a message id of its own stays out, although its parent is kept.
            """{"type":"assistant","uuid":"a3x","parentUuid":"u3","message":{"id":"msg_3x","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"cut short"}]}}""",
            """{"type":"assistant","uuid":"a3","parentUuid":"u3","message":{"id":"msg_3","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"two"}]}}""",
        )
        val loaded = TranscriptReader.read(f)
        assertEquals(listOf("user: first", "assistant: one", "user: second", "assistant: two"), loaded.state.shape())
        assertEquals(8, loaded.meta.lineCount)
        // Nothing of the abandoned turn counts, not even its permission mode or effort.
        assertNull(loaded.meta.lastPermissionMode)
        assertNull(loaded.meta.lastEffort)
        assertEquals(loaded.meta, TranscriptReader.meta(f))
    }

    @Test
    fun `each part before a compaction keeps its own current chain`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("17171717-1717-4171-8171-171717171717.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"first"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"one"}]}}""",
            """{"type":"user","uuid":"ux","parentUuid":"a1","message":{"role":"user","content":"abandoned"}}""",
            """{"type":"user","uuid":"uy","parentUuid":"a1","message":{"role":"user","content":"first again"}}""",
            // The compaction: a boundary that roots a new chain, then the summary the CLI hands the model.
            """{"type":"system","subtype":"compact_boundary","uuid":"b1","parentUuid":null,"logicalParentUuid":"uy","content":"Conversation compacted"}""",
            """{"type":"user","uuid":"s1","parentUuid":"b1","isCompactSummary":true,"message":{"role":"user","content":"This session is being continued from a previous conversation."}}""",
            """{"type":"user","uuid":"u2","parentUuid":"s1","message":{"role":"user","content":"second"}}""",
            """{"type":"assistant","uuid":"a2","parentUuid":"u2","message":{"id":"msg_2","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"two"}]}}""",
            // A compact summary can root the new chain itself.
            """{"type":"user","uuid":"s2","parentUuid":null,"isCompactSummary":true,"message":{"role":"user","content":"This session is being continued again."}}""",
            """{"type":"user","uuid":"u3","parentUuid":"s2","message":{"role":"user","content":"third"}}""",
        )
        assertEquals(
            listOf("user: first", "assistant: one", "user: first again", "user: second", "assistant: two", "user: third"),
            TranscriptReader.read(f).state.shape(),
        )
    }

    @Test
    fun `parallel tool calls off the chain stay in the history`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("21212121-2121-4212-8212-212121212121.jsonl")
        // Two tool calls in one message: each block has its own line, and the results branch off the blocks they answer.
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"Read both files"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"m1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"a.txt"}}]}}""",
            """{"type":"assistant","uuid":"a2","parentUuid":"a1","message":{"id":"m1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"b.txt"}}]}}""",
            """{"type":"user","uuid":"r2","parentUuid":"a2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":"bee"}]}}""",
            """{"type":"user","uuid":"r1","parentUuid":"a1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ay"}]}}""",
            """{"type":"assistant","uuid":"a3","parentUuid":"r1","message":{"id":"m2","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"Both read."}]}}""",
        )
        assertEquals(
            listOf("user: Read both files", "assistant: tool Read, tool Read", "result: bee", "result: ay", "assistant: Both read."),
            TranscriptReader.read(f).state.shape(),
        )
    }

    @Test
    fun `the chain runs through attachment and system lines`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("23232323-2323-4232-8232-232323232323.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"first"}}""",
            """{"type":"attachment","uuid":"x1","parentUuid":"u1","attachment":{"type":"date"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"x1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"one"}]}}""",
            """{"type":"system","subtype":"informational","uuid":"y1","parentUuid":"a1","content":"Noted"}""",
            """{"type":"user","uuid":"u2","parentUuid":"y1","message":{"role":"user","content":"second"}}""",
            """{"type":"assistant","uuid":"a2","parentUuid":"u2","message":{"id":"msg_2","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"two"}]}}""",
        )
        assertEquals(listOf("user: first", "assistant: one", "user: second", "assistant: two"), TranscriptReader.read(f).state.shape())
    }

    @Test
    fun `a conversational line without a uuid stays in the history`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("24242424-2424-4242-8242-242424242424.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"first"}}""",
            """{"type":"user","message":{"role":"user","content":"no uuid"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"one"}]}}""",
        )
        // No chain can reach it, so the walk keeps it rather than drop it; its entry id falls back to the line number.
        val entries = TranscriptReader.read(f).state.entries
        assertEquals(listOf("u1", "line-2", "msg_1"), entries.map { it.id })
    }

    @Test
    fun `a last conversational line without a uuid does not hide the chain before it`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("25252525-2525-4252-8252-252525252525.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"first"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"one"}]}}""",
            """{"type":"user","message":{"role":"user","content":"no uuid"}}""",
        )
        // The chain ends at the last line that has a uuid; the line without one is kept all the same.
        assertEquals(listOf("user: first", "assistant: one", "user: no uuid"), TranscriptReader.read(f).state.shape())
    }

    @Test
    fun `a compaction goes on from its logical parent when the boundary names one`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("26262626-2626-4262-8262-262626262626.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"first"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"one"}]}}""",
            """{"type":"user","uuid":"ux","parentUuid":"a1","message":{"role":"user","content":"compacted from here"}}""",
            // Written after ux but on a branch the compaction did not continue: the boundary says it follows ux.
            """{"type":"user","uuid":"uy","parentUuid":"a1","message":{"role":"user","content":"a branch left behind"}}""",
            """{"type":"system","subtype":"compact_boundary","uuid":"b1","parentUuid":null,"logicalParentUuid":"ux","content":"Conversation compacted"}""",
            """{"type":"user","uuid":"s1","parentUuid":"b1","isCompactSummary":true,"message":{"role":"user","content":"This session is being continued from a previous conversation."}}""",
            """{"type":"user","uuid":"u2","parentUuid":"s1","message":{"role":"user","content":"second"}}""",
        )
        assertEquals(
            listOf("user: first", "assistant: one", "user: compacted from here", "user: second"),
            TranscriptReader.read(f).state.shape(),
        )
    }

    @Test
    fun `a user line with text beside its tool results never joins off the chain`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("27272727-2727-4272-8272-272727272727.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"Run it"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"m1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""",
            // Off the chain, parent kept, but with a prompt beside the result: a rewound branch, not a parallel call's result.
            """{"type":"user","uuid":"rx","parentUuid":"a1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"old"},{"type":"text","text":"rewound prompt"}]}}""",
            """{"type":"user","uuid":"r1","parentUuid":"a1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"files"}]}}""",
            """{"type":"assistant","uuid":"a2","parentUuid":"r1","message":{"id":"m2","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"Done."}]}}""",
        )
        assertEquals(
            listOf("user: Run it", "assistant: tool Bash", "result: files", "assistant: Done."),
            TranscriptReader.read(f).state.shape(),
        )
    }

    @Test
    fun `lines that join off the chain keep joining until nothing changes`() {
        // r2 comes before a2, the line it answers, so it can only join once a2 has: on a second round.
        val chain = TranscriptChain()
        listOf(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"Read both files"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"m1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"a.txt"}}]}}""",
            """{"type":"user","uuid":"r2","parentUuid":"a2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":"bee"}]}}""",
            """{"type":"assistant","uuid":"a2","parentUuid":"a1","message":{"id":"m1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"b.txt"}}]}}""",
            """{"type":"user","uuid":"r1","parentUuid":"a1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ay"}]}}""",
            """{"type":"assistant","uuid":"a3","parentUuid":"r1","message":{"id":"m2","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"Both read."}]}}""",
        ).forEach(chain::add)
        assertEquals(BitSet(), chain.abandoned())
    }

    @Test
    fun `a prompt that quotes a CLI tag further on stays a prompt`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("18181818-1818-4181-8181-181818181818.jsonl")
        val prompt = "Explain <command-name>/model</command-name> and <bash-stdout> in transcripts"
        f.writeLines("""{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"$prompt"}}""")
        val loaded = TranscriptReader.read(f)
        assertEquals(listOf("user: $prompt"), loaded.state.shape())
        assertEquals(prompt, loaded.meta.firstPrompt)
    }

    @Test
    fun `a shell escape loads as its command, and output and notifications are skipped`(@TempDir dir: Path) = runBlocking<Unit> {
        val f = dir.resolve("19191919-1919-4191-8191-191919191919.jsonl")
        f.writeLines(
            """{"type":"user","uuid":"b1","parentUuid":null,"message":{"role":"user","content":"<bash-input>ls -la</bash-input>"}}""",
            """{"type":"user","uuid":"b2","parentUuid":"b1","message":{"role":"user","content":"<bash-stdout>notes.txt</bash-stdout><bash-stderr></bash-stderr>"}}""",
            """{"type":"user","uuid":"b3","parentUuid":"b2","message":{"role":"user","content":"<bash-stderr>Command failed: cat nothing.txt</bash-stderr>"}}""",
            """{"type":"user","uuid":"c1","parentUuid":"b3","message":{"role":"user","content":"<local-command-stderr>Unknown command: /nope</local-command-stderr>"}}""",
            """{"type":"user","uuid":"n1","parentUuid":"c1","message":{"role":"user","content":"<task-notification>\n<task-id>bg-1</task-id>\n<status>completed</status>\n</task-notification>"}}""",
        )
        val loaded = TranscriptReader.read(f)
        assertEquals(listOf("user: !ls -la"), loaded.state.shape())
        assertEquals("!ls -la", loaded.meta.firstPrompt)
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

    // polish spec 5, edge 3: assistant lines' usage is not decoded, so a reloaded session draws no ring.
    @Test
    fun `a reloaded transcript has no context`() = runBlocking<Unit> {
        assertNull(TranscriptReader.read(Fixtures.transcript("history-reply")).state.context)
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
        // A resumed session knows its id before init arrives: the transcript's file name.
        assertEquals("history-reply", reply.state.sessionId)
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
        // Long sessions are what the sidebar is for: turns of lines shaped like the CLI's, 4,000 of them about 2.8 MB.
        fun write(turns: Int): Path {
            val id = "aaaaaaaa-aaaa-4aaa-8aaa-%012d".format(turns)
            val f = dir.resolve("$id.jsonl")
            f.bufferedWriter().use { out ->
                for (n in 1..turns) {
                    out.write("""{"parentUuid":"a-${n - 1}","isSidechain":false,"type":"user","message":{"role":"user","content":"prompt $n"},"uuid":"u-$n","timestamp":"2026-10-16T12:00:00.000Z","permissionMode":"default","cwd":"/work/app","sessionId":"$id"}""" + "\n")
                    out.write("""{"parentUuid":"u-$n","isSidechain":false,"message":{"model":"claude-sonnet-5-5","id":"m-$n","type":"message","role":"assistant","content":[{"type":"text","text":"reply $n"}],"stop_reason":"end_turn","usage":{"input_tokens":2,"output_tokens":3}},"requestId":"req-$n","type":"assistant","uuid":"a-$n","timestamp":"2026-10-16T12:00:01.000Z","effort":"medium","cwd":"/work/app","sessionId":"$id"}""" + "\n")
                }
                out.write("""{"type":"custom-title","customTitle":"Long session","sessionId":"$id"}""" + "\n")
            }
            return f
        }
        val small = write(2_000)
        val large = write(8_000)
        assertTrue(large.fileSize() in 5_000_000L..6_500_000L, "generated ${large.fileSize()} bytes")
        TranscriptReader.read(small) // warms up the JIT, so that the first timed read is not the slow one

        val smallMillis = measureTimeMillis { TranscriptReader.read(small) }
        val loaded: LoadedTranscript
        val largeMillis = measureTimeMillis { loaded = TranscriptReader.read(large) }
        assertEquals(16_000, loaded.state.entries.size)
        assertEquals(listOf("u-8000", "m-8000"), loaded.state.entries.takeLast(2).map { it.id })
        assertEquals("Long session", loaded.meta.title)
        // Four times the lines: linear is about four times the time, quadratic sixteen. The slack absorbs a garbage collection.
        assertTrue(largeMillis < 8 * smallMillis + 500, "2,000 turns took $smallMillis ms, 8,000 took $largeMillis ms")
        assertTrue(largeMillis < 10_000, "reading took $largeMillis ms")
    }

    @Test
    fun `a file that cannot be opened makes read throw and meta fall back to the id`(@TempDir dir: Path) {
        val id = "77777777-7777-4777-8777-777777777777"
        // As root, a test cannot make a file unreadable: a path below a plain file cannot be opened either.
        val unopenable = dir.resolve("not-a-folder").also { it.writeText("") }.resolve("$id.jsonl")
        assertFailsWith<IOException> { runBlocking { TranscriptReader.read(unopenable) } }
        val meta = TranscriptReader.meta(unopenable)
        assertEquals(emptyMeta(id), meta)
        assertEquals(id, meta.title)
    }

    @Test
    fun `meta of a file that is gone throws, so that the index leaves it out`(@TempDir dir: Path) {
        assertFailsWith<NoSuchFileException> { TranscriptReader.meta(dir.resolve("77777777-7777-4777-8777-777777777777.jsonl")) }
    }

    @Test
    fun `an IOException in the first pass shows nothing, in the second what was parsed`() = runBlocking<Unit> {
        val id = "88888888-8888-4888-8888-888888888888"
        val lines = listOf(
            """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"hello"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"hi"}]}}""",
            """{"type":"user","uuid":"u2","parentUuid":"a1","message":{"role":"user","content":"and then?"}}""",
        )

        fun reader(failAfter: Int = Int.MAX_VALUE) = object : BufferedReader(StringReader(lines.joinToString("\n"))) {
            private var calls = 0

            override fun readLine(): String? = if (++calls > failAfter) throw IOException("device gone") else super.readLine()
        }

        // In the first pass: nothing to show, and the meta only says how far reading got.
        val first = TranscriptReader.readFrom({ reader(failAfter = 2) }, id)
        assertEquals(emptyList(), first.state.entries)
        assertEquals(emptyMeta(id, lineCount = 2), first.meta)
        assertEquals("IOException", first.readError, "the history is not all there, and the caller is told why")

        // In the second pass: what was parsed before it.
        var opened = 0
        val second = TranscriptReader.readFrom({ if (++opened == 1) reader() else reader(failAfter = 2) }, id)
        assertEquals(listOf("u1", "msg_1"), second.state.entries.map { it.id })
        assertFalse(second.state.busy)
        assertEquals(2, second.meta.lineCount)
        assertEquals("claude-sonnet-5-5", second.meta.lastModel)
        assertEquals("IOException", second.readError)

        // The file cannot be opened again for the second pass: nothing was parsed.
        opened = 0
        val reopened = TranscriptReader.readFrom({ if (++opened == 1) reader() else throw IOException("gone") }, id)
        assertEquals(emptyList(), reopened.state.entries)
        assertEquals(emptyMeta(id), reopened.meta)
        assertEquals("IOException", reopened.readError)

        // Read to the end: no error.
        assertNull(TranscriptReader.readFrom({ reader() }, id).readError)
    }

    @Test
    fun `lines appended between the two passes wait for the next read`() = runBlocking<Unit> {
        val first = """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"hello"}}"""
        val appended = """{"type":"user","uuid":"u2","parentUuid":"u1","message":{"role":"user","content":"written meanwhile"}}"""
        var opened = 0
        val loaded = TranscriptReader.readFrom(
            { BufferedReader(StringReader(if (++opened == 1) first else "$first\n$appended")) },
            "20202020-2020-4202-8202-202020202020",
        )
        // The second pass reads only the lines the first pass placed.
        assertEquals(listOf("user: hello"), loaded.state.shape())
        assertEquals(1, loaded.meta.lineCount)
    }

    @Test
    fun `a user or assistant line the first pass could not place is left out`() = runBlocking<Unit> {
        val first = """{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"hello"}}"""
        val complete = """{"type":"user","uuid":"u2","parentUuid":"u1","message":{"role":"user","content":"still being written"}}"""
        var opened = 0
        val loaded = TranscriptReader.readFrom(
            // The CLI was still writing the second line during the first pass, and had finished it by the second.
            { BufferedReader(StringReader(if (++opened == 1) "$first\n${complete.take(40)}" else "$first\n$complete")) },
            "29292929-2929-4292-8292-292929292929",
        )
        assertEquals(listOf("user: hello"), loaded.state.shape())
        assertEquals(2, loaded.meta.lineCount)
    }

    @Test
    fun `meta reads the file once`() {
        val lines = listOf(
            """{"type":"user","uuid":"u1","parentUuid":null,"cwd":"/p","message":{"role":"user","content":"hello"}}""",
            """{"type":"assistant","uuid":"a1","parentUuid":"u1","effort":"low","message":{"id":"msg_1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"hi"}]}}""",
            """{"type":"custom-title","customTitle":"Mine","sessionId":"x"}""",
        )
        var opened = 0
        val id = "30303030-3030-4303-8303-303030303030"
        val meta = TranscriptReader.metaFrom({
            opened++
            BufferedReader(StringReader(lines.joinToString("\n")))
        }, id)
        assertEquals(1, opened)
        assertEquals(TranscriptMeta(id, "Mine", null, "hello", "claude-sonnet-5-5", "low", null, "/p", 3), meta)
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
            TranscriptReader.readFrom({ endless }, "99999999-9999-4999-8999-999999999999")
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

    private fun read(name: String): ChatState = runBlocking { TranscriptReader.read(Fixtures.transcript(name)) }.state

    private fun result(state: ChatState, id: String): ChatEntry.ToolResult =
        state.entries.filterIsInstance<ChatEntry.ToolResult>().single { it.toolUseId == id }

    /** The id of the call to [tool] in [state]. */
    private fun callId(state: ChatState, tool: String): String =
        state.entries.filterIsInstance<ChatEntry.Assistant>().flatMap { it.blocks }.filterIsInstance<AssistantBlock.ToolUse>().single { it.name == tool }.id

    @Test
    fun `a recorded question session reloads with its answers and decision`() {
        val single = result(read("ask-single"), "<id-12>")
        assertEquals("Blue", single.result!!.jsonObject["answers"]!!.jsonObject["Which colour?"]!!.jsonPrimitive.content)
        assertFalse(single.isError)

        val deny = read("ask-deny")
        val denied = result(deny, callId(deny, "AskUserQuestion"))
        assertTrue(denied.isError)
        assertEquals("reject", denied.decision!!.decision)
        assertTrue(denied.decision.nonExecutionKind != null, denied.decision.toString())
    }

    @Test
    fun `a recorded edit reloads with its patch`() {
        val state = read("tools-edit")
        val results = state.entries.filterIsInstance<ChatEntry.ToolResult>()
        assertTrue(results.any { (it.result as? JsonObject)?.get("structuredPatch")?.jsonArray?.isNotEmpty() == true }, results.toString())
        val write = result(state, callId(state, "Write"))
        assertEquals("create", write.result!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a recorded thinking block reloads with its duration`() {
        val thinking = read("think-default").entries.filterIsInstance<ChatEntry.Assistant>().flatMap { it.blocks }.filterIsInstance<AssistantBlock.Thinking>()
        assertTrue(thinking.any { it.text == "" && it.durationMs == 2143L }, thinking.toString())
    }

    @Test
    fun `a recorded agent session reloads the agent result without children`() {
        val state = read("agent-sync")
        val agent = result(state, "<id-12>").result!!.jsonObject
        assertEquals(2, agent["totalToolUseCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(21781, agent["totalTokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(5829, agent["totalDurationMs"]!!.jsonPrimitive.content.toInt())
        // The subagent's own lines live in <id>/subagents/, which the reader does not read.
        assertTrue(state.entries.none { it is ChatEntry.Assistant && it.parentToolUseId != null })
        assertTrue(state.entries.none { it is ChatEntry.ToolResult && it.parentToolUseId != null })
    }

    @Test
    fun `a reloaded user line with an image yields the same entry as its stream`() {
        val e = read("user-image").entries.filterIsInstance<ChatEntry.User>().first()
        assertEquals("What colour is this image? Reply with one word.", e.text)
        // The uuid of the frame drive_cli sent, as the stream's command_lifecycle lines name it: the redactor gave it one
        // placeholder across the pair, so the id is the same live and on reload.
        val sent = Fixtures.lines("user-image").map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "command_lifecycle" }
            .map { it["command_uuid"]!!.jsonPrimitive.content }
            .distinct()
            .single()
        assertEquals(sent, e.id)
        assertEquals(UserImage("${e.id}#0", "image/png", TINY_PNG_BASE64), e.images.single())
    }

    /** A transcript of one user line holding one image, its data redacted to the placeholder (spec 5.7), and no text. */
    private fun imagesOnly(dir: Path): Path = dir.resolve("66666666-6666-4666-8666-666666666666.jsonl").apply {
        writeLines(
            """{"type":"user","uuid":"u9","timestamp":"2026-10-21T20:26:59.860Z","message":{"role":"user","content":""" +
                """[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"<base64>"}}]}}""",
        )
    }

    @Test
    fun `a reloaded images-only user line keeps its images`(@TempDir dir: Path) = runBlocking<Unit> {
        val state = TranscriptReader.read(imagesOnly(dir)).state
        assertEquals(
            ChatEntry.User(
                "u9",
                "",
                Instant.parse("2026-10-21T20:26:59.860Z").toEpochMilli(),
                listOf(UserImage("u9#0", "image/png", "<base64>")),
            ),
            state.entries.filterIsInstance<ChatEntry.User>().single(),
        )
    }

    // The JVM takes no brackets in a method name: the spec's name is the display name.
    @Test
    @DisplayName("an images-only first prompt is the meta's first prompt [Image #1]")
    fun `an images-only first prompt is the meta's first prompt Image #1`(@TempDir dir: Path) = runBlocking<Unit> {
        val file = imagesOnly(dir)
        assertEquals("[Image #1]", TranscriptReader.meta(file).firstPrompt)
        assertEquals("[Image #1]", TranscriptReader.read(file).meta.title)
    }

    @Test
    fun `images beside tool results with nothing typed make no prompt`(@TempDir dir: Path) = runBlocking<Unit> {
        val file = dir.resolve("77777777-7777-4777-8777-777777777777.jsonl")
        file.writeLines(
            """{"type":"user","uuid":"u8","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"ok"},""" +
                """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$TINY_PNG_BASE64"}}]}}""",
        )
        assertEquals(listOf("result: ok"), TranscriptReader.read(file).state.shape())
        assertNull(TranscriptReader.meta(file).firstPrompt)
    }

    @Test
    fun `timestamps and cwd are read from the lines`() {
        val state = read("ask-single")
        assertEquals("<path>", state.cwd)
        val users = state.entries.filterIsInstance<ChatEntry.User>()
        assertTrue(users.isNotEmpty())
        assertTrue(users.all { (it.sentAtMillis ?: 0L) > 0L }, users.toString())
        val results = state.entries.filterIsInstance<ChatEntry.ToolResult>()
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { (it.atMillis ?: 0L) > 0L }, results.toString())
    }

    @Test
    fun `a transcript without timestamps or cwd still loads`(@TempDir dir: Path) = runBlocking<Unit> {
        // history-reply's prompt (line 3) and reply (line 18), as an older CLI would write them: no timestamp, no cwd. The reply's
        // recorded parent, <uuid-27>, is an attachment line left out, so it names the prompt instead.
        val lines = Fixtures.transcript("history-reply").readLines()
        val prompt = Json.parseToJsonElement(lines[2]).jsonObject
        val reply = Json.parseToJsonElement(lines[17]).jsonObject
        assertEquals("<uuid-17>", prompt["uuid"]!!.jsonPrimitive.content)
        val file = dir.resolve("old-cli.jsonl")
        file.writeLines(
            JsonObject(prompt - "timestamp" - "cwd").toString(),
            JsonObject(reply - "timestamp" - "cwd" + ("parentUuid" to JsonPrimitive("<uuid-17>"))).toString(),
        )
        val state = TranscriptReader.read(file).state
        assertEquals(listOf("user: Reply with the single word: pong", "assistant: pong"), state.shape())
        assertNull(state.cwd)
        assertEquals(0L, state.entries.filterIsInstance<ChatEntry.User>().single().sentAtMillis ?: 0L)
        // The reader stamps a line without a timestamp 0, which the reducer maps to no time (polish spec edge 21).
        assertNull(state.entries.filterIsInstance<ChatEntry.Assistant>().single().atMillis)
    }

    @Test
    fun `a reloaded assistant entry is stamped with its line's timestamp`() {
        // history-reply's reply, line 18.
        val line = Fixtures.transcript("history-reply").readLines()[17]
        val stamped = assertNotNull((Ndjson.decode(line) as CliMessage.Assistant).timestampMillis)
        assertEquals(Instant.parse("2026-10-16T15:28:34.762Z").toEpochMilli(), stamped)
        assertEquals(stamped, read("history-reply").entries.filterIsInstance<ChatEntry.Assistant>().single().atMillis)
    }
}
