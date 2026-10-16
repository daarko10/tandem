package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.PermissionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import java.nio.file.Path
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
            "--include-partial-messages", "--permission-prompt-tool=stdio",
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
        assertFailsWith<IllegalArgumentException> { SessionConfig("claude", tmp) }
        assertFailsWith<IllegalArgumentException> { SessionConfig("claude", tmp, sessionId = "a", resumeSessionId = "b") }
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

    private fun assertNull(value: Any?) = kotlin.test.assertNull(value)
}
