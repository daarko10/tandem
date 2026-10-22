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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    private suspend fun awaitSleep() {
        while (ownSleeps().isEmpty()) delay(20)
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
        withTimeout(10_000) { awaitSleep() }
        val started = System.currentTimeMillis()
        running.cancelAndJoin()
        assertTrue(System.currentTimeMillis() - started < 2_000, "the job ends at once")
        assertEquals(emptyList(), ownSleeps().map { it.pid() })
    }

    @Test
    fun `the cap kills after the timeout`() {
        unix()
        val started = System.currentTimeMillis()
        val result = run("sleep 5", runner(timeoutMillis = 200))
        assertEquals(ShellEnd.TimedOut, result.end)
        assertTrue(System.currentTimeMillis() - started < 2_000)
        assertEquals(emptyList(), ownSleeps().map { it.pid() })
    }

    @Test
    fun `a background child that keeps the output open does not hold the row`() {
        unix()
        val started = System.currentTimeMillis()
        val result = run("sleep 30 & echo x")
        assertEquals(ShellEnd.Exited(0), result.end)
        assertEquals("x\n", result.output)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test
    fun `killAllNow destroys a running command's tree`() = runBlocking<Unit> {
        unix()
        val shell = runner()
        val running = scope.async { shell.run("sleep 30", dir) {} }
        withTimeout(10_000) { awaitSleep() }
        val started = System.currentTimeMillis()
        shell.killAllNow()
        withTimeout(2_000) { running.await() }
        assertTrue(System.currentTimeMillis() - started < 2_000)
        assertEquals(emptyList(), ownSleeps().map { it.pid() })
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

    @Test
    fun `a command that prints nothing sends empty stdout`() {
        unix()
        val batches = Collections.synchronizedList(ArrayList<String>())
        val result = run("true") { batches += it }
        assertEquals("", result.output)
        assertEquals(ShellEnd.Exited(0), result.end)
        assertEquals(emptyList(), batches)
        assertIs<ShellEnd.Exited>(result.end)
    }
}
