package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.PermissionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    private class CutOffSession(process: CutOffProcess, tmp: Path, sessionId: String) {
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

    private fun assertNull(value: Any?) = kotlin.test.assertNull(value)
}
