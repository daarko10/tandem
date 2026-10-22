package dev.daarkolabs.tandem.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fails unless each of [processes] has been killed within 2 s. Asked of the process itself, not of the test's descendants: a `sleep`
 * whose shell alone was killed is reparented to PID 1, so it would no longer be among them, alive or not. Shared with
 * [SessionManagerTest].
 */
internal suspend fun assertGone(processes: List<ProcessHandle>) {
    val gone = withTimeoutOrNull(2_000) { while (processes.any { it.runs() }) delay(20) }
    assertNotNull(gone, "still running after 2 s: ${processes.filter { it.runs() }.map { it.pid() }}")
}

/**
 * Whether this process still runs: alive and, where Linux's `/proc` says, not a zombie. A killed orphan stays a zombie until PID 1
 * reaps it, which in a container can take over a second; it is killed all the same.
 */
private fun ProcessHandle.runs(): Boolean {
    if (!isAlive) return false
    val stat = runCatching { Path.of("/proc/${pid()}/stat").readText() }.getOrNull() ?: return true
    return stat.substringAfterLast(')').trim().firstOrNull() != 'Z'
}

/** [ShellRunner] against a real `/bin/sh` (spec 12.4, 12.8); the process tests skip on Windows. */
class ShellRunnerTest {
    @TempDir
    lateinit var dir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The `sleep` processes that ran before the test: another test's leftovers are not this test's to judge. */
    private val earlierSleeps: Set<Long> = sleeps().map { it.pid() }.toSet()

    private val environment = mapOf("SHELL" to "/bin/sh", "TANDEM_TEST" to "x")

    @AfterEach
    fun cancelScope(): Unit = runBlocking {
        scope.coroutineContext.job.cancelAndJoin()
        ownSleeps().forEach { it.destroyForcibly() }
    }

    private fun unix() = assumeTrue(!System.getProperty("os.name").lowercase().contains("windows"))

    private fun runner(timeoutMillis: Long = Shells.TIMEOUT_MILLIS, env: Map<String, String> = environment, launcher: ShellLauncher = Shells.defaultLauncher()) =
        ShellRunner(launcher, environment = { env }, timeoutMillis = timeoutMillis)

    private fun sleeps(): List<ProcessHandle> = ProcessHandle.current().descendants()
        .filter { it.isAlive && it.info().command().orElse("").substringAfterLast('/') == "sleep" }.toList()

    private fun ownSleeps(): List<ProcessHandle> = sleeps().filter { it.pid() !in earlierSleeps }

    /** Waits for this test's `sleep`s to start, and returns them. */
    private suspend fun awaitSleeps(): List<ProcessHandle> {
        while (true) {
            ownSleeps().takeIf { it.isNotEmpty() }?.let { return it }
            delay(20)
        }
    }

    private fun run(command: String, runner: ShellRunner = runner(), onOutput: suspend (String) -> Unit = {}): ShellResult =
        runBlocking { withTimeout(10_000) { runner.run(command, dir, onOutput) } }

    @Test
    fun `runs in the directory with the environment and merges stderr in order`() {
        unix()
        assertEquals("abc", run("printf a; printf b >&2; printf c").output)
        assertEquals(dir.toRealPath().toString() + "\n", run("pwd").output)
        assertEquals("x\n", run("echo \$TANDEM_TEST").output)
    }

    @Test
    fun `stdin is closed`() {
        unix()
        val started = System.currentTimeMillis()
        val result = run("cat")
        assertEquals(ShellEnd.Exited(0), result.end)
        assertEquals("", result.output)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test
    fun `a non-zero exit is reported`() {
        unix()
        assertEquals(ShellEnd.Exited(3), run("exit 3").end)
    }

    @Test
    fun `output over the cap is cut and the rest drained`() {
        unix()
        val result = run("head -c 300000 /dev/zero | tr '\\0' a")
        assertEquals("a".repeat(100_000) + "\n… (cut, 200000 more characters)", result.output)
        assertEquals(200_000, result.cut)
        assertEquals(ShellEnd.Exited(0), result.end)
    }

    @Test
    fun `invalid UTF-8 is replaced`() {
        unix()
        assertEquals("\uFFFD\uFFFD", run("printf '\\377\\376'").output)
    }

    @Test
    fun `a sequence split across chunks is not broken`() {
        unix()
        val result = run("head -c 8191 /dev/zero | tr '\\0' a; printf '\\342\\202\\254'")
        assertEquals("a".repeat(8191) + "€", result.output)
        assertFalse('\uFFFD' in result.output)
    }

    @Test
    fun `the tree is killed on stop`() = runBlocking<Unit> {
        unix()
        val running = scope.async { runner().run("sleep 60 & wait", dir) {} }
        val sleeps = withTimeout(10_000) { awaitSleeps() }
        val started = System.currentTimeMillis()
        running.cancelAndJoin()
        assertTrue(System.currentTimeMillis() - started < 2_000, "the job ends at once")
        assertGone(sleeps)
    }

    @Test
    fun `the cap kills after the timeout`() {
        unix()
        val started = System.currentTimeMillis()
        // The shell prints the sleep's pid as it starts it, since 200 ms is too short to look for the sleep among the descendants.
        val result = run("sleep 5 & echo \$!; wait", runner(timeoutMillis = 200))
        assertEquals(ShellEnd.TimedOut, result.end)
        assertTrue(System.currentTimeMillis() - started < 2_000)
        // No pid: the cap came before the shell had started anything (a slow login profile), so there is no sleep to look for.
        val sleep = result.output.lineSequence().firstNotNullOfOrNull { it.trim().toLongOrNull() }
        runBlocking { assertGone(listOfNotNull(sleep?.let { ProcessHandle.of(it).orElse(null) })) }
    }

    @Test
    fun `a background child that keeps the output open does not hold the row`() {
        unix()
        // 5 s, not 30: the sleep outlives its shell, as an orphan that no cleanup here can find, so it must end on its own soon.
        val started = System.currentTimeMillis()
        val result = run("sleep 5 & echo x")
        assertEquals(ShellEnd.Exited(0), result.end)
        assertEquals("x\n", result.output)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test
    fun `killAllNow destroys a running command's tree`() = runBlocking<Unit> {
        unix()
        val shell = runner()
        val running = scope.async { shell.run("sleep 30 & wait", dir) {} }
        val sleeps = withTimeout(10_000) { awaitSleeps() }
        val started = System.currentTimeMillis()
        shell.killAllNow()
        val result = withTimeout(2_000) { running.await() }
        assertTrue(System.currentTimeMillis() - started < 2_000)
        assertEquals(ShellEnd.Stopped, result.end, "the IDE's exit ended it, not the command")
        assertGone(sleeps)
    }

    @Test
    fun `a shell that cannot start reports NotStarted`() {
        unix()
        val result = run("echo x", runner(env = mapOf("SHELL" to "/nonexistent/sh")))
        assertEquals(ShellEnd.NotStarted("/nonexistent/sh", "No such file or directory"), result.end)
        assertEquals("", result.output)
    }

    @Test
    fun `the shell argv is the login shell with -lc and ComSpec on Windows`() {
        assertEquals(listOf("/bin/zsh", "-lc", "x"), Shells.argv("x", mapOf("SHELL" to "/bin/zsh")))
        assertEquals(listOf("/bin/zsh", "-lc", "x"), Shells.argv("x", emptyMap(), os = "Mac OS X"))
        assertEquals(listOf("/bin/zsh", "-lc", "x"), Shells.argv("x", mapOf("SHELL" to " "), os = "Mac OS X"))
        assertEquals(listOf("/bin/sh", "-lc", "x"), Shells.argv("x", emptyMap(), os = "Linux"))
        assertEquals(listOf("/bin/sh", "-lc", "x"), Shells.argv("x", mapOf("SHELL" to ""), os = "Linux"))
        assertEquals(listOf("C:\\W\\cmd.exe", "/c", "x"), Shells.argv("x", mapOf("ComSpec" to "C:\\W\\cmd.exe"), os = "Windows 11"))
        assertEquals(listOf("cmd.exe", "/c", "x"), Shells.argv("x", emptyMap(), os = "Windows 11"))
    }

    @Test
    fun `output arrives in batches while the command runs`() {
        unix()
        val batches = Collections.synchronizedList(ArrayList<String>())
        val result = run("printf a; sleep 0.3; printf b") { batches += it }
        assertEquals(listOf("a", "b"), batches)
        assertEquals("ab", result.output)
    }

    /** The runner's half; the frames' half, an empty `<bash-stdout>`, is [SessionManagerTest]'s test of the same name. */
    @Test
    fun `a command that prints nothing sends empty stdout`() {
        unix()
        val batches = Collections.synchronizedList(ArrayList<String>())
        val result = run("true") { batches += it }
        assertEquals("", result.output)
        assertEquals(ShellEnd.Exited(0), result.end)
        assertEquals(emptyList(), batches)
    }

    @Test
    fun `a stopped command still hands on the output read so far`() = runBlocking<Unit> {
        unix()
        val batches = Collections.synchronizedList(ArrayList<String>())
        // A batch never due on its own: only the end hands it on.
        val shell = ShellRunner(environment = { environment }, batchMillis = 60_000)
        val running = scope.async { shell.run("printf 'a\\342\\202'; sleep 30 & wait", dir) { batches += it } }
        withTimeout(10_000) { awaitSleeps() }
        delay(300) // the pump has read what was printed before the sleep
        running.cancelAndJoin()
        assertEquals(listOf("a\uFFFD"), batches, "the batch and the decoder's residue (an unfinished sequence) reach the caller")
    }

    @Test
    fun `nothing is kept after a cut`() {
        unix()
        // 99,999 characters, then U+1F600 (a surrogate pair) at the cap, then, in a later read, more: the pair is cut whole, and so is
        // all that follows, though one character would still fit.
        val result = run("head -c 99999 /dev/zero | tr '\\0' a; printf '\\360\\237\\230\\200'; sleep 0.1; printf bc")
        assertEquals("a".repeat(99_999) + "\n… (cut, 4 more characters)", result.output)
        assertEquals(4, result.cut)
    }
}
