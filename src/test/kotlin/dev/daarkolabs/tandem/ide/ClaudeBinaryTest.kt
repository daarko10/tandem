package dev.daarkolabs.tandem.ide

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClaudeBinaryTest {
    @TempDir
    lateinit var tmp: Path

    private fun executable(dir: String, name: String = "claude"): String =
        File(tmp.toFile(), dir).apply { mkdirs() }.resolve(name).apply { writeText("#!/bin/sh\n"); setExecutable(true) }.path

    @Test
    fun `nothing found when PATH and the usual locations are empty`() {
        assertNull(ClaudeBinary.find(override = null, path = "$tmp/a:$tmp/b", home = "$tmp/home", windows = false))
    }

    @Test
    fun `PATH order wins, then the install locations under home`() {
        val second = executable("b")
        val home = executable("home/.local/bin")
        assertEquals(second, ClaudeBinary.find(null, "$tmp/a:$tmp/b", "$tmp/home", windows = false))
        assertEquals(home, ClaudeBinary.find(null, "$tmp/a", "$tmp/home", windows = false))
    }

    @Test
    fun `an executable override wins and a missing one is ignored`() {
        val onPath = executable("a")
        val custom = executable("custom", "my-claude")
        assertEquals(custom, ClaudeBinary.find(custom, "$tmp/a", "$tmp/home", windows = false))
        assertEquals(onPath, ClaudeBinary.find("$tmp/missing", "$tmp/a", "$tmp/home", windows = false))
    }

    @Test
    fun `non-executable files are skipped`() {
        File(tmp.toFile(), "a").apply { mkdirs() }.resolve("claude").writeText("not executable")
        assertNull(ClaudeBinary.find(null, "$tmp/a", "$tmp/home", windows = false))
    }

    @Test
    fun `a directory is not executable, though it can be searched`() {
        val dir = File(tmp.toFile(), "claude-dir").apply { mkdirs() }
        assertTrue(dir.canExecute(), "the permission to search a directory is its execute bit")
        assertFalse(ClaudeBinary.isExecutable(dir.path))
        val onPath = executable("a")
        assertEquals(onPath, ClaudeBinary.find(dir.path, "$tmp/a", "$tmp/home", windows = false), "a directory as the override is ignored")
    }

    /** A shell script named [name] that runs [body]. */
    private fun script(name: String, body: String): String =
        File(tmp.toFile(), name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }.path

    @Test
    fun `version is the first line the executable prints`() = runBlocking {
        assertEquals("2.1.301 (Claude Code)", ClaudeBinary.version(script("claude", "echo; echo '2.1.301 (Claude Code)'; echo more")))
        // It runs as a session's CLI does, with Tandem's entrypoint.
        assertEquals("sdk-tandem", ClaudeBinary.version(script("env", "echo \"\$CLAUDE_CODE_ENTRYPOINT\"")))
    }

    @Test
    fun `version says how a run failed`() = runBlocking {
        assertEquals("Exited with code 3: unknown option", ClaudeBinary.version(script("bad", "echo 'unknown option' >&2; exit 3")))
        assertEquals("It printed nothing", ClaudeBinary.version(script("quiet", "exit 0")))
        val missing = ClaudeBinary.version("$tmp/missing")
        assertTrue(missing.startsWith("Could not run it: "), missing)
    }

    @Test
    fun `version stops an executable that does not answer`() = runBlocking {
        // The script's child sleeps with an argument no other process has, so that it can be found.
        val hanging = script("hanging", "sleep 41.25")
        assertEquals("No answer within 500ms", ClaudeBinary.version(hanging, timeout = 500.milliseconds))
        fun sleepers(): List<ProcessHandle> =
            ProcessHandle.allProcesses().filter { it.isAlive && "41.25" in it.info().arguments().orElse(emptyArray()) }.toList()
        withTimeout(10_000) { while (sleepers().isNotEmpty()) delay(20) }
    }

    @Test
    fun `version does not wait for output that something else keeps open`() = runBlocking {
        // It exits at once, but leaves a child behind that holds its output open: the output never ends while that child lives.
        val leaky = script("leaky", "sleep 42.75 &\nexit 0")
        fun sleepers(): List<ProcessHandle> =
            ProcessHandle.allProcesses().filter { it.isAlive && "42.75" in it.info().arguments().orElse(emptyArray()) }.toList()
        try {
            val answer = withTimeout(3_000) { ClaudeBinary.version(leaky, timeout = 10.seconds) }
            assertEquals("It printed nothing", answer)
        } finally {
            sleepers().forEach { it.destroyForcibly() } // no longer the script's child, so version leaves it alone
        }
    }
}
