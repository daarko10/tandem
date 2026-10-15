package dev.daarkolabs.tandem.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs [ClaudeSession] against a fake CLI (a shell script), so no real `claude` is needed. */
class ClaudeSessionTest {
    private val fixtures = File(System.getProperty("tandem.fixtures") ?: "src/test/resources/fixtures")
    private val tmp: Path = File(System.getProperty("java.io.tmpdir")).toPath()

    private fun fakeCli(script: String) = ProcessLauncher { _, dir -> ProcessBuilder("sh", "-c", script).directory(dir.toFile()).start() }

    @Test
    fun `replays a transcript into state and records what the host wrote`() = runBlocking {
        val stdin = File.createTempFile("tandem-stdin", ".ndjson").apply { deleteOnExit() }
        // Echo the fixture, then keep reading stdin until the host closes it, like the CLI does.
        val launcher = fakeCli("cat '${File(fixtures, "plain-reply.ndjson").path}'; cat > '${stdin.path}'")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp, model = "claude-sonnet-5-5"), launcher)
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
        val session = ClaudeSession(scope, SessionConfig("claude", tmp), launcher)
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
    fun `a missing binary ends the session with a visible error`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("/nonexistent/claude", tmp))
        session.start().join()
        val notice = session.state.value.entries.last() as ChatEntry.Notice
        assertEquals(NoticeLevel.ERROR, notice.level)
        assertTrue(session.state.value.ended)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `a crash is reported with the exit code and stderr`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeSession(scope, SessionConfig("claude", tmp), fakeCli("echo 'bad flag' >&2; exit 3"))
        session.start()
        val final = withTimeout(10_000) { session.state.first { it.ended } }
        val notice = assertNotNull(final.entries.last() as? ChatEntry.Notice)
        assertTrue(notice.text.contains("code 3") && notice.text.contains("bad flag"), notice.text)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `argv follows the protocol reference`() {
        val argv = SessionConfig("claude", tmp, model = "opus", resumeSessionId = "s1").argv()
        assertEquals(
            listOf(
                "claude", "--output-format", "stream-json", "--verbose", "--input-format", "stream-json", "--include-partial-messages",
                "--permission-prompt-tool=stdio", "--model", "opus", "--disallowedTools", "Edit,Write,MultiEdit,NotebookEdit", "--resume", "s1",
            ),
            argv,
        )
    }

    private fun assertNull(value: Any?) = kotlin.test.assertNull(value)
}
