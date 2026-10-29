package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ControlPayloads
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.HostMessages
import dev.daarkolabs.tandem.protocol.ImageBlock
import dev.daarkolabs.tandem.protocol.McpAuthStart
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.ToolReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs [ClaudeSession] against a fake CLI (a shell script), so no real `claude` is needed. */
class ClaudeSessionTest {
    private val fixtures = File(System.getProperty("tandem.fixtures") ?: "src/test/resources/fixtures")
    private val tmp: Path = File(System.getProperty("java.io.tmpdir")).toPath()
    private val newSessionId = "11111111-1111-4111-8111-111111111111"

    /** The Python fake CLI that the test task also hands to the platform tests. */
    private val fakeClaude: String = System.getProperty("tandem.claudePath") ?: "src/test/resources/fake-claude.py"

    private fun fakeCli(script: String) = ProcessLauncher { _, dir -> ProcessBuilder("sh", "-c", script).directory(dir.toFile()).start() }

    @Test
    fun `replays a transcript into state and records what the host wrote`() = runBlocking {
        val stdin = File.createTempFile("tandem-stdin", ".ndjson").apply { deleteOnExit() }
        // Echo the fixture, then keep reading stdin until the host closes it, like the CLI does.
        val launcher = fakeCli("cat '${File(fixtures, "plain-reply.ndjson").path}'; cat > '${stdin.path}'")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId, model = "claude-sonnet-5-5"), launcher)
        session.start()
        session.sendUserMessage("Reply with the single word: pong")

        val final = withTimeout(10_000) { session.state.first { state -> !state.busy && state.entries.any { it is ChatEntry.Assistant } } }
        assertNull(final.modelWarning)
        assertEquals("pong", (final.entries.filterIsInstance<ChatEntry.Assistant>().single().blocks.single() as AssistantBlock.Text).text)

        session.close()
        val written = stdin.readLines()
        assertTrue(written[0].contains("\"subtype\":\"initialize\""), written[0])
        assertTrue(written[1].contains("\"type\":\"user\""), written[1])
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `sendUserMessage writes one frame with the image blocks after the text`() = runBlocking {
        val stdin = File.createTempFile("tandem-stdin", ".ndjson").apply { deleteOnExit() }
        val launcher = fakeCli("cat '${File(fixtures, "user-image.ndjson").path}'; cat > '${stdin.path}'")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), launcher)
        session.start()
        session.sendUserMessage("What colour is this image? Reply with one word.", listOf(ImageBlock("image/png", "iVBORw0KGgo=")))

        withTimeout(10_000) { session.state.first { state -> !state.busy && state.entries.any { it is ChatEntry.Assistant } } }
        session.close()
        val written = stdin.readLines()
        val content = Json.parseToJsonElement(written[1]).jsonObject.getValue("message").jsonObject.getValue("content").jsonArray
        assertEquals(listOf("text", "image"), content.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("What colour is this image? Reply with one word.", content[0].jsonObject.getValue("text").jsonPrimitive.content)
        val source = content[1].jsonObject.getValue("source").jsonObject
        assertEquals("base64", source.getValue("type").jsonPrimitive.content)
        assertEquals("image/png", source.getValue("media_type").jsonPrimitive.content)
        assertEquals("iVBORw0KGgo=", source.getValue("data").jsonPrimitive.content)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `image ids are the entry uuid and the index`() = runBlocking {
        val stdin = File.createTempFile("tandem-stdin", ".ndjson").apply { deleteOnExit() }
        val launcher = fakeCli("cat '${File(fixtures, "user-image.ndjson").path}'; cat > '${stdin.path}'")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), launcher)
        session.start()
        session.sendUserMessage("x", listOf(ImageBlock("image/png", "AAAA"), ImageBlock("image/jpeg", "/9j/")))

        val entry = session.state.value.entries.filterIsInstance<ChatEntry.User>().single()
        withTimeout(10_000) { session.state.first { state -> !state.busy && state.entries.any { it is ChatEntry.Assistant } } }
        session.close()
        val uuid = Json.parseToJsonElement(stdin.readLines()[1]).jsonObject.getValue("uuid").jsonPrimitive.content
        assertEquals(uuid, entry.id)
        assertEquals(listOf("$uuid#0", "$uuid#1"), entry.images.map { it.id })
        assertEquals(listOf(UserImage("$uuid#0", "image/png", "AAAA"), UserImage("$uuid#1", "image/jpeg", "/9j/")), entry.images)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `blank text with an image makes an entry with no text, as on reload`() = runBlocking {
        val stdin = File.createTempFile("tandem-stdin", ".ndjson").apply { deleteOnExit() }
        val launcher = fakeCli("cat > '${stdin.path}'")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), launcher)
        session.start()
        session.sendUserMessage("  ", listOf(ImageBlock("image/png", "AAAA")))

        val entry = session.state.value.entries.filterIsInstance<ChatEntry.User>().single()
        assertEquals("", entry.text)
        assertEquals(listOf(UserImage("${entry.id}#0", "image/png", "AAAA")), entry.images)
        // The frame carries no text block, so the reloaded line's text is "" too.
        val isPrompt = { line: String -> "\"type\":\"user\"" in line }
        withTimeout(10_000) { while (stdin.readLines().none(isPrompt)) delay(20) }
        session.close()
        val frame = Json.parseToJsonElement(stdin.readLines().first(isPrompt)).jsonObject
        val content = frame.getValue("message").jsonObject.getValue("content").jsonArray
        assertEquals(listOf("image"), content.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `cancelling the owning scope leaves no process or child process behind`() = runBlocking {
        // Ignores stdin and spawns a grandchild: the worst case for cleanup.
        val launcher = fakeCli("sleep 600 & sleep 600")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), launcher)
        session.start()
        val process = withTimeout(5_000) {
            while ((session.process?.descendants()?.count() ?: 0L) < 2L) kotlinx.coroutines.delay(20)
            session.process!!
        }
        val handles = listOf(process.toHandle()) + process.descendants().toList()
        assertTrue(handles.all { it.isAlive })

        scope.coroutineContext.job.cancelAndJoin()

        withTimeout(10_000) { while (handles.any { it.isAlive }) kotlinx.coroutines.delay(20) }
        assertFalse(handles.any { it.isAlive })
    }

    @Test
    fun `a process that starts while the scope is being cancelled is still stopped`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Process>()
        // Starting a process cannot be interrupted, so a cancellation can arrive while it starts: here, from inside the launch.
        val launcher = ProcessLauncher { _, dir ->
            scope.cancel()
            ProcessBuilder("sh", "-c", "cat > /dev/null").directory(dir.toFile()).start().also { started.complete(it) }
        }
        ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), launcher).start()
        val process = withTimeout(5_000) { started.await() }
        try {
            scope.coroutineContext.job.join()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (process.isAlive && System.nanoTime() < deadline) delay(20)
            assertFalse(process.isAlive, "the process outlived its cancelled session")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `killNow stops the process tree synchronously`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), fakeCli("sleep 600 & sleep 600"))
        session.start()
        val process = withTimeout(5_000) {
            while ((session.process?.descendants()?.count() ?: 0L) < 2L) kotlinx.coroutines.delay(20)
            session.process!!
        }
        val handles = listOf(process.toHandle()) + process.descendants().toList()
        session.killNow()
        withTimeout(5_000) { while (handles.any { it.isAlive }) kotlinx.coroutines.delay(20) }
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `a missing binary ends the session with a visible error`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("/nonexistent/claude", tmp, sessionId = newSessionId))
        session.start().join()
        val notice = session.state.value.entries.last() as ChatEntry.Notice
        assertEquals(NoticeLevel.ERROR, notice.level)
        assertTrue(session.state.value.ended)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `a launcher that fails in an unexpected way ends the session with a visible error too`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // ProcessBuilder.start throws more than IOException: an argument it cannot pass, a security manager, a broken launcher.
        val launcher = ProcessLauncher { _, _ -> throw IllegalStateException("launcher broke") }
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), launcher)
        val final = withTimeout(10_000) {
            session.start()
            session.state.first { it.ended }
        }
        val notice = assertNotNull(final.entries.last() as? ChatEntry.Notice)
        assertEquals(NoticeLevel.ERROR, notice.level)
        assertTrue(notice.text.startsWith("Could not start `claude`: launcher broke"), notice.text)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `a crash is reported with the exit code and stderr`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), fakeCli("echo 'bad flag' >&2; exit 3"))
        session.start()
        val final = withTimeout(10_000) { session.state.first { it.ended } }
        val notice = assertNotNull(final.entries.last() as? ChatEntry.Notice)
        assertTrue(notice.text.contains("code 3") && notice.text.contains("bad flag"), notice.text)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `argv for a new session and for a resumed fork`() {
        val base = listOf(
            "claude", "--output-format", "stream-json", "--verbose", "--input-format", "stream-json",
            "--include-partial-messages", "--thinking-display", "summarized", "--permission-prompt-tool=stdio",
        )
        assertEquals(
            base + listOf(
                "--model", "opus", "--effort", "high", "--permission-mode", "plan",
                "--disallowedTools", "Edit,Write,MultiEdit,NotebookEdit", "--session-id", "s1",
            ),
            SessionConfig("claude", tmp, sessionId = "s1", model = "opus", effort = "high", permissionMode = PermissionMode.PLAN).argv(),
        )
        assertEquals(
            base + listOf("--disallowedTools", "Edit,Write,MultiEdit,NotebookEdit", "--resume", "s2", "--fork-session"),
            SessionConfig("claude", tmp, resumeSessionId = "s2", forkSession = true).argv(),
        )
        // A fork may bring its own id: the only case where both ids are passed.
        assertEquals(
            base + listOf("--disallowedTools", "Edit,Write,MultiEdit,NotebookEdit") +
                listOf("--resume", "s2", "--fork-session", "--session-id", "s3"),
            SessionConfig("claude", tmp, sessionId = "s3", resumeSessionId = "s2", forkSession = true).argv(),
        )
        assertFailsWith<IllegalArgumentException> { SessionConfig("claude", tmp) }
        // The CLI refuses --session-id with --resume unless --fork-session is given too.
        assertFailsWith<IllegalArgumentException> { SessionConfig("claude", tmp, sessionId = "a", resumeSessionId = "b") }
        // A fork without the session it forks would silently start a new session.
        assertFailsWith<IllegalArgumentException> { SessionConfig("claude", tmp, sessionId = "a", forkSession = true) }
    }

    @Test
    fun `added folders become --add-dir after --resume`() {
        val base = listOf(
            "claude", "--output-format", "stream-json", "--verbose", "--input-format", "stream-json",
            "--include-partial-messages", "--thinking-display", "summarized", "--permission-prompt-tool=stdio",
        )
        val disallowed = listOf("--disallowedTools", "Edit,Write,MultiEdit,NotebookEdit")
        // "/b c" stays one argv element: no shell splits it.
        assertEquals(
            base + disallowed + listOf("--resume", "s2", "--add-dir", "/a", "--add-dir", "/b c"),
            SessionConfig("claude", tmp, resumeSessionId = "s2", additionalDirectories = listOf("/a", "/b c")).argv(),
        )
        assertEquals(
            base + disallowed + listOf("--session-id", "s1", "--add-dir", "/a"),
            SessionConfig("claude", tmp, sessionId = "s1", additionalDirectories = listOf("/a")).argv(),
        )
        assertEquals(
            base + disallowed + listOf("--resume", "s2", "--fork-session", "--session-id", "s3", "--add-dir", "/a"),
            SessionConfig("claude", tmp, sessionId = "s3", resumeSessionId = "s2", forkSession = true, additionalDirectories = listOf("/a")).argv(),
        )
        assertTrue("--add-dir" !in SessionConfig("claude", tmp, resumeSessionId = "s2").argv())
        assertTrue("--add-dir" !in SessionConfig("claude", tmp, sessionId = "s1").argv())
    }

    // spec: a fork at a reply ends with --resume-session-at
    @Test
    fun `a fork at a reply ends with --resume-session-at`() {
        val base = listOf(
            "claude", "--output-format", "stream-json", "--verbose", "--input-format", "stream-json",
            "--include-partial-messages", "--thinking-display", "summarized", "--permission-prompt-tool=stdio",
        )
        val disallowed = listOf("--disallowedTools", "Edit,Write,MultiEdit,NotebookEdit")
        val fork = listOf("--resume", "A", "--fork-session", "--session-id", "B", "--resume-session-at", "X")
        assertEquals(
            base + disallowed + fork,
            SessionConfig("claude", tmp, sessionId = "B", resumeSessionId = "A", forkSession = true, resumeSessionAt = "X").argv(),
        )
        assertEquals(
            base + disallowed + fork + listOf("--add-dir", "/a"),
            SessionConfig(
                "claude", tmp, sessionId = "B", resumeSessionId = "A", forkSession = true, resumeSessionAt = "X",
                additionalDirectories = listOf("/a"),
            ).argv(),
        )
        // Without --fork-session the CLI would branch the original's own transcript.
        assertFailsWith<IllegalArgumentException> { SessionConfig("claude", tmp, resumeSessionId = "A", resumeSessionAt = "X") }
        for (bad in listOf("", " ", "-x")) {
            assertFailsWith<IllegalArgumentException>(bad) {
                SessionConfig("claude", tmp, sessionId = "B", resumeSessionId = "A", forkSession = true, resumeSessionAt = bad)
            }
        }
    }

    @Test
    fun `keep_alive and unknown lines advance lastFrameAt without a state emission`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val launcher = fakeCli("echo '{\"type\":\"keep_alive\"}'; sleep 0.2; echo '{\"type\":\"no_such_type\"}'; sleep 0.2")
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), launcher)
        // One log of both flows, in the order they changed: a frame time, or a new chat state.
        val log = Collections.synchronizedList(mutableListOf<Any>())
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { session.lastFrameAtMillis.collect { log.add(it) } }
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { session.state.collect { log.add(it) } }
        session.start()
        withTimeout(10_000) { session.state.first { it.ended } }

        val frames = synchronized(log) { log.toList() }
        val times = frames.filterIsInstance<Long>().filter { it != 0L }
        assertEquals(2, times.size, frames.toString())
        assertTrue(times[1] > times[0], times.toString())
        val between = frames.subList(frames.indexOf(times[0]) + 1, frames.indexOf(times[1]))
        assertEquals(emptyList(), between, "a keep_alive line changes no state")
        // The unknown line set times[1] before its dispatch: a state it caused would come after it. Only the end may.
        val after = frames.drop(frames.indexOf(times[1]) + 1).filterIsInstance<ChatState>()
        assertTrue(after.isNotEmpty() && after.all { it.ended }, "an unknown line changes no state: $after")
        assertEquals(1, frames.subList(0, frames.indexOf(times[0])).count { it is ChatState }, "only the initial state before")
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `the injected clock stamps the prompt, the turn and every line`() = runBlocking {
        // Waits for initialize and the prompt before it answers, so the turn has started when its lines come.
        val launcher = fakeCli("read a; read b; cat '${File(fixtures, "plain-reply.ndjson").path}'; cat > /dev/null")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), launcher, clock = { 1_000L })
        session.start()
        session.sendUserMessage("Reply with the single word: pong")
        val sent = session.state.value
        assertEquals(1_000L, sent.turn?.startedAtMillis)
        assertEquals(1_000L, sent.entries.filterIsInstance<ChatEntry.User>().single().sentAtMillis)

        withTimeout(10_000) { session.state.first { it.turnsEnded == 1 } }
        assertEquals(1_000L, session.lastFrameAtMillis.value)
        session.close()
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `the result of a start that fails is no system init, though it carries a session id`() = runBlocking {
        // What the CLI prints for `--resume` of a session it does not find, then it exits with code 1.
        val result = """{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"$newSessionId",""" +
            """"errors":["No conversation found with session ID: $newSessionId"]}"""
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, resumeSessionId = newSessionId), fakeCli("echo '$result'; exit 1"))
        session.start()
        val ended = withTimeout(10_000) { session.state.first { it.ended } }
        assertEquals(newSessionId, ended.sessionId)
        assertNull(session.reportedSessionId.value)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `reports the session id of the first system init, which comes with the first prompt, and shows host notices`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig(fakeClaude, tmp, sessionId = newSessionId), DefaultProcessLauncher())
        session.start()
        delay(300) // time for a wrong id to show: the fake sends no system/init before a prompt, as the CLI does
        assertNull(session.reportedSessionId.value)
        session.sendUserMessage("Reply with the single word: pong")
        assertEquals(newSessionId, withTimeout(10_000) { session.reportedSessionId.filterNotNull().first() })

        withTimeout(10_000) { session.state.first { s -> !s.busy && s.entries.any { it is ChatEntry.Assistant } } }
        session.notice(NoticeLevel.WARNING, "Resumed as a new session")
        val notice = session.state.value.entries.last() as ChatEntry.Notice
        assertEquals(NoticeLevel.WARNING, notice.level)
        assertEquals("Resumed as a new session", notice.text)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `a resumed session starts from the loaded history`() = runBlocking {
        val history = ChatState(entries = listOf(ChatEntry.User("u0", "earlier")), effort = "low")
        val launcher = fakeCli("cat '${File(fixtures, "plain-reply.ndjson").path}'; cat > /dev/null")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, resumeSessionId = "s1"), launcher, initialState = history)
        session.start()
        val final = withTimeout(10_000) { session.state.first { s -> !s.busy && s.entries.any { it is ChatEntry.Assistant } } }
        assertEquals("earlier", (final.entries.first() as ChatEntry.User).text)
        assertEquals("low", final.effort)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `picks in the config win over the loaded history and missing ones keep it`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val history = ChatState(requestedModel = "sonnet", permissionMode = PermissionMode.ACCEPT_EDITS, effort = "low")

        val picked = SessionConfig("claude", tmp, resumeSessionId = "s1", model = "opus", effort = "high", permissionMode = PermissionMode.PLAN)
        val overridden = ClaudeSession(scope, picked, fakeCli("true"), initialState = history).state.value
        assertEquals(Triple("opus", PermissionMode.PLAN, "high"), Triple(overridden.requestedModel, overridden.permissionMode, overridden.effort))

        val kept = ClaudeSession(scope, SessionConfig("claude", tmp, resumeSessionId = "s1"), fakeCli("true"), initialState = history).state.value
        assertEquals(Triple("sonnet", PermissionMode.ACCEPT_EDITS, "low"), Triple(kept.requestedModel, kept.permissionMode, kept.effort))
        scope.cancel()
    }

    @Test
    fun `the spawn's --permission-mode is the launch mode`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val auto = SessionConfig("claude", tmp, sessionId = newSessionId, permissionMode = PermissionMode.AUTO)
        assertEquals(PermissionMode.AUTO, ClaudeSession(scope, auto, fakeCli("true")).state.value.launchPermissionMode)
        val none = SessionConfig("claude", tmp, sessionId = newSessionId)
        val history = ChatState(launchPermissionMode = PermissionMode.PLAN)
        assertEquals(null, ClaudeSession(scope, none, fakeCli("true"), initialState = history).state.value.launchPermissionMode)
        scope.cancel()
    }

    @Test
    fun `a permission mode change shows before the CLI confirms it`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), fakeCli("true"))
        session.setPermissionMode(PermissionMode.PLAN)
        assertEquals(PermissionMode.PLAN, session.state.value.permissionMode)
        scope.cancel()
    }

    @Test
    fun `setEffort sends apply_flag_settings and records the previous level`() = runBlocking {
        val stdin = File.createTempFile("tandem-stdin", ".ndjson").apply { deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), fakeCli("cat > '${stdin.path}'"))
        session.start()
        session.setEffort("high")
        assertEquals("high", session.state.value.effort)
        assertEquals(EffortChange("high", null), session.state.value.effortChanges.values.single())

        val written = withTimeout(10_000) {
            var lines = stdin.readLines()
            while (lines.none { "apply_flag_settings" in it }) {
                delay(20)
                lines = stdin.readLines()
            }
            lines
        }
        val line = written.single { "apply_flag_settings" in it }
        assertTrue("\"subtype\":\"apply_flag_settings\"" in line, line)
        assertTrue("\"effortLevel\":\"high\"" in line, line)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `setModel sends set_model and records the model shown before under its request id`() = runBlocking {
        val stdin = File.createTempFile("tandem-stdin", ".ndjson").apply { deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), fakeCli("cat > '${stdin.path}'"))
        session.start()
        session.setModel("opus")
        // The request id is what lets a refusal put back the model shown before (Reducer, ModelChange).
        val (requestId, change) = session.state.value.modelChanges.entries.single()
        assertEquals(ModelChange("opus", null, previousPending = false, previousWarning = null), change)

        val written = withTimeout(10_000) {
            var lines = stdin.readLines()
            while (lines.none { "set_model" in it }) {
                delay(20)
                lines = stdin.readLines()
            }
            lines
        }
        val frame = Json.parseToJsonElement(written.single { "set_model" in it }).jsonObject
        assertEquals(requestId, frame["request_id"]!!.jsonPrimitive.content)
        val request = frame["request"]!!.jsonObject
        assertEquals("set_model", request["subtype"]!!.jsonPrimitive.content)
        assertEquals("opus", request["model"]!!.jsonPrimitive.content)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `rename asks the CLI to rename the session`() = runBlocking {
        val stdin = File.createTempFile("tandem-stdin", ".ndjson").apply { deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), fakeCli("cat > '${stdin.path}'"))
        session.start()
        session.rename("Alpha")

        val written = withTimeout(10_000) {
            var lines = stdin.readLines()
            while (lines.size < 2) {
                delay(20)
                lines = stdin.readLines()
            }
            lines
        }
        val request = Json.parseToJsonElement(written[1]).jsonObject["request"]!!.jsonObject
        assertEquals("rename_session", request["subtype"]!!.jsonPrimitive.content)
        assertEquals("Alpha", request["title"]!!.jsonPrimitive.content)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `the default launcher strips inherited session variables and passes the config dir through`() = runBlocking {
        val log = File.createTempFile("tandem-spawn", ".ndjson").apply { deleteOnExit() }
        val launcher = DefaultProcessLauncher {
            System.getenv() + mapOf(
                "CLAUDE_CODE_SESSION_ID" to "bogus", "NODE_OPTIONS" to "--inspect",
                "CLAUDE_CONFIG_DIR" to "/cfg", "TANDEM_FAKE_LOG" to log.path,
                "CLAUDE_CODE_OAUTH_TOKEN" to "secret-value-for-test",
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig(fakeClaude, tmp, sessionId = newSessionId), launcher)
        session.start()
        session.sendUserMessage("Reply with the single word: pong")
        withTimeout(10_000) { session.state.first { s -> !s.busy && s.entries.any { it is ChatEntry.Assistant } } }
        session.close()

        val spawn = Json.parseToJsonElement(log.readLines().first()).jsonObject
        assertEquals("__spawn__", spawn["type"]!!.jsonPrimitive.content)
        val env = spawn["env"]!!.jsonObject
        assertFalse("CLAUDE_CODE_SESSION_ID" in env)
        assertFalse("NODE_OPTIONS" in env)
        assertEquals("/cfg", env["CLAUDE_CONFIG_DIR"]!!.jsonPrimitive.content)
        assertEquals("sdk-tandem", env["CLAUDE_CODE_ENTRYPOINT"]!!.jsonPrimitive.content)
        // The fake lists the name of a secret that reaches it, never its value.
        assertEquals("<redacted>", env["CLAUDE_CODE_OAUTH_TOKEN"]!!.jsonPrimitive.content)
        assertFalse("secret-value-for-test" in log.readText())
        val argv = spawn["argv"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(newSessionId, argv[argv.indexOf("--session-id") + 1])
        scope.coroutineContext.job.cancelAndJoin()
    }

    /** A process's output that gives nothing until [cutOff], then fails every read as a pipe closed under it does. */
    private class CutOffOutput : InputStream() {
        private val cut = CountDownLatch(1)

        fun cutOff() = cut.countDown()

        override fun read(): Int {
            cut.await()
            throw IOException("Stream closed")
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = read()
    }

    /**
     * [real], whose output is [output] in place of its own. With [cutOnKill], ending it cuts that output off at once, as the JDK
     * closes the pipe of a process that has exited while a read waits on it.
     */
    private class CutOffProcess(private val real: Process, val output: CutOffOutput, private val cutOnKill: Boolean) : Process() {
        override fun getOutputStream() = real.outputStream
        override fun getInputStream(): InputStream = output
        override fun getErrorStream(): InputStream = real.errorStream
        override fun waitFor(): Int = real.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = real.waitFor(timeout, unit)
        override fun exitValue(): Int = real.exitValue()
        override fun isAlive(): Boolean = real.isAlive
        override fun pid(): Long = real.pid()
        override fun toHandle(): ProcessHandle = real.toHandle()
        override fun descendants() = real.descendants()

        override fun destroy() {
            real.destroy()
            if (cutOnKill) output.cutOff()
        }

        override fun destroyForcibly(): Process {
            real.destroyForcibly()
            if (cutOnKill) output.cutOff()
            return this
        }
    }

    /** A scope whose uncaught exceptions are kept in [uncaught], and a session in it whose process is [process]. */
    private class CutOffSession(process: Process, tmp: Path, sessionId: String) {
        val uncaught: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.add(e) })
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = sessionId), ProcessLauncher { _, _ -> process })
    }

    @Test
    fun `a read cut off by killing the process ends the session quietly`() = runBlocking {
        val output = CutOffOutput()
        val process = CutOffProcess(ProcessBuilder("sh", "-c", "cat > /dev/null").start(), output, cutOnKill = true)
        val run = CutOffSession(process, tmp, newSessionId)
        run.session.start()
        withTimeout(5_000) { while (run.session.process == null) delay(20) }

        run.session.killNow() // as the JVM shutdown hook does: the read waiting on the output fails
        withTimeout(10_000) { while (run.uncaught.isEmpty() && !run.session.state.value.ended) delay(20) }
        assertEquals(emptyList(), run.uncaught.map { it.stackTraceToString().take(1500) }, "the process was killed: its output ending is no failure")
        // The session ends as for any process that ends: with its exit code in the chat.
        val notice = assertNotNull(run.session.state.value.entries.last() as? ChatEntry.Notice)
        assertEquals("The claude CLI exited with code 137.", notice.text)
        run.scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `a process killed at once during a turn ends it as interrupted, not failed`() = runBlocking {
        // As the JVM shutdown hook kills it when the IDE exits: the turn in progress is not one that failed, but it did end.
        val output = CutOffOutput()
        val process = CutOffProcess(ProcessBuilder("sh", "-c", "cat > /dev/null").start(), output, cutOnKill = true)
        val run = CutOffSession(process, tmp, newSessionId)
        run.session.start()
        withTimeout(5_000) { while (run.session.process == null) delay(20) }
        run.session.sendUserMessage("Count to a hundred")
        withTimeout(5_000) { run.session.state.first { it.busy } }

        run.session.killNow()
        val ended = withTimeout(10_000) { run.session.state.first { it.ended } }
        assertEquals(1, ended.turnsEnded)
        assertEquals(TurnEnd.INTERRUPTED, ended.lastTurnEnd)
        assertEquals(TurnEnd.INTERRUPTED, ended.entries.filterIsInstance<ChatEntry.TurnEnded>().single().end)
        assertNull(ended.turn)
        run.scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `a read that fails while the process runs on is not swallowed`() = runBlocking {
        val output = CutOffOutput()
        val real = ProcessBuilder("sh", "-c", "cat > /dev/null").start()
        val run = CutOffSession(CutOffProcess(real, output, cutOnKill = false), tmp, newSessionId)
        run.session.start()
        withTimeout(5_000) { while (run.session.process == null) delay(20) }

        output.cutOff() // nobody stopped the process, which still runs
        withTimeout(10_000) { while (run.uncaught.isEmpty()) delay(20) }
        assertEquals("Stream closed", (run.uncaught.single() as IOException).message)
        // The failure ends the session, and with it the process.
        withTimeout(10_000) { while (real.isAlive) delay(20) }
        run.scope.coroutineContext.job.cancelAndJoin()
    }

    /** An output that nothing writes to, as a quiet CLI's: a read waits on it, interruptibly, and [reading] says when one does. */
    private class QuietOutput : java.io.PipedInputStream() {
        val writer = java.io.PipedOutputStream(this)
        val reading = CountDownLatch(1)

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reading.countDown()
            return super.read(b, off, len)
        }
    }

    /** [real], whose output is [output] in place of its own. */
    private class QuietProcess(private val real: Process, private val output: InputStream) : Process() {
        override fun getOutputStream() = real.outputStream
        override fun getInputStream(): InputStream = output
        override fun getErrorStream(): InputStream = real.errorStream
        override fun waitFor(): Int = real.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = real.waitFor(timeout, unit)
        override fun exitValue(): Int = real.exitValue()
        override fun isAlive(): Boolean = real.isAlive
        override fun pid(): Long = real.pid()
        override fun toHandle(): ProcessHandle = real.toHandle()
        override fun descendants() = real.descendants()
        override fun destroy() = real.destroy()
        override fun destroyForcibly(): Process = real.destroyForcibly()
    }

    @Test
    fun `cancelling a session while a read waits on an interruptible output is no failure`() = runBlocking {
        // Cancelling interrupts the reading thread; an interruptible stream then throws InterruptedIOException, an IOException.
        val output = QuietOutput()
        val real = ProcessBuilder("sh", "-c", "cat > /dev/null").start()
        val uncaught: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.add(e) })
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), ProcessLauncher { _, _ -> QuietProcess(real, output) })
        try {
            session.start()
            withTimeout(5_000) { while (!output.reading.await(20, TimeUnit.MILLISECONDS)) delay(1) } // the read waits
            scope.coroutineContext.job.cancelAndJoin()
            assertEquals(emptyList(), uncaught.map { it.stackTraceToString().take(1500) }, "a cancelled read is no failure")
        } finally {
            real.destroyForcibly()
            output.writer.close()
        }
    }

    /**
     * A session on a [ScriptedProcess]: the test writes the CLI's lines with [write], and every frame the host writes lands in
     * [stdin]. Answers go through the session's own request path (spec 9.2). With [child], the real process first starts that shell
     * command in the background and then becomes the `cat` itself: a process the CLI started, which outlives it.
     */
    private class ScriptedSession(
        tmp: Path,
        sessionId: String,
        mcpMillisPerSecond: Long = 1_000L,
        quietWaitMillis: Long = 5_000L,
        child: String? = null,
        hostTools: HostTools? = null,
    ) {
        val stdin: File = File.createTempFile("tandem-stdin", ".ndjson").apply { deleteOnExit() }
        val process = ScriptedProcess(ProcessBuilder("sh", "-c", (child?.let { "$it & exec " } ?: "") + "cat > '${stdin.path}'").start())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(
            scope,
            SessionConfig("claude", tmp, sessionId = sessionId),
            ProcessLauncher { _, _ -> process },
            mcpMillisPerSecond = mcpMillisPerSecond,
            quietWaitMillis = quietWaitMillis,
            hostTools = hostTools,
            version = "0.4.0",
        )

        fun write(line: String) = process.script.println(line)

        /**
         * The CLI dies by itself, as in a crash or when something outside Tandem kills it. Unlike [Process.destroyForcibly], which
         * also closes Tandem's ends of its pipes, nothing else changes: the pipes stay as they are, and its scripted output ends.
         */
        fun die() {
            process.toHandle().destroyForcibly()
            process.script.close()
        }

        /** A success answer to [requestId]; [payload] is its `response` object, none for a bare success (`mcp-status:19`). */
        fun success(requestId: String, payload: String? = null) {
            val response = payload?.let { ""","response":$it""" } ?: ""
            write("""{"type":"control_response","response":{"subtype":"success","request_id":"$requestId"$response}}""")
        }

        /** An error answer to [requestId], in the envelope of docs/protocol.md 4.1. */
        fun error(requestId: String, message: String) =
            write("""{"type":"control_response","response":{"subtype":"error","request_id":"$requestId","error":"$message"}}""")

        /** The frames the host wrote that contain [part], once there are [count]. */
        suspend fun written(part: String, count: Int = 1): List<String> = withTimeout(10_000) {
            var lines = stdin.readLines().filter { part in it }
            while (lines.size < count) {
                delay(20)
                lines = stdin.readLines().filter { part in it }
            }
            lines
        }

        /** The host's [nth] `control_request` of [subtype], once it is written. */
        suspend fun requestOf(subtype: String, nth: Int = 1): JsonObject =
            Json.parseToJsonElement(written("\"subtype\":\"$subtype\"", nth)[nth - 1]).jsonObject

        /** The request id of the host's [nth] `control_request` of [subtype], once it is written. */
        suspend fun requestIdOf(subtype: String, nth: Int = 1): String = requestOf(subtype, nth).getValue("request_id").jsonPrimitive.content

        /**
         * The chat once every line written so far has been read: an answer nobody waits for goes last, and its notice says the reader
         * got to it. Its own notice is left out.
         */
        suspend fun settled(): ChatState {
            error("sentinel", "sentinel")
            val state = withTimeout(10_000) { session.state.first { s -> s.entries.any { it is ChatEntry.Notice && it.text == SENTINEL } } }
            return state.copy(entries = state.entries.filterNot { it is ChatEntry.Notice && it.text == SENTINEL }, entryIndex = emptyMap())
        }

        suspend fun close() = scope.coroutineContext.job.cancelAndJoin()

        private companion object {
            const val SENTINEL = "The CLI rejected a request: sentinel"
        }
    }

    private fun ChatState.notices(level: NoticeLevel): List<String> =
        entries.filterIsInstance<ChatEntry.Notice>().filter { it.level == level }.map { it.text }

    private fun rejected(error: String): String = "The CLI rejected a request: $error"

    @Test
    fun `a request completes with the response that carries its id and the reducer never sees it`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        val sent = CompletableDeferred<String>()
        val reply = async { run.session.request(10_000) { sent.complete(it); HostMessages.mcpStatus(it) } }
        val requestId = withTimeout(10_000) { sent.await() }
        run.written("\"request_id\":\"$requestId\"")
        run.error("someone-else", "not this one") // another id: the reducer's
        run.error(requestId, "Server not found: nosuch")

        val answered = assertIs<Reply.Answered>(withTimeout(10_000) { reply.await() })
        assertEquals(requestId, answered.response.requestId)
        assertEquals("Server not found: nosuch", answered.response.error)
        assertEquals(listOf(rejected("not this one")), run.settled().notices(NoticeLevel.ERROR))
        run.close()
    }

    @Test
    fun `an unregistered error response still reaches the chat`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.error("tandem-99", "Unknown subtype")
        assertEquals(listOf(rejected("Unknown subtype")), run.settled().notices(NoticeLevel.ERROR))
        run.close()
    }

    @Test
    fun `a request times out and a late answer goes to the reducer`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        var requestId = ""
        val reply = withTimeout(10_000) { run.session.request(200) { requestId = it; HostMessages.mcpStatus(it) } }
        assertEquals(Reply.TimedOut, reply)

        run.error(requestId, "late")
        assertEquals(listOf(rejected("late")), run.settled().notices(NoticeLevel.ERROR))
        run.close()
    }

    @Test
    fun `an answer that races the timeout is returned, not lost`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        val timeoutMillis = 60L
        // Each answer comes at the deadline, a few milliseconds either side: either the caller gets it or the reducer does.
        val outcomes = (0 until 30).map { i ->
            val sent = CompletableDeferred<String>()
            val writer = launch(Dispatchers.IO) {
                val requestId = sent.await()
                delay(timeoutMillis + i % 5 - 2)
                run.error(requestId, "race-$i")
            }
            val reply = withTimeout(10_000) { run.session.request(timeoutMillis) { sent.complete(it); HostMessages.mcpStatus(it) } }
            writer.join()
            i to reply
        }

        val reduced = run.settled().notices(NoticeLevel.ERROR)
        for ((i, reply) in outcomes) {
            when (reply) {
                is Reply.Answered -> {
                    assertEquals("race-$i", reply.response.error)
                    assertFalse(rejected("race-$i") in reduced, "race-$i went to both the caller and the reducer")
                }
                Reply.TimedOut -> assertTrue(rejected("race-$i") in reduced, "race-$i was lost")
                Reply.Ended -> fail("the session did not end")
            }
        }
        assertEquals(outcomes.count { it.second == Reply.TimedOut }, reduced.size, reduced.toString())
        run.close()
    }

    /**
     * Runs [test] with a caller thread that can be held: [hold] returns once that thread's one worker is busy, so a coroutine on it
     * that waits stays suspended, its resumption queued, until [hold]'s release runs. The executor is not a scheduled one: a timeout
     * on its dispatcher fires on another thread.
     */
    private fun <T> withHeldThread(test: suspend CoroutineScope.(thread: CoroutineDispatcher, hold: () -> (() -> Unit)) -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try {
            return runBlocking {
                test(executor.asCoroutineDispatcher()) {
                    val held = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    executor.execute { held.countDown(); release.await(10, TimeUnit.SECONDS) }
                    assertTrue(held.await(10, TimeUnit.SECONDS), "the caller thread was never free")
                    release::countDown
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `an answer that comes after the timeout fired, before the caller resumed, is the caller's`() = withHeldThread { thread, hold ->
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        val sent = CompletableDeferred<String>()
        val reply = async(thread) { run.session.request(300) { sent.complete(it); HostMessages.mcpStatus(it) } }
        val requestId = withTimeout(10_000) { sent.await() }
        val release = hold() // the caller now waits for its answer and cannot resume
        // The timeout cancels the request's withTimeoutOrNull block, a child of the caller, while the caller cannot run.
        withTimeout(10_000) { while (reply.children.none { it.isCancelled }) delay(5) }
        run.error(requestId, "late but first")
        assertEquals(emptyList(), run.settled().notices(NoticeLevel.ERROR), "the reader handed the answer to the waiter")
        release()

        val answered = assertIs<Reply.Answered>(withTimeout(10_000) { reply.await() })
        assertEquals("late but first", answered.response.error)
        assertEquals(emptyList(), run.settled().notices(NoticeLevel.ERROR), "the caller's answer went to the reducer too")
        run.close()
    }

    @Test
    fun `an answer to a caller cancelled before it resumed still reaches the chat`() = withHeldThread { thread, hold ->
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        val sent = CompletableDeferred<String>()
        val caller = launch(thread) { run.session.request(10_000) { sent.complete(it); HostMessages.mcpReconnect(it, "broken") } }
        val requestId = withTimeout(10_000) { sent.await() }
        val release = hold() // the caller now waits for its answer and cannot resume
        run.error(requestId, "Connection closed")
        assertEquals(emptyList(), run.settled().notices(NoticeLevel.ERROR), "the reader handed the answer to the waiter")
        caller.cancel() // a panel closed just as the answer came: the queued resumption throws instead (prompt cancellation)
        release()
        withTimeout(10_000) { caller.join() }

        assertTrue(caller.isCancelled)
        assertEquals(listOf(rejected("Connection closed")), run.settled().notices(NoticeLevel.ERROR))
        run.close()
    }

    @Test
    fun `the mcp requests wait 15 s or 30 s and say which`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId, mcpMillisPerSecond = 10) // a second is 10 ms here; nothing answers
        run.session.start()

        /** [request]'s result and how long it took, in milliseconds. */
        suspend fun timed(request: suspend () -> McpResult<*>): Pair<McpResult<*>, Long> {
            val started = System.nanoTime()
            val result = withTimeout(10_000) { request() }
            return result to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        }

        val waits = listOf(
            15 to timed { run.session.mcpStatus() },
            15 to timed { run.session.mcpToggle("echo", enabled = false) },
            30 to timed { run.session.mcpReconnect("broken") },
            30 to timed { run.session.mcpAuthenticate("authy") },
        )
        for ((seconds, outcome) in waits) {
            val (result, millis) = outcome
            assertEquals(McpResult.Failed(McpError.TimedOut(seconds)), result)
            assertTrue(millis >= seconds * 10L, "waited $millis ms, not $seconds of its seconds")
        }
        assertEquals(McpState(), run.settled().mcp)
        run.close()
    }

    @Test
    fun `a cancelled request's error answer still reaches the chat`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        val sent = CompletableDeferred<String>()
        val caller = launch { run.session.request(10_000) { sent.complete(it); HostMessages.mcpReconnect(it, "broken") } }
        val requestId = withTimeout(10_000) { sent.await() }
        caller.cancelAndJoin() // a panel closed mid-reconnect

        run.error(requestId, "Connection closed")
        assertEquals(listOf(rejected("Connection closed")), run.settled().notices(NoticeLevel.ERROR))
        run.close()
    }

    @Test
    fun `a process exit fails every waiting request with Ended`() = runBlocking {
        // Reads initialize and both requests, then exits by itself: nothing closes the bare session.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), fakeCli("read a; read b; read c"))
        session.start()
        val replies = List(2) { async { session.request(10_000) { HostMessages.mcpStatus(it) } } }

        assertEquals(listOf<Reply>(Reply.Ended, Reply.Ended), withTimeout(5_000) { replies.awaitAll() })
        assertTrue(session.state.value.ended)
        assertTrue(session.job.isActive, "the session was not closed: its process ended")
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `close while a request waits answers Ended`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), fakeCli("cat > /dev/null"))
        session.start()
        val sent = CompletableDeferred<Unit>()
        val reply = async { session.request(10_000) { sent.complete(Unit); HostMessages.mcpStatus(it) } }
        withTimeout(10_000) { sent.await() }

        session.close()
        assertEquals(Reply.Ended, withTimeout(5_000) { reply.await() })
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `a failed launch answers Ended`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val asked = CountDownLatch(1)
        // The launch fails only once the request waits, so the end has to answer it.
        val launcher = ProcessLauncher { _, _ ->
            asked.await(10, TimeUnit.SECONDS)
            throw IOException("No such file or directory")
        }
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), launcher)
        session.start()

        val reply = withTimeout(10_000) { session.request(10_000) { asked.countDown(); HostMessages.mcpStatus(it) } }
        assertEquals(Reply.Ended, reply)
        assertTrue(session.state.value.ended)
        scope.coroutineContext.job.cancelAndJoin()
    }

    /** The processes [ScriptedSession.process] started, once there is one: a test that kills its process kills these in the end. */
    private suspend fun ScriptedSession.children(): List<ProcessHandle> = withTimeout(5_000) {
        while (process.descendants().count() == 0L) delay(20)
        process.descendants().toList()
    }

    /**
     * Returns once the session's stderr reader waits in the pipe's read itself. A read that does not wait yet when the process exits
     * finds the pipe drained and closed by the JDK; one that waits holds it until the last process that can write to it is gone.
     */
    private suspend fun ScriptedSession.stderrReadWaits() = withTimeout(5_000) {
        val inRead = { frame: StackTraceElement? -> frame?.className == "java.io.FileInputStream" && frame.methodName == "readBytes" }
        while (!inRead(process.stderrReader?.stackTrace?.firstOrNull())) delay(20)
    }

    @Test
    fun `a child that keeps the CLI's stderr open holds back neither the end nor close`() = runBlocking {
        // A tool's subprocess or an MCP server the CLI started can outlive it with its stderr: a read of that pipe never ends.
        val run = ScriptedSession(tmp, newSessionId, child = "sleep 600 >&2")
        val children = run.children()
        try {
            run.session.start()
            val sent = CompletableDeferred<Unit>()
            val waiting = async { run.session.request(60_000) { sent.complete(Unit); HostMessages.mcpStatus(it) } }
            withTimeout(10_000) { sent.await() }
            run.stderrReadWaits()

            run.die()
            val ended = withTimeout(5_000) { run.session.state.first { it.ended } }
            assertEquals("The claude CLI exited with code 137.", (ended.entries.last() as ChatEntry.Notice).text)
            assertEquals(Reply.Ended, withTimeout(1_000) { waiting.await() })
            assertEquals(Reply.Ended, withTimeout(1_000) { run.session.request(60_000) { HostMessages.mcpStatus(it) } })
            assertTrue(children.all { it.isAlive }, "the child still holds the stderr")
            withTimeout(5_000) { run.close() }
        } finally {
            children.forEach { it.destroyForcibly() }
        }
    }

    @Test
    fun `the CLI's stdin is closed once it has exited, ahead of the end`() = runBlocking {
        // The JDK closes it only once the waiting stderr read has returned, which this child holds back until its stdin ends. What the
        // child writes to stderr then is in the exit notice.
        val run = ScriptedSession(tmp, newSessionId, child = "exec 3<&0; { cat <&3 > /dev/null; echo 'stdin closed' >&2; }")
        val children = run.children()
        try {
            run.session.start()
            run.stderrReadWaits()
            run.die()
            val ended = withTimeout(5_000) { run.session.state.first { it.ended } }
            assertEquals("The claude CLI exited with code 137.\nstdin closed", (ended.entries.last() as ChatEntry.Notice).text)
            run.close()
        } finally {
            children.forEach { it.destroyForcibly() }
        }
    }

    @Test
    fun `an output that fails as the process is destroyed ends the session, not fails it`() = runBlocking {
        // destroyForcibly closes the pipes at once, while isAlive turns false only once the JDK has seen the exit: a read that fails
        // in between is the end of the output. Here the stderr fails a moment ahead of the kill, while the process still runs.
        val process = ScriptedProcess(ProcessBuilder("sh", "-c", "exec cat > /dev/null").start())
        val run = CutOffSession(process, tmp, newSessionId)
        try {
            process.errorStream.close()
            run.session.start()
            withTimeout(5_000) { while (process.stderrFailure == null) delay(5) }
            delay(100) // the session has taken the failure in by now, well within the grace it gives the process to end
            process.destroyForcibly()
            val ended = withTimeout(5_000) { run.session.state.first { it.ended } }
            assertEquals(emptyList(), run.uncaught.map { it.stackTraceToString().take(1500) }, "the output ended with the process")
            assertEquals("The claude CLI exited with code 137.", (ended.entries.last() as ChatEntry.Notice).text)
        } finally {
            process.destroyForcibly()
            run.scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `a request after the end answers Ended at once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, sessionId = newSessionId), fakeCli("exit 0"))
        session.start()
        withTimeout(10_000) { session.state.first { it.ended } }
        var built = 0

        // The process is gone, the session still open: nothing is written and nothing waits.
        assertEquals(Reply.Ended, withTimeout(1_000) { session.request(10_000) { built++; HostMessages.mcpStatus(it) } })
        session.close()
        assertEquals(Reply.Ended, withTimeout(1_000) { session.request(10_000) { built++; HostMessages.mcpStatus(it) } })
        assertEquals(0, built, "a request after the end builds no frame")
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `an mcp_status success without mcpServers is Rejected`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        val reply = async { run.session.mcpStatus() }
        // Synthetic: a success whose payload has no mcpServers array.
        run.success(run.requestIdOf("mcp_status"), """{"servers":[]}""")

        assertEquals(McpResult.Failed(McpError.Rejected("the CLI's answer listed no servers")), withTimeout(10_000) { reply.await() })
        assertEquals(McpState(), run.settled().mcp)
        run.close()
    }

    @Test
    fun `an mcp_authenticate bare success is Ok without a url`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        val reply = async { run.session.mcpAuthenticate("echo") }
        val frame = Json.parseToJsonElement(run.written("\"subtype\":\"mcp_authenticate\"").single()).jsonObject
        assertEquals("echo", frame.getValue("request").jsonObject.getValue("serverName").jsonPrimitive.content)
        run.success(frame.getValue("request_id").jsonPrimitive.content) // the bare success of mcp-status:19

        assertEquals(McpResult.Ok(McpAuthStart(authUrl = null)), withTimeout(10_000) { reply.await() })
        run.close()
    }

    @Test
    fun `the typed mcp requests send the probe frames and map their answers`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        /** Line [n] of the mcp-status fixture, answering [requestId] in place of its recorded id. */
        fun recorded(n: Int, requestId: String): String = Fixtures.lines("mcp-status")[n - 1].replace(Regex("<request_id-\\d+>"), requestId)

        /** The payload of a recorded answer line. */
        fun payload(line: String): JsonObject? = (Ndjson.decode(line) as CliMessage.ControlResponse).payload

        val status = async { run.session.mcpStatus() }
        val listed = recorded(18, run.requestIdOf("mcp_status"))
        run.write(listed)
        val servers = assertNotNull(ControlPayloads.mcpStatus(payload(listed))).servers
        assertEquals(McpResult.Ok(servers), withTimeout(10_000) { status.await() })
        assertEquals(listOf("echo", "broken", "authy"), servers.map { it.name })
        assertEquals(McpState(servers, detailed = true), run.session.state.value.mcp)

        val toggle = async { run.session.mcpToggle("echo", enabled = false) }
        val toggleFrame = run.requestOf("mcp_toggle")
        assertEquals(Json.parseToJsonElement("""{"subtype":"mcp_toggle","serverName":"echo","enabled":false}"""), toggleFrame.getValue("request"))
        run.success(toggleFrame.getValue("request_id").jsonPrimitive.content)
        assertEquals(McpResult.Ok(Unit), withTimeout(10_000) { toggle.await() })

        val reconnect = async { run.session.mcpReconnect("broken") }
        run.write(recorded(36, run.requestIdOf("mcp_reconnect")))
        assertEquals(McpResult.Failed(McpError.Rejected("Connection closed")), withTimeout(10_000) { reconnect.await() })

        // Synthetic: an error answer without its error text.
        val textless = async { run.session.mcpReconnect("broken") }
        run.write("""{"type":"control_response","response":{"subtype":"error","request_id":"${run.requestIdOf("mcp_reconnect", nth = 2)}"}}""")
        assertEquals(McpResult.Failed(McpError.Rejected("unknown error")), withTimeout(10_000) { textless.await() })

        val signIn = async { run.session.mcpAuthenticate("authy") }
        val link = recorded(40, run.requestIdOf("mcp_authenticate"))
        run.write(link)
        val start = assertNotNull(ControlPayloads.mcpAuthenticate(payload(link)))
        assertEquals(McpResult.Ok(start), withTimeout(10_000) { signIn.await() })
        assertTrue(start.authUrl!!.startsWith("http://127.0.0.1:33041/authorize?"), start.authUrl)

        // A link Tandem would not open is never handed on.
        val local = async { run.session.mcpAuthenticate("authy") }
        run.success(run.requestIdOf("mcp_authenticate", nth = 2), """{"authUrl":"file:///etc/passwd"}""")
        assertEquals(
            McpResult.Failed(McpError.Rejected("the sign-in link is not an http(s) URL", linkFailed = true)),
            withTimeout(10_000) { local.await() },
        )

        assertEquals(emptyList(), run.settled().notices(NoticeLevel.ERROR), "every answer was its caller's")
        run.close()
        assertEquals(McpResult.Failed(McpError.Ended), run.session.mcpToggle("echo", enabled = true))
    }

    @Test
    fun `an elicitation request keeps the Unsupported request answer and adds a warning notice`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        // Not recorded: an elicitation request as sdk.d.ts 3976-3999 types it (URL mode), inline. No fixture file: hard rule 6
        // keeps synthetic-long-reply the only unrecorded fixture.
        run.write(
            """{"type":"control_request","request_id":"elicit-1","request":{"subtype":"elicitation","mcp_server_name":"authy",""" +
                """"message":"Sign in to authy","mode":"url","url":"https://example.test/authorize","elicitation_id":"el-1"}}""",
        )

        val answer = Json.parseToJsonElement(run.written("\"request_id\":\"elicit-1\"").single())
        val unsupported = """{"type":"control_response","response":{"subtype":"error","request_id":"elicit-1","error":"Unsupported request: elicitation"}}"""
        assertEquals(Json.parseToJsonElement(unsupported), answer)
        val state = run.settled()
        assertEquals(listOf("An MCP server asked for input Tandem cannot show yet."), state.notices(NoticeLevel.WARNING))
        assertEquals(emptyList(), state.notices(NoticeLevel.ERROR))
        run.close()
    }

    private fun assertNull(value: Any?) = kotlin.test.assertNull(value)

    /** The `user` frames the host wrote so far, parsed. */
    private fun ScriptedSession.userFrames(): List<JsonObject> =
        stdin.readLines().filter { "\"type\":\"user\"" in it }.map { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.content(): String = this["message"]!!.jsonObject["content"]!!.jsonPrimitive.content

    /** Lines [from]..[to] (1-based) of fixture [name], with [edit] applied to each as a JSON object. */
    private fun lines(name: String, from: Int, to: Int, edit: (Int, MutableMap<String, JsonElement>) -> Unit = { _, _ -> }): List<String> =
        (from..to).map { n ->
            val fields = Json.parseToJsonElement(Fixtures.lines(name)[n - 1]).jsonObject.toMutableMap()
            edit(n, fields)
            JsonObject(fields).toString()
        }

    @Test
    fun `the output frame waits for the quiet result`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.session.sendShellEscape("shell-1", "echo hi", "hi\n", respond = true)
        run.written("\"type\":\"user\"")
        delay(300)
        val first = run.userFrames().single()
        assertEquals("<bash-input>echo hi</bash-input>", first.content())
        assertEquals(false, first["shouldQuery"]!!.jsonPrimitive.boolean)
        val uuid = first["uuid"]!!.jsonPrimitive.content
        assertEquals(setOf(uuid), run.session.state.value.quietSends)
        assertTrue(run.session.state.value.busy)

        lines("bash-mode-respond", 3, 7) { n, fields ->
            if (n == 6) fields["user_message_uuids"] = Json.parseToJsonElement("[\"$uuid\"]")
        }.forEach(run::write)
        val frames = withTimeout(2_000) {
            while (run.userFrames().size < 2) delay(20)
            run.userFrames()
        }
        val output = frames[1]
        assertEquals("<bash-stdout>hi\n</bash-stdout><bash-stderr></bash-stderr>", output.content())
        assertFalse("shouldQuery" in output)
        assertEquals(true, output["client_composed"]!!.jsonPrimitive.boolean)
        val state = run.session.state.value
        assertTrue(state.busy, "the reply's turn is still open")
        assertTrue(state.quietSends.isEmpty())
        assertTrue(state.entries.none { it is ChatEntry.TurnEnded })
        run.close()
    }

    @Test
    fun `the output frame goes out after the wait without a quiet result`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId, quietWaitMillis = 200)
        run.session.start()
        run.session.sendShellEscape("shell-1", "echo hi", "hi\n", respond = true)
        val frames = withTimeout(2_000) {
            while (run.userFrames().size < 2) delay(20)
            run.userFrames()
        }
        assertEquals("<bash-stdout>hi\n</bash-stdout><bash-stderr></bash-stderr>", frames[1].content())
        run.close()
    }

    @Test
    fun `interrupt drops a pending output frame`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.session.sendShellEscape("shell-1", "echo hi", "hi\n", respond = true)
        // The initialize request went out at the start: none may follow it.
        val requests = { run.stdin.readLines().count { "\"type\":\"control_request\"" in it } }
        run.written("\"type\":\"user\"")
        val before = requests()
        run.session.interrupt()
        delay(1_000)
        assertEquals(1, run.userFrames().size)
        assertEquals(before, requests(), "no control_request after the interrupt")
        assertTrue(run.stdin.readLines().none { "\"interrupt\"" in it }, "no model call runs: no interrupt request")
        val state = run.session.state.value
        assertFalse(state.busy)
        assertNull(state.turn)
        assertEquals("Stopped.", (state.entries.last() as ChatEntry.Notice).text)
        assertTrue(state.entries.none { it is ChatEntry.TurnEnded })
        run.close()
    }

    @Test
    fun `Ended drops a pending output frame`() = runBlocking {
        // A wait far longer than the test: only the drop on Ended can clear the pending frame, never the fallback.
        val run = ScriptedSession(tmp, newSessionId, quietWaitMillis = 60_000)
        run.session.start()
        run.session.sendShellEscape("shell-1", "echo hi", "hi\n", respond = true)
        run.written("\"type\":\"user\"")
        run.process.destroyForcibly()
        val ended = withTimeout(10_000) { run.session.state.first { it.ended } }
        // A guard only: the destroyed process wrote the file, so a frame sent now could not land in it whatever happened.
        assertEquals(1, run.userFrames().size)
        val end = ended.entries.filterIsInstance<ChatEntry.TurnEnded>().single()
        assertEquals(TurnEnd.FAILED, end.end)
        // The check that the frame was dropped: an output frame still pending would make interrupt() drop it, with a "Stopped." notice
        // dispatched before interrupt() returns.
        val stopped = { run.session.state.value.entries.count { it is ChatEntry.Notice && it.text == "Stopped." } }
        val before = stopped()
        run.session.interrupt()
        assertEquals(before, stopped(), "nothing was pending")
        run.close()
    }

    @Test
    fun `Ended drops a held shell escape and says its output was not sent`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.session.sendUserMessage("x")
        run.session.sendShellEscape("shell-1", "echo hi\necho more", "hi\nmore\n", respond = true)
        run.written("\"type\":\"user\"")
        run.process.destroyForcibly()
        val text = "The output of ! echo hi was not sent: the CLI ended first."
        // The first ended chat has the notice already: it goes in with the end, so the handle never takes an ended chat without it.
        val state = withTimeout(10_000) { run.session.state.first { it.ended } }
        assertTrue(state.entries.any { it is ChatEntry.Notice && it.text == text }, "the notice is in the same step as the end")
        assertEquals(1, run.userFrames().size, "only the prompt")
        // After the prompt's failed turn and the exit's own notice, at the end of the chat.
        val notice = state.entries.last() as ChatEntry.Notice
        assertEquals(text, notice.text)
        assertEquals(NoticeLevel.WARNING, notice.level)
        assertEquals(TurnEnd.FAILED, state.entries.filterIsInstance<ChatEntry.TurnEnded>().single().end)
        assertTrue(state.entries.indexOfFirst { it is ChatEntry.TurnEnded } < state.entries.lastIndex - 1)
        run.close()
    }

    @Test
    fun `letting the process go says a pending output frame was not sent`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId, quietWaitMillis = 200)
        run.session.start()
        run.session.sendShellEscape("shell-1", "echo hi", "hi\n", respond = true)
        run.written("\"type\":\"user\"")
        // The session's Stop: the handle lets the process go, then settles the chat, which ends the open turn without a word.
        run.session.letGoShellFrames()
        val notice = run.session.state.value.entries.last() as ChatEntry.Notice
        assertEquals("The output of ! echo hi was not sent: the CLI ended first.", notice.text)
        assertEquals(NoticeLevel.WARNING, notice.level)
        delay(500) // past the wait: a frame still pending would go out now
        assertEquals(1, run.userFrames().size, "only the command frame")
        run.close()
    }

    @Test
    fun `a shell escape sent once the process is over sends nothing and says so`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.process.destroyForcibly()
        withTimeout(10_000) { run.session.state.first { it.ended } }
        run.session.sendShellEscape("shell-1", "echo hi", "hi\n", respond = false)
        val state = run.session.state.value
        assertEquals("The output of ! echo hi was not sent: the CLI ended first.", (state.entries.last() as ChatEntry.Notice).text)
        assertEquals(emptySet(), state.quietSends, "no frame was sent, so no quiet result is awaited")
        assertEquals(0, run.userFrames().size)
        run.close()
    }

    @Test
    fun `a shell escape sent once the CLI's output has ended sends nothing and says so`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.written("\"subtype\":\"initialize\"")
        // The CLI's stdout ends while the process lives on: waitFor still blocks, so only the end of the output can count.
        run.process.script.close()
        withTimeout(10_000) {
            while (run.session.request(50) { HostMessages.interrupt(it) } != Reply.Ended) delay(20)
        }
        run.session.sendShellEscape("shell-1", "echo hi", "hi\n", respond = false)
        val state = run.session.state.value
        assertEquals("The output of ! echo hi was not sent: the CLI ended first.", (state.entries.last() as ChatEntry.Notice).text)
        assertEquals(emptySet(), state.quietSends, "no frame was sent, so no quiet result is awaited")
        assertEquals(0, run.userFrames().size)
        run.close()
    }

    @Test
    fun `a shell escape after a prompt waits for the prompt's result`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.session.sendUserMessage("x")
        run.session.sendShellEscape("shell-1", "echo hi", "hi\n", respond = true)
        run.written("\"type\":\"user\"")
        delay(300)
        assertEquals(1, run.userFrames().size, "only the prompt")
        assertTrue(run.session.state.value.entries.none { it is ChatEntry.Shell && it.sent })

        Fixtures.lines("plain-reply").forEach(run::write)
        val frames = withTimeout(2_000) {
            while (run.userFrames().size < 2) delay(20)
            run.userFrames()
        }
        assertEquals("<bash-input>echo hi</bash-input>", frames[1].content())
        val state = run.session.state.value
        assertEquals(1, state.entries.count { it is ChatEntry.TurnEnded }, "the prompt's turn ended")
        assertTrue(state.busy, "the shell escape's reply turn is open")
        run.close()
    }

    // --- Desktop parity (spec 3.2, 4.2, 9.2): get_usage after a usage line, get_context_usage ---

    /** The `response` object of the `control_response` at [line] of fixture [name], as JSON text. */
    private fun payloadAt(name: String, line: Int): String =
        Json.parseToJsonElement(Fixtures.lines(name)[line - 1]).jsonObject["response"]!!.jsonObject["response"].toString()

    private fun ChatState.usageCard(): UsageCard? =
        entries.filterIsInstance<ChatEntry.Notice>().lastOrNull { it.sourceUuid != null }?.usage

    private suspend fun ScriptedSession.card(done: (UsageCard) -> Boolean): UsageCard =
        withTimeout(10_000) { session.state.first { s -> s.usageCard()?.let(done) == true }.usageCard()!! }

    private fun UsagePartState<*>.loading(): Boolean = this == UsagePartState.Loading

    @Test
    fun `a usage line sends both get_usage requests in order`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.write(Fixtures.lines("parity-usage")[21])
        run.settled()
        assertTrue(run.stdin.readLines().none { "get_usage" in it }, "nothing before the local turn's result")
        assertEquals(UsageCard(UsagePartState.Loading, UsagePartState.Loading), run.session.state.value.usageCard())

        run.write(Fixtures.lines("parity-usage")[22])
        val first = run.requestOf("get_usage")
        assertEquals(true, first["request"]!!.jsonObject["skip_behaviors"]!!.jsonPrimitive.boolean)
        run.success(first["request_id"]!!.jsonPrimitive.content, payloadAt("parity-usage", 16))
        val second = run.requestOf("get_usage", 2)
        assertFalse("skip_behaviors" in second["request"]!!.jsonObject, second.toString())
        run.success(second["request_id"]!!.jsonPrimitive.content, payloadAt("parity-usage", 17))

        val card = run.card { !it.session.loading() && !it.history.loading() }
        val session = assertIs<UsagePartState.Loaded<*>>(card.session).value as dev.daarkolabs.tandem.protocol.SessionUsage
        assertEquals(0.1523063, session.totalCostUsd)
        val history = assertIs<UsagePartState.Loaded<*>>(card.history).value as dev.daarkolabs.tandem.protocol.UsageHistory
        assertEquals(23, history.day?.sessionCount)
        assertEquals(2, run.stdin.readLines().count { "get_usage" in it })
        run.close()
    }

    @Test
    fun `the history request is not sent once the process ended`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.write(Fixtures.lines("parity-usage")[21])
        run.write(Fixtures.lines("parity-usage")[22])
        run.written("get_usage")
        run.die()

        val card = run.card { !it.session.loading() && !it.history.loading() }
        assertEquals(UsageCard(UsagePartState.Failed(McpError.Ended), UsagePartState.Failed(McpError.Ended)), card)
        withTimeout(10_000) { run.session.job.children.toList().forEach { it.join() } }
        assertEquals(1, run.stdin.readLines().count { "get_usage" in it })
        run.close()
    }

    // spec: contextUsage decodes off the caller's thread and times out at 30 s
    @Test
    fun `contextUsage times out at 30 s and a success lands in the state`() = runBlocking {
        val quiet = ScriptedSession(tmp, newSessionId, mcpMillisPerSecond = 10) // a second is 10 ms here; nothing answers
        quiet.session.start()
        assertEquals(McpResult.Failed(McpError.TimedOut(30)), withTimeout(10_000) { quiet.session.contextUsage() })
        assertEquals(null, quiet.settled().contextBreakdown)
        quiet.close()

        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        val b17 = ControlPayloads.contextUsage(
            (Ndjson.decode(Fixtures.lines("parity-context")[16]) as CliMessage.ControlResponse).payload,
        )!!
        val answered = async { run.session.contextUsage() }
        run.success(run.requestIdOf("get_context_usage"), payloadAt("parity-context", 17))
        assertEquals(McpResult.Ok(b17), withTimeout(10_000) { answered.await() })
        assertEquals(b17, run.session.state.value.contextBreakdown)

        val empty = async { run.session.contextUsage() }
        run.success(run.requestIdOf("get_context_usage", 2), "{}")
        assertEquals(McpResult.Failed(McpError.Rejected("the CLI's answer had no categories")), withTimeout(10_000) { empty.await() })
        assertEquals(b17, run.session.state.value.contextBreakdown, "a failed answer keeps the last breakdown")
        run.close()
    }

    @Test
    fun `retryUsage of the history sends the full request alone`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.session.retryUsage("<uuid-28>", UsagePart.HISTORY)
        val request = run.requestOf("get_usage")
        assertFalse("skip_behaviors" in request["request"]!!.jsonObject, request.toString())
        run.success(request["request_id"]!!.jsonPrimitive.content, payloadAt("parity-usage", 17))
        run.settled()
        assertEquals(1, run.stdin.readLines().count { "get_usage" in it })
        run.close()
    }

    // --- Sending while Claude works (spec 12.3, 12.4, 9.7) ---

    /** The lines [from]..[to] (1-based) of fixture [name] as written, with each placeholder of [uuids] rewritten to its value. */
    private fun rewritten(name: String, from: Int, to: Int, uuids: Map<String, String> = emptyMap()): List<String> =
        Fixtures.lines(name).subList(from - 1, to).map { line -> uuids.entries.fold(line) { acc, (k, v) -> acc.replace(k, v) } }

    /** A session whose prompt's turn runs (its frame sent, then [name]'s lines 1..[through] written) and read. */
    private suspend fun turnOpen(name: String = "queue-fold", through: Int = 6, mcpMillisPerSecond: Long = 1_000L): ScriptedSession {
        val run = ScriptedSession(tmp, newSessionId, mcpMillisPerSecond = mcpMillisPerSecond)
        run.session.start()
        run.session.sendUserMessage("prompt")
        run.written("\"type\":\"user\"")
        rewritten(name, 1, through).forEach(run::write)
        run.settled()
        return run
    }

    private fun ChatState.stages(): List<Pair<String, PendingStage>> = pendingSends.map { it.uuid to it.stage }

    /** The `user` frames once there are [count]. */
    private suspend fun ScriptedSession.userFrames(count: Int): List<JsonObject> = withTimeout(10_000) {
        while (userFrames().size < count) delay(20)
        userFrames()
    }

    private fun JsonObject.uuid(): String = getValue("uuid").jsonPrimitive.content

    private fun JsonObject.text(): String =
        getValue("message").jsonObject.getValue("content").let { content ->
            (content as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: content.jsonArray.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
        }

    private suspend fun ScriptedSession.awaitState(predicate: (ChatState) -> Boolean): ChatState =
        withTimeout(10_000) { session.state.first(predicate) }

    @Test
    fun `a message sent mid-turn goes out at once without priority`() = runBlocking {
        val run = turnOpen(through = 18)
        run.session.sendUserMessage("more")
        val frame = run.userFrames(2)[1]
        assertEquals("more", frame.text())
        assertFalse("priority" in frame)
        assertEquals(listOf(frame.uuid() to PendingStage.QUEUED), run.session.state.value.stages())
        run.close()
    }

    @Test
    fun `a later message carries priority later`() = runBlocking {
        val run = turnOpen(through = 18)
        run.session.sendUserMessage("more", later = true)
        val line = run.written("\"type\":\"user\"", 2)[1]
        val frame = Json.parseToJsonElement(line).jsonObject
        assertTrue("\"uuid\":\"${frame.uuid()}\",\"priority\":\"later\"" in line, line)
        assertTrue(run.session.state.value.pendingSends.single().later)
        run.close()
    }

    @Test
    fun `interrupt adds cancel_queued only when a message waits and the init lists the capability`() = runBlocking {
        val run = turnOpen()
        run.session.sendUserMessage("more")
        val uuid = run.session.state.value.pendingSends.single().uuid
        run.session.interrupt()
        val request = run.requestOf("interrupt")
        assertEquals(true, request.getValue("request").jsonObject["cancel_queued"]?.jsonPrimitive?.boolean)
        // queue-cancel:56's payload, its uuid the send's.
        run.success(request.getValue("request_id").jsonPrimitive.content, """{"still_queued":[],"cancelled":["$uuid"]}""")
        val returned = run.awaitState { it.pendingSends.isEmpty() }
        assertEquals(listOf(uuid), returned.returnedSends.map { it.uuid })
        assertEquals(listOf("Not sent: \"more\". It is back in the composer."), returned.notices(NoticeLevel.INFO))
        run.close()

        // Nothing waiting: today's frame, byte for byte.
        val idle = turnOpen()
        idle.session.interrupt()
        val plain = idle.requestOf("interrupt")
        assertEquals(HostMessages.interrupt(plain.getValue("request_id").jsonPrimitive.content), plain)
        idle.close()

        // An init without the capability: today's frame, though a message waits.
        val older = ScriptedSession(tmp, newSessionId)
        older.session.start()
        older.session.sendUserMessage("prompt")
        older.written("\"type\":\"user\"")
        val init = Json.parseToJsonElement(Fixtures.lines("plain-reply")[3]).jsonObject
        val capabilities = init.getValue("capabilities").jsonArray.filter { it.jsonPrimitive.content != "interrupt_cancel_queued_v1" }
        older.write(JsonObject(init + ("capabilities" to kotlinx.serialization.json.JsonArray(capabilities))).toString())
        older.settled()
        older.session.sendUserMessage("more")
        assertEquals(1, older.session.state.value.pendingSends.size)
        older.session.interrupt()
        val without = older.requestOf("interrupt")
        assertEquals(HostMessages.interrupt(without.getValue("request_id").jsonPrimitive.content), without)
        older.close()
    }

    // spec: withdraw writes cancel_async_message; true removes; false, an error or a timeout refuses with the notice
    @Test
    fun `withdraw writes cancel_async_message, true removes, false, an error or a timeout refuses with the notice`() = runBlocking {
        val refusal = "Claude had already taken \"more\"; it was not withdrawn."
        val run = turnOpen()
        run.session.sendUserMessage("more", later = true)
        val uuid = run.session.state.value.pendingSends.single().uuid

        run.session.withdraw(uuid, restore = true)
        val request = run.requestOf("cancel_async_message")
        assertEquals(
            Json.parseToJsonElement("""{"subtype":"cancel_async_message","message_uuid":"$uuid"}"""),
            request.getValue("request"),
        )
        assertEquals(listOf(uuid to PendingStage.WITHDRAWING), run.session.state.value.stages())
        run.success(request.getValue("request_id").jsonPrimitive.content, """{"cancelled":true}""")
        val gone = run.awaitState { it.pendingSends.isEmpty() }
        assertEquals(listOf(uuid), gone.returnedSends.map { it.uuid })

        run.session.sendUserMessage("more", later = true)
        val second = run.session.state.value.pendingSends.single().uuid
        run.session.withdraw(second, restore = false)
        run.success(run.requestIdOf("cancel_async_message", 2), """{"cancelled":false}""")
        val refused = run.awaitState { s -> s.notices(NoticeLevel.INFO).size == 1 }
        assertEquals(listOf(second to PendingStage.QUEUED), refused.stages())
        assertEquals(listOf(refusal), refused.notices(NoticeLevel.INFO))

        run.session.withdraw(second, restore = false)
        run.error(run.requestIdOf("cancel_async_message", 3), "boom")
        val failed = run.awaitState { s -> s.notices(NoticeLevel.INFO).size == 2 }
        assertEquals(listOf(second to PendingStage.QUEUED), failed.stages())
        run.close()

        // No answer: 5 s, scaled.
        val slow = turnOpen(mcpMillisPerSecond = 10)
        slow.session.sendUserMessage("more", later = true)
        val waiting = slow.session.state.value.pendingSends.single().uuid
        slow.session.withdraw(waiting, restore = true)
        slow.requestOf("cancel_async_message")
        val timedOut = slow.awaitState { s -> s.notices(NoticeLevel.INFO).isNotEmpty() }
        assertEquals(listOf(waiting to PendingStage.QUEUED), timedOut.stages())
        assertEquals(listOf(refusal), timedOut.notices(NoticeLevel.INFO))
        slow.close()
    }

    @Test
    fun `a prompt sent while a shell escape's output frame waits goes out right after it`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.session.sendShellEscape("shell-1", "echo hi", "hi\n", respond = true)
        val quiet = run.userFrames(1).single().uuid()
        run.session.sendUserMessage("next")
        delay(300)
        assertEquals(1, run.userFrames().size, "the prompt waits")
        val held = run.session.state.value.stages().single()
        assertEquals(PendingStage.HELD, held.second)

        lines("bash-mode-respond", 3, 7) { n, fields ->
            if (n == 6) fields["user_message_uuids"] = Json.parseToJsonElement("[\"$quiet\"]")
        }.forEach(run::write)
        val frames = run.userFrames(3)
        assertEquals("<bash-input>echo hi</bash-input>", frames[0].content())
        assertEquals("<bash-stdout>hi\n</bash-stdout><bash-stderr></bash-stderr>", frames[1].content())
        assertEquals("next", frames[2].text())
        assertEquals(held.first, frames[2].uuid())
        assertEquals(listOf(held.first to PendingStage.QUEUED), run.awaitState { it.stages().none { s -> s.second == PendingStage.HELD } }.stages())
        run.close()
    }

    @Test
    fun `a prompt sent while a ! command runs goes out after the escape's frames`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.session.event(SessionEvent.ShellStarted("s1", "echo hi", 0))
        run.session.sendUserMessage("next")
        delay(300)
        assertEquals(0, run.userFrames().size, "no frame while the command runs")
        val held = run.session.state.value.stages().single()
        assertEquals(PendingStage.HELD, held.second)

        run.session.sendShellEscape("s1", "echo hi", "hi\n", respond = true)
        val quiet = run.userFrames(1).single().uuid()
        assertEquals("<bash-input>echo hi</bash-input>", run.userFrames().single().content())
        lines("bash-mode-respond", 3, 7) { n, fields ->
            if (n == 6) fields["user_message_uuids"] = Json.parseToJsonElement("[\"$quiet\"]")
        }.forEach(run::write)
        val frames = run.userFrames(3)
        assertEquals("<bash-stdout>hi\n</bash-stdout><bash-stderr></bash-stderr>", frames[1].content())
        assertEquals("next", frames[2].text())
        assertEquals(listOf(held.first to PendingStage.QUEUED), run.awaitState { it.stages().none { s -> s.second == PendingStage.HELD } }.stages())
        run.close()
    }

    @Test
    fun `a prompt held behind a ! that sends nothing goes out when it ends`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.session.event(SessionEvent.ShellStarted("s1", "nope", 0))
        run.session.sendUserMessage("next")
        delay(300)
        assertEquals(0, run.userFrames().size)
        val uuid = run.session.state.value.stages().single().also { assertEquals(PendingStage.HELD, it.second) }.first
        run.session.shellUnsent("s1")
        assertEquals("next", run.userFrames(1).single().text())
        assertEquals(listOf(uuid to PendingStage.QUEUED), run.session.state.value.stages())
        run.close()
    }

    @Test
    fun `a held escape waits until no queued message remains`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.session.sendUserMessage("prompt")
        val prompt = run.userFrames(1).single().uuid()
        run.session.sendUserMessage("more", later = true)
        val later = run.userFrames(2)[1].uuid()
        val uuids = mapOf("<command_uuid-4>" to prompt, "<command_uuid-25>" to later)
        rewritten("queue-later", 1, 18, uuids).forEach(run::write)
        run.settled()
        assertEquals(listOf(later to PendingStage.QUEUED), run.session.state.value.stages())
        run.session.sendShellEscape("shell-1", "echo hi", "hi\n", respond = true)

        rewritten("queue-later", 19, 51, uuids).forEach(run::write)
        run.awaitState { it.turnsEnded == 1 }
        delay(300)
        assertEquals(2, run.userFrames().size, "the later message waits in the CLI's queue: no escape yet")

        rewritten("queue-later", 52, 63, uuids).forEach(run::write)
        val frames = run.userFrames(3)
        assertEquals("<bash-input>echo hi</bash-input>", frames[2].content())
        assertEquals(2, run.session.state.value.turnsEnded)
        run.close()
    }

    @Test
    fun `the process ending drops held prompts and returns them`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId, quietWaitMillis = 60_000)
        run.session.start()
        run.session.sendShellEscape("shell-1", "echo hi", "hi\n", respond = true)
        run.userFrames(1)
        run.session.sendUserMessage("next")
        val uuid = run.session.state.value.stages().single().first
        run.process.destroyForcibly()
        val ended = run.awaitState { it.ended }
        assertEquals(1, run.userFrames().size, "no prompt frame")
        assertEquals(listOf(ReturnedSend(uuid, "next", emptyList())), ended.returnedSends)
        assertTrue(ended.pendingSends.isEmpty())
        assertEquals(
            listOf("Not sent: \"next\": the CLI ended before reading it. It is back in the composer."),
            ended.notices(NoticeLevel.WARNING).filter { it.startsWith("Not sent") },
        )
        run.close()
    }

    @Test
    fun `a send the interrupt receipt names nowhere comes back with Stop's notice`() = runBlocking {
        val run = turnOpen()
        run.session.sendUserMessage("more")
        val uuid = run.session.state.value.pendingSends.single().uuid
        run.session.interrupt()
        val request = run.requestOf("interrupt")
        assertEquals(true, request.getValue("request").jsonObject["cancel_queued"]?.jsonPrimitive?.boolean)
        run.success(request.getValue("request_id").jsonPrimitive.content, """{"still_queued":[],"cancelled":[]}""")
        val returned = run.awaitState { it.pendingSends.isEmpty() }
        assertEquals(listOf(ReturnedSend(uuid, "more", emptyList())), returned.returnedSends)
        assertEquals(listOf("Not sent: \"more\". It is back in the composer."), returned.notices(NoticeLevel.INFO))
        run.close()
    }

    @Test
    fun `a prompt sent while a context-only escape's quiet result is due goes out after it`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.session.event(SessionEvent.ShellStarted("s1", "echo hi", 0))
        run.session.sendUserMessage("next")
        val uuid = run.session.state.value.stages().single().also { assertEquals(PendingStage.HELD, it.second) }.first
        run.session.sendShellEscape("s1", "echo hi", "hi\n", respond = false)
        val quiet = run.userFrames(1).single().uuid()
        delay(300)
        assertEquals(1, run.userFrames().size, "the prompt waits for the quiet result")
        assertEquals(listOf(uuid to PendingStage.HELD), run.session.state.value.stages())
        lines("bash-mode", 3, 8) { n, fields ->
            if (n == 8) fields["user_message_uuids"] = Json.parseToJsonElement("[\"$quiet\"]")
        }.forEach(run::write)
        val frames = run.userFrames(2)
        assertTrue(frames[0].content().startsWith("<bash-input>echo hi</bash-input>"), frames[0].content())
        assertEquals("next", frames[1].text())
        assertEquals(listOf(uuid to PendingStage.QUEUED), run.awaitState { it.stages().none { s -> s.second == PendingStage.HELD } }.stages())
        run.close()

        // No command running: an ended command's context frame, then a prompt before its quiet result.
        val again = ScriptedSession(tmp, newSessionId)
        again.session.start()
        again.session.sendShellEscape("s2", "echo hi", "hi\n", respond = false)
        val second = again.userFrames(1).single().uuid()
        again.session.sendUserMessage("next")
        delay(300)
        assertEquals(1, again.userFrames().size, "no prompt frame until the quiet result")
        lines("bash-mode", 3, 8) { n, fields ->
            if (n == 8) fields["user_message_uuids"] = Json.parseToJsonElement("[\"$second\"]")
        }.forEach(again::write)
        assertEquals("next", again.userFrames(2)[1].text())
        again.close()
    }

    /** Serves Tandem's tools in a test: each call goes to [calls]; [answer] answers it (by default it waits until cancelled). */
    private class FakeHostTools(private val answer: suspend (ToolCall) -> ToolReply = { awaitCancellation() }) : HostTools {
        val calls = CompletableDeferred<ToolCall>()
        val callCancelled = CompletableDeferred<Unit>()
        val cancelled: MutableList<Pair<SessionId, JsonElement>> = Collections.synchronizedList(ArrayList())
        val cancelledAll: MutableList<SessionId> = Collections.synchronizedList(ArrayList())
        val allCancelled = CompletableDeferred<Unit>()

        override suspend fun call(call: ToolCall): ToolReply {
            calls.complete(call)
            try {
                return answer(call)
            } catch (e: CancellationException) {
                callCancelled.complete(Unit)
                throw e
            }
        }

        override fun cancel(session: SessionId, requestId: JsonElement) {
            cancelled.add(session to requestId)
        }

        override fun cancelAll(session: SessionId) {
            cancelledAll.add(session)
            allCancelled.complete(Unit)
        }
    }

    /** The `mcp_response` the host wrote for control request [requestId], once it is written. */
    private suspend fun ScriptedSession.mcpResponse(requestId: String): JsonObject {
        val line = written("\"request_id\":\"$requestId\"").single { "mcp_response" in it }
        return Json.parseToJsonElement(line).jsonObject["response"]!!.jsonObject["response"]!!.jsonObject["mcp_response"]!!.jsonObject
    }

    /**
     * Waits until every frame the host sent before now is written: the outbox writes in order, so once the answer to a `ping` sent
     * now is in [ScriptedSession.stdin], so is everything before it.
     */
    private suspend fun ScriptedSession.drained() {
        write("""{"type":"control_request","request_id":"barrier","request":{"subtype":"mcp_message","server_name":"tandem","message":{"jsonrpc":"2.0","id":99,"method":"ping"}}}""")
        mcpResponse("barrier")
    }

    /** The `request_id` of line [n] of fixture [name]. */
    private fun requestIdAt(name: String, n: Int): String =
        Json.parseToJsonElement(Fixtures.lines(name)[n - 1]).jsonObject["request_id"]!!.jsonPrimitive.content

    // spec: the handshake is answered in order while a text turn streams
    @Test
    fun `the handshake is answered in order while a text turn streams`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.session.sendUserMessage("Change alpha to beta in hello.txt")
        run.written("\"type\":\"user\"")
        // initialize (ide-edit:1), notifications/initialized (:4) and tools/list (:6), among the turn's own lines up to :18.
        lines("ide-edit", 1, 18).forEach(run::write)

        val responses = run.written("mcp_response", count = 3).map { Json.parseToJsonElement(it).jsonObject["response"]!!.jsonObject }
        assertEquals(listOf(1, 4, 6).map { requestIdAt("ide-edit", it) }, responses.map { it["request_id"]!!.jsonPrimitive.content })
        val replies = responses.map { it["response"]!!.jsonObject["mcp_response"]!!.jsonObject }
        assertEquals(listOf(0, 0, 1), replies.map { it["id"]!!.jsonPrimitive.int }, "initialize 0, the notification's 0, tools/list 1")
        val server = replies[0]["result"]!!.jsonObject
        assertEquals("2025-11-25", server["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("0.4.0", server["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), replies[1]["result"])
        assertEquals(listOf("edit_file", "write_file"), replies[2]["result"]!!.jsonObject["tools"]!!.jsonArray.map {
            it.jsonObject["name"]!!.jsonPrimitive.content
        })
        assertTrue(run.session.state.value.busy, "answered while the turn streams, before its result")
        assertEquals(emptyList(), run.settled().notices(NoticeLevel.ERROR), "no frame of the handshake went to the reducer as an error")
        run.close()
    }

    // spec: a tools call is answered from the host tools off the reader
    @Test
    fun `a tools call is answered from the host tools off the reader`() = runBlocking {
        val release = CompletableDeferred<ToolReply>()
        val tools = FakeHostTools { release.await() }
        val run = ScriptedSession(tmp, newSessionId, hostTools = tools)
        run.session.start()
        run.write(Fixtures.lines("ide-edit")[38 - 1])
        val call = withTimeout(10_000) { tools.calls.await() }
        assertEquals(ToolCall(call.name, call.arguments, "<id-32>", JsonPrimitive(2), newSessionId), call)
        assertEquals("edit_file", call.name)
        assertEquals("<path>/hello.txt", call.arguments["file_path"]!!.jsonPrimitive.content)

        // The reader goes on while the host works: a later line is reduced before the release.
        run.write(Fixtures.lines("ide-edit")[11 - 1])
        val streaming = run.settled()
        assertTrue(streaming.entries.filterIsInstance<ChatEntry.Assistant>().any { it.streaming }, "ide-edit:11's message_start was reduced")
        assertFalse(run.stdin.readLines().any { "mcp_response" in it }, "nothing answers the call before the host does")

        release.complete(ToolReply("Applied.", isError = false))
        val reply = run.mcpResponse(requestIdAt("ide-edit", 38))
        assertEquals(JsonPrimitive(2), reply["id"])
        val content = reply["result"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("Applied.", content["text"]!!.jsonPrimitive.content)
        assertNull(reply["result"]!!.jsonObject["isError"])
        run.close()
    }

    // spec: notifications/cancelled cancels the pending call's job and the late reply is not sent
    @Test
    fun `notifications cancelled cancels the pending call's job and the late reply is not sent`() = runBlocking {
        val tools = FakeHostTools()
        val run = ScriptedSession(tmp, newSessionId, hostTools = tools)
        run.session.start()
        run.write(Fixtures.lines("ide-edit-stop")[39 - 1])
        withTimeout(10_000) { tools.calls.await() }
        run.write(Fixtures.lines("ide-edit-stop")[42 - 1])

        withTimeout(10_000) { tools.callCancelled.await() }
        assertEquals(listOf<Pair<SessionId, JsonElement>>(newSessionId to JsonPrimitive(2)), tools.cancelled.toList())
        // The notification itself is acknowledged, the call never.
        val ack = run.mcpResponse(requestIdAt("ide-edit-stop", 42))
        assertEquals(Json.parseToJsonElement("""{"jsonrpc":"2.0","result":{},"id":0}"""), ack)
        run.settled()
        val callId = requestIdAt("ide-edit-stop", 39)
        assertFalse(run.stdin.readLines().any { "\"request_id\":\"$callId\"" in it }, "no mcp_response for the withdrawn call")
        run.close()
    }

    // spec: notifications/cancelled cancels the pending call's job and the late reply is not sent
    @Test
    fun `a call withdrawn by notifications cancelled whose host still answers late sends no reply`() = runBlocking {
        val late = CompletableDeferred<Unit>()
        val callJob = CompletableDeferred<Job>()
        val tools = FakeHostTools {
            callJob.complete(currentCoroutineContext().job)
            withContext(NonCancellable) { late.await() } // a host that ignores the cancellation and answers anyway
            ToolReply("late", isError = false)
        }
        val run = ScriptedSession(tmp, newSessionId, hostTools = tools)
        run.session.start()
        run.write(Fixtures.lines("ide-edit-stop")[39 - 1])
        withTimeout(10_000) { tools.calls.await() }
        run.write(Fixtures.lines("ide-edit-stop")[42 - 1])
        run.mcpResponse(requestIdAt("ide-edit-stop", 42)) // acknowledged after the call was withdrawn
        assertEquals(listOf<Pair<SessionId, JsonElement>>(newSessionId to JsonPrimitive(2)), tools.cancelled.toList())
        val job = withTimeout(10_000) { callJob.await() }
        assertTrue(job.isCancelled)

        late.complete(Unit)
        withTimeout(10_000) { job.join() } // the host answered, and the job has done whatever it does with the answer
        run.drained()
        val callId = requestIdAt("ide-edit-stop", 39)
        assertFalse(run.stdin.readLines().any { "\"request_id\":\"$callId\"" in it }, "the late answer is dropped")
        run.close()
    }

    // round-1 review: the CLI reused an open JSON-RPC id
    @Test
    fun `a tools call whose id is still open withdraws the older call and only the newer one answers`() = runBlocking {
        val seen = AtomicInteger()
        val olderEnded = CompletableDeferred<Unit>()
        val tools = FakeHostTools {
            if (seen.incrementAndGet() == 1) {
                try {
                    awaitCancellation()
                } finally {
                    olderEnded.complete(Unit)
                }
            }
            ToolReply("newer", isError = false)
        }
        val run = ScriptedSession(tmp, newSessionId, hostTools = tools)
        run.session.start()
        val line = Fixtures.lines("ide-edit")[38 - 1]
        val olderId = requestIdAt("ide-edit", 38)
        run.write(line)
        withTimeout(10_000) { tools.calls.await() }
        run.write(line.replace("\"request_id\":\"$olderId\"", "\"request_id\":\"again\""))

        val reply = run.mcpResponse("again")
        assertEquals("newer", reply["result"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        withTimeout(10_000) { olderEnded.await() }
        assertEquals(listOf<Pair<SessionId, JsonElement>>(newSessionId to JsonPrimitive(2)), tools.cancelled.toList())
        run.drained()
        assertFalse(run.stdin.readLines().any { "\"request_id\":\"$olderId\"" in it }, "the older call gets no reply")
        run.close()
    }

    // round-1 review: a host that fails answers an error, so the CLI never waits (callHostTools' contract, not a spec-named test)
    @Test
    fun `a host that throws an Error still answers the call with an error`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId, hostTools = FakeHostTools { throw NotImplementedError("lane A") })
        run.session.start()
        run.write(Fixtures.lines("ide-edit")[38 - 1])
        val failed = run.mcpResponse(requestIdAt("ide-edit", 38))["result"]!!.jsonObject
        assertEquals(true, failed["isError"]!!.jsonPrimitive.boolean)
        assertEquals(
            "Tandem could not run edit_file: NotImplementedError.",
            failed["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
        )
        run.close()
    }

    // round-2 review: a CancellationException from the host while the call's own job is active is a failure, not a withdrawal
    @Test
    fun `a host that throws a CancellationException of its own still answers the call with an error`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId, hostTools = FakeHostTools { throw CancellationException("host") })
        run.session.start()
        run.write(Fixtures.lines("ide-edit")[38 - 1])
        val failed = run.mcpResponse(requestIdAt("ide-edit", 38))["result"]!!.jsonObject
        assertEquals(true, failed["isError"]!!.jsonPrimitive.boolean)
        assertEquals(
            "Tandem could not run edit_file: CancellationException.",
            failed["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
        )
        run.drained()
        val callId = requestIdAt("ide-edit", 38)
        assertEquals(1, run.stdin.readLines().count { "mcp_response" in it && "\"request_id\":\"$callId\"" in it }, "exactly one reply")
        run.close()
    }

    // spec: an unknown server_name gets -32601
    @Test
    fun `an unknown server_name gets -32601`() = runBlocking {
        val tools = FakeHostTools()
        val run = ScriptedSession(tmp, newSessionId, hostTools = tools)
        run.session.start()
        val call = Fixtures.lines("ide-edit")[38 - 1].replace("\"server_name\":\"tandem\"", "\"server_name\":\"elsewhere\"")
        run.write(call)

        val reply = run.mcpResponse(requestIdAt("ide-edit", 38))
        assertEquals(
            Json.parseToJsonElement("""{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"Method not found: tools/call"}}"""),
            reply,
        )
        assertFalse(tools.calls.isCompleted, "another server's call never reaches Tandem's tools")
        run.close()
    }

    // spec: a can_use_tool for mcp__tandem__edit_file is answered allow with the input and raises no pendingPermissions
    @Test
    fun `a can_use_tool for mcp__tandem__edit_file is answered allow with the input and raises no pendingPermissions`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        val request = Json.parseToJsonElement(Fixtures.lines("ide-edit")[37 - 1]).jsonObject
        run.write(request.toString())

        val requestId = request["request_id"]!!.jsonPrimitive.content
        val frame = Json.parseToJsonElement(run.written("\"request_id\":\"$requestId\"").single()).jsonObject
        val answer = frame["response"]!!.jsonObject["response"]!!.jsonObject
        assertEquals("allow", answer["behavior"]!!.jsonPrimitive.content)
        assertEquals(request["request"]!!.jsonObject["input"], answer["updatedInput"])
        assertTrue(answer["updatedPermissions"] == null, "the suggestion to always allow is not applied")
        assertEquals(emptyList(), run.settled().pendingPermissions)
        run.close()
    }

    // spec: the version gate notice fires when init tools lack the tool and not when it has it
    @Test
    fun `the version gate notice fires when init tools lack the tool and not when it has it`() = runBlocking {
        suspend fun warnings(vararg inits: String): List<String> {
            val run = ScriptedSession(tmp, newSessionId)
            run.session.start()
            inits.forEach(run::write)
            return run.settled().notices(NoticeLevel.WARNING).also { run.close() }
        }
        val init = Fixtures.lines("ide-edit")[8 - 1]
        val without = Json.parseToJsonElement(init).jsonObject.toMutableMap().apply {
            put("tools", Json.parseToJsonElement("""["Bash","Read"]"""))
            put("claude_code_version", JsonPrimitive("2.1.312"))
        }.let(::JsonObject).toString()
        val notice = "Edits are off in this session: claude 2.1.312 did not take Tandem's edit tools. Update claude or use the terminal for edits."

        assertEquals(listOf(notice), warnings(without, without), "once per process")
        assertEquals(emptyList(), warnings(init), "ide-edit:8 lists mcp__tandem__edit_file")
        assertEquals(emptyList(), warnings(Fixtures.lines("plain-reply").first { "\"subtype\":\"init\"" in it }), "tools emptied by the redactor")
        val mcpStatusInit = Fixtures.lines("mcp-status").first { "\"subtype\":\"init\"" in it }
        val version = Json.parseToJsonElement(mcpStatusInit).jsonObject["claude_code_version"]?.jsonPrimitive?.content ?: "unknown"
        assertEquals(listOf(notice.replace("2.1.312", version)), warnings(mcpStatusInit), "recorded without the server (deviation 1)")
    }

    // spec: a session closed with a call open cancels it
    @Test
    fun `a session closed with a call open cancels it`() = runBlocking {
        val tools = FakeHostTools()
        val run = ScriptedSession(tmp, newSessionId, hostTools = tools)
        run.session.start()
        run.write(Fixtures.lines("ide-edit")[38 - 1])
        withTimeout(10_000) { tools.calls.await() }

        run.close()
        withTimeout(10_000) { tools.callCancelled.await() }
        withTimeout(10_000) { tools.allCancelled.await() }
        assertEquals(listOf<SessionId>(newSessionId), tools.cancelledAll.toList())
        assertFalse(run.stdin.readLines().any { "mcp_response" in it }, "nobody reads a reply any more")
    }

    // the process ending by itself: the spec names stop, close and the process's end as the cancelAll cases
    @Test
    fun `a process that ends by itself with a call open cancels it with no reply`() = runBlocking {
        val tools = FakeHostTools()
        val run = ScriptedSession(tmp, newSessionId, hostTools = tools)
        run.session.start()
        run.write(Fixtures.lines("ide-edit")[38 - 1])
        withTimeout(10_000) { tools.calls.await() }

        run.die()
        withTimeout(10_000) { tools.callCancelled.await() }
        withTimeout(10_000) { tools.allCancelled.await() }
        assertEquals(listOf<SessionId>(newSessionId), tools.cancelledAll.toList())
        assertFalse(run.stdin.readLines().any { "mcp_response" in it }, "no reply for the withdrawn call")
        run.close()
        assertEquals(listOf<SessionId>(newSessionId), tools.cancelledAll.toList(), "once per process")
    }

    @Test
    fun `a tools call without host tools and one whose host fails are answered with an error`() = runBlocking {
        val run = ScriptedSession(tmp, newSessionId)
        run.session.start()
        run.write(Fixtures.lines("ide-edit")[38 - 1])
        val none = run.mcpResponse(requestIdAt("ide-edit", 38))["result"]!!.jsonObject
        assertEquals(true, none["isError"]!!.jsonPrimitive.boolean)
        assertEquals(NO_HOST_TOOLS_TEXT, none["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        run.close()

        val failing = ScriptedSession(tmp, newSessionId, hostTools = FakeHostTools { throw IllegalStateException("boom") })
        failing.session.start()
        failing.write(Fixtures.lines("ide-edit")[38 - 1])
        val failed = failing.mcpResponse(requestIdAt("ide-edit", 38))["result"]!!.jsonObject
        assertEquals(true, failed["isError"]!!.jsonPrimitive.boolean)
        assertEquals(
            "Tandem could not run edit_file: IllegalStateException.",
            failed["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
        )
        failing.close()
    }

}
