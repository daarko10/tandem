package dev.daarkolabs.tandem.ide

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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

    /** Install locations under [tmp], standing in for the real ones, which a test must never find a `claude` in. */
    private val locations: List<String> get() = listOf("$tmp/home/.local/bin", "$tmp/home/.claude/local")

    @Test
    fun `nothing found when PATH and the install locations are empty`() {
        assertNull(ClaudeBinary.find(override = null, path = pathOf("a", "b"), locations = locations, windows = false))
    }

    @Test
    fun `PATH order wins, then the install locations in their order`() {
        val second = executable("b")
        val claudeLocal = executable("home/.claude/local")
        assertEquals(Found(second, Where.PATH), ClaudeBinary.find(null, pathOf("a", "b"), locations, windows = false))
        assertEquals(Found(claudeLocal, Where.INSTALL_LOCATION), ClaudeBinary.find(null, "$tmp/a", locations, windows = false))
        val localBin = executable("home/.local/bin")
        assertEquals(Found(localBin, Where.INSTALL_LOCATION), ClaudeBinary.find(null, "$tmp/a", locations, windows = false))
    }

    @Test
    fun `an executable override wins and a missing one is ignored`() {
        val onPath = executable("a")
        val custom = executable("custom", "my-claude")
        assertEquals(Found(custom, Where.OVERRIDE), ClaudeBinary.find(custom, "$tmp/a", locations, windows = false))
        assertEquals(Found(onPath, Where.PATH), ClaudeBinary.find("$tmp/missing", "$tmp/a", locations, windows = false))
    }

    @Test
    fun `non-executable files are skipped`() {
        // Windows has no execute bit (canExecute is true for every readable file): there, what cannot run is a name outside PATHEXT,
        // `on Windows a file without a PATHEXT extension cannot run`.
        assumeTrue(!windows, "Windows has no execute bit; its counterpart is the PATHEXT test")
        File(tmp.toFile(), "a").apply { mkdirs() }.resolve("claude").writeText("not executable")
        assertNull(ClaudeBinary.find(null, "$tmp/a", locations, windows = false))
    }

    @Test
    fun `a directory is not executable, though it can be searched`() {
        val dir = File(tmp.toFile(), "claude-dir").apply { mkdirs() }
        assertTrue(dir.canExecute(), "the permission to search a directory is its execute bit")
        assertFalse(ClaudeBinary.isExecutable(dir.path))
        val onPath = executable("a")
        val found = ClaudeBinary.find(dir.path, "$tmp/a", locations, windows = false)
        assertEquals(Found(onPath, Where.PATH), found, "a directory as the override is ignored")
    }

    @Test
    fun `the install locations are the CLI's own installers', then Homebrew's, then package managers' global bins`() {
        assertEquals(
            listOf(
                "/u/.local/bin", "/u/.claude/local", "/opt/homebrew/bin", "/usr/local/bin", "/u/.npm-global/bin", "/u/.bun/bin",
                "/u/.volta/bin",
            ),
            ClaudeBinary.installLocations(home = "/u", windows = false),
        )
        assertEquals(
            listOf("/u/.local/bin", "/u/.claude/local", "/u/AppData/Roaming/npm"),
            ClaudeBinary.installLocations(home = "/u", windows = true, appData = null),
        )
    }

    @Test
    fun `the install locations on Windows end with npm's prefix, from APPDATA or the home's default`() {
        val expected = listOf("/u/.local/bin", "/u/.claude/local", "/u/AppData/Roaming/npm")
        assertEquals(expected, ClaudeBinary.installLocations("/u", windows = true, appData = "/u/AppData/Roaming"))
        assertEquals(expected, ClaudeBinary.installLocations("/u", windows = true, appData = null))
        assertEquals(expected, ClaudeBinary.installLocations("/u", windows = true, appData = ""))
        assertEquals(listOf("/u/.local/bin", "/u/.claude/local", "/r/npm"), ClaudeBinary.installLocations("/u", windows = true, appData = "/r"))
    }

    @Test
    fun `on Windows PATH is read under whatever name the environment gives it`() {
        val exe = executable("win-bin", "claude.exe")
        val environment = mapOf("Path" to "$tmp/win-bin", "HOME" to "/u")
        assertEquals("Path" to "$tmp/win-bin", ClaudeBinary.pathVariable(environment))
        assertNull(ClaudeBinary.pathVariable(mapOf("HOME" to "/u")))
        assertEquals(Found(exe, Where.PATH), ClaudeBinary.detect(override = null, environment = environment, home = "$tmp/home", windows = true))
    }

    @Test
    fun `on Windows the exe then the cmd shim are taken and the bare claude never`() {
        val exe = executable("all", "claude.exe")
        executable("all", "claude.cmd")
        executable("all", "claude")
        assertEquals(Found(exe, Where.PATH), ClaudeBinary.find(null, "$tmp/all", locations, windows = true))
        val cmd = executable("cmd-only", "claude.cmd")
        executable("cmd-only", "claude")
        assertEquals(Found(cmd, Where.PATH), ClaudeBinary.find(null, "$tmp/cmd-only", locations, windows = true))
        // npm's POSIX shim, `#!/bin/sh`: CreateProcess cannot run it (error 193).
        executable("bare", "claude")
        assertNull(ClaudeBinary.find(null, "$tmp/bare", locations, windows = true))
    }

    @Test
    fun `on Windows a file without a PATHEXT extension cannot run`() {
        assertFalse(ClaudeBinary.isExecutable(executable("w", "claude.ps1"), windows = true, pathExt = null))
        assertFalse(ClaudeBinary.isExecutable(executable("w", "fake-claude.py"), windows = true, pathExt = null))
        assertFalse(ClaudeBinary.isExecutable(executable("w", "claude"), windows = true, pathExt = null))
        assertTrue(ClaudeBinary.isExecutable(executable("w", "claude.cmd"), windows = true, pathExt = null))
        assertTrue(ClaudeBinary.isExecutable(executable("w", "x.py"), windows = true, pathExt = ".PY;.EXE"))
        assertFalse(ClaudeBinary.isExecutable(executable("w", "claude.cmd"), windows = true, pathExt = ".PY;.EXE"))
        // Outside Windows the extension says nothing.
        assertTrue(ClaudeBinary.isExecutable(executable("w", "claude"), windows = false, pathExt = ".EXE"))
    }

    @Test
    fun `on Windows extensions compare ignoring case`() {
        assertTrue(ClaudeBinary.isExecutable(executable("c", "Claude.EXE"), windows = true, pathExt = ".exe;.cmd"))
        assertTrue(ClaudeBinary.isExecutable(executable("c", "claude.Cmd"), windows = true, pathExt = ".EXE;.CMD"))
    }

    @Test
    fun `on Windows a quoted PATH entry is searched without its quotes`() {
        // Installers write `"C:\Program Files\nodejs"` into PATH, and Windows' own `where` accepts it.
        val exe = executable("quoted", "claude.exe")
        assertEquals(Found(exe, Where.PATH), ClaudeBinary.find(null, "\"$tmp/quoted\"", locations, windows = true))
    }

    @Test
    fun `an npm cmd shim is run as the exe it calls`() {
        val cmd = executable("npm", "claude.cmd")
        val exe = executable("npm/node_modules/@anthropic-ai/claude-code/bin", "claude.exe")
        assertEquals(File(exe), ClaudeBinary.npmTarget(File(cmd)))
        assertEquals(Found(exe, Where.PATH), ClaudeBinary.find(null, "$tmp/npm", locations, windows = true))
        assertEquals(exe, ClaudeBinary.resolveShim(cmd, windows = true), "a saved claude.cmd too")
        assertEquals(cmd, ClaudeBinary.resolveShim(cmd, windows = false))
    }

    @Test
    fun `a cmd shim without the exe beside it is run as it is`() {
        // pnpm's, yarn's and nvm-windows' shims have no claude.exe in that place: the shim runs, and a stop kills its tree.
        val cmd = executable("pnpm", "claude.cmd")
        assertNull(ClaudeBinary.npmTarget(File(cmd)))
        assertEquals(Found(cmd, Where.PATH), ClaudeBinary.find(null, "$tmp/pnpm", locations, windows = true))
        assertEquals(cmd, ClaudeBinary.resolveShim(cmd, windows = true))
    }

    @Test
    fun `the not-runnable text names the Windows extensions`() {
        assertEquals(
            "C:\\x\\claude.ps1 is not something Windows can run (no .exe, .cmd or .bat)",
            ClaudeBinary.notRunnableText("C:\\x\\claude.ps1", windows = true),
        )
        assertEquals("Configured path is not executable", ClaudeBinary.notRunnableText("/x/claude", windows = false))
    }

    @Test
    fun `detect searches the PATH of the environment it is given, not the IDE's own`() {
        val shellBin = executable("shell-bin")
        val shell = mapOf("PATH" to "$tmp/shell-bin")
        val found = ClaudeBinary.detect(override = null, environment = shell, home = "$tmp/home", windows = false)
        assertEquals(Found(shellBin, Where.PATH), found)
    }

    @Test
    fun `detect without arguments searches the login shell's PATH`() {
        // Under the running system's own rules: on Windows only claude.exe or claude.cmd count, never the bare claude.
        val shellBin = executable("login-shell-bin", if (windows) "claude.exe" else "claude")
        val hook = System.getProperty("tandem.claudePath")
        System.clearProperty("tandem.claudePath")
        ShellEnvironment.source = { mapOf("PATH" to "$tmp/login-shell-bin") }
        try {
            assertEquals(Found(shellBin, Where.PATH), ClaudeBinary.detect())
        } finally {
            ShellEnvironment.reset()
            if (hook != null) System.setProperty("tandem.claudePath", hook)
        }
    }

    @Test
    fun `where it was found reads as a phrase`() {
        assertEquals("found on PATH", Where.PATH.description)
        assertEquals("found in a usual install location, not on PATH", Where.INSTALL_LOCATION.description)
        assertEquals("the tandem.claudePath system property", Where.OVERRIDE.description)
    }

    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows")

    /** A shell script named [name] that runs [sh], or on Windows `<name>.cmd` that runs [cmd] (`@echo off` first). */
    private fun script(name: String, sh: String, cmd: String): String =
        if (windows) File(tmp.toFile(), "$name.cmd").apply { writeText("@echo off\r\n${cmd.replace("\n", "\r\n")}\r\n") }.path
        else File(tmp.toFile(), name).apply { writeText("#!/bin/sh\n$sh\n"); setExecutable(true) }.path

    /** [names] joined into a `PATH` value, each an absolute path under the temporary directory. */
    private fun pathOf(vararg names: String): String = names.joinToString(File.pathSeparator) { File(tmp.toFile(), it).path }

    @Test
    fun `the directory of claude goes to the end of PATH unless PATH has it`() {
        val claude = File(tmp.toFile(), ".volta/bin/claude").path
        // At the end: the user's own node, npm and system tools keep winning; launchd's PATH has no node for it to shadow anyway.
        assertEquals(
            mapOf("PATH" to pathOf("usr/bin", "bin", ".volta/bin"), "HOME" to "/u"),
            ClaudeBinary.environmentFor(claude, mapOf("PATH" to pathOf("usr/bin", "bin"), "HOME" to "/u"), windows = false),
        )
        val onPath = mapOf("PATH" to pathOf("usr/bin", ".volta/bin"))
        assertEquals(onPath, ClaudeBinary.environmentFor(claude, onPath, windows = false))
    }

    @Test
    fun `on Windows PATH is compared ignoring case, under whatever name the environment gives it`() {
        val claude = File(tmp.toFile(), "Programs/claude.exe").path
        val onPath = mapOf("Path" to File(tmp.toFile(), "PROGRAMS").path)
        assertEquals(onPath, ClaudeBinary.environmentFor(claude, onPath, windows = true))
        assertEquals(
            mapOf("Path" to pathOf("bin", "Programs")),
            ClaudeBinary.environmentFor(claude, mapOf("Path" to pathOf("bin")), windows = true),
        )
    }

    @Test
    fun `PATH is left alone when there is none, or for a relative claude, or a directory PATH cannot hold`() {
        val noPath = mapOf("HOME" to "/u")
        assertEquals(noPath, ClaudeBinary.environmentFor(File(tmp.toFile(), "bin/claude").path, noPath, windows = false))
        val path = mapOf("PATH" to pathOf("usr/bin"))
        assertEquals(path, ClaudeBinary.environmentFor("bin${File.separator}claude", path, windows = false))
        assertEquals(path, ClaudeBinary.environmentFor(".${File.separator}claude", path, windows = false))
        assertEquals(path, ClaudeBinary.environmentFor("claude", path, windows = false))
        val split = File(File(tmp.toFile(), "a${File.pathSeparator}b"), "claude").path
        assertEquals(path, ClaudeBinary.environmentFor(split, path, windows = false))
    }

    /**
     * A `claude` in a directory of its own, run the way npm, Volta or Bun install it: by `#!/usr/bin/env` and an interpreter beside
     * it (`node` for them, here a script that prints a version), which a `PATH` without that directory does not find.
     */
    private fun claudeWithInterpreterBeside(): String {
        val dir = File(tmp.toFile(), "install-location").apply { mkdirs() }
        if (windows) {
            // npm's claude.cmd calls the node beside it by its own directory, %~dp0.
            dir.resolve("tandem-test-node.cmd").writeText("@echo 2.0.0 (Claude Code)\r\n")
            return dir.resolve("claude.cmd").apply { writeText("@call \"%~dp0tandem-test-node.cmd\" %*\r\n") }.path
        }
        dir.resolve("tandem-test-node").apply { writeText("#!/bin/sh\necho '2.0.0 (Claude Code)'\n"); setExecutable(true) }
        return dir.resolve("claude").apply { writeText("#!/usr/bin/env tandem-test-node\n"); setExecutable(true) }.path
    }

    @Test
    fun `version finds an interpreter installed beside claude`() = runBlocking {
        val launchdPath = mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin")
        assertEquals("2.0.0 (Claude Code)", ClaudeBinary.version(claudeWithInterpreterBeside(), environment = launchdPath))
    }

    @Test
    fun `version is the first line the executable prints`() = runBlocking {
        assertEquals("2.1.301 (Claude Code)", ClaudeBinary.version(script("claude", "echo; echo '2.1.301 (Claude Code)'; echo more", "echo.\necho 2.1.301 (Claude Code)\necho more")))
        // It runs as a session's CLI does, with Tandem's entrypoint.
        assertEquals("sdk-tandem", ClaudeBinary.version(script("env", "echo \"\$CLAUDE_CODE_ENTRYPOINT\"", "echo %CLAUDE_CODE_ENTRYPOINT%")))
    }

    @Test
    fun `version runs in the environment it is given, as a session's CLI would get it`() = runBlocking {
        val probe = script(
            "probe",
            "echo \"\$TANDEM_PROBE \$CLAUDE_CODE_ENTRYPOINT \${CLAUDECODE:-none}\"",
            // cmd prints an unset variable's name, not nothing.
            "if defined CLAUDECODE (echo %TANDEM_PROBE% %CLAUDE_CODE_ENTRYPOINT% %CLAUDECODE%) else (echo %TANDEM_PROBE% %CLAUDE_CODE_ENTRYPOINT% none)",
        )
        val environment = mapOf("TANDEM_PROBE" to "from-shell", "CLAUDECODE" to "1")
        assertEquals("from-shell sdk-tandem none", ClaudeBinary.version(probe, environment = environment))
    }

    @Test
    fun `version runs in the login shell's environment by default`() = runBlocking {
        ShellEnvironment.source = { mapOf("TANDEM_PROBE" to "login-shell") }
        try {
            assertEquals("login-shell", ClaudeBinary.version(script("probe", "echo \"\$TANDEM_PROBE\"", "echo %TANDEM_PROBE%")))
        } finally {
            ShellEnvironment.reset()
        }
    }

    @Test
    fun `version says how a run failed`() = runBlocking {
        assertEquals("Exited with code 3: unknown option", ClaudeBinary.version(script("bad", "echo 'unknown option' >&2; exit 3", ">&2 echo unknown option\nexit /b 3")))
        assertEquals("It printed nothing", ClaudeBinary.version(script("quiet", "exit 0", "exit /b 0")))
        val missing = ClaudeBinary.version("$tmp/missing")
        assertTrue(missing.startsWith("Could not run it: "), missing)
    }

    @Test
    fun `version stops an executable that does not answer`() = runBlocking {
        assumeTrue(!windows, "finds its sleepers by their arguments, which Windows does not give")
        // The script's child sleeps with an argument no other process has, so that it can be found.
        val hanging = script("hanging", "sleep 41.25", "")
        assertEquals("No answer within 500ms", ClaudeBinary.version(hanging, timeout = 500.milliseconds))
        fun sleepers(): List<ProcessHandle> =
            ProcessHandle.allProcesses().filter { it.isAlive && "41.25" in it.info().arguments().orElse(emptyArray()) }.toList()
        withTimeout(10_000) { while (sleepers().isNotEmpty()) delay(20) }
    }

    @Test
    fun `version does not wait for output that something else keeps open`() = runBlocking {
        assumeTrue(!windows, "finds its sleepers by their arguments, which Windows does not give")
        // It exits at once, but leaves a child behind that holds its output open: the output never ends while that child lives.
        val leaky = script("leaky", "sleep 42.75 &\nexit 0", "")
        fun sleepers(): List<ProcessHandle> =
            ProcessHandle.allProcesses().filter { it.isAlive && "42.75" in it.info().arguments().orElse(emptyArray()) }.toList()
        try {
            val answer = withTimeout(3_000) { ClaudeBinary.version(leaky, timeout = 10.seconds) }
            assertEquals("It printed nothing", answer)
        } finally {
            sleepers().forEach { it.destroyForcibly() } // no longer the script's child, so version leaves it alone
        }
    }

    /** A process that has exited and printed [output], which a test reads without running anything. */
    private class ExitedProcess(output: ByteArray) : Process() {
        val stdout: ByteArrayInputStream = ByteArrayInputStream(output)

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun isAlive(): Boolean = false

        override fun destroy() {}
    }

    @Test
    fun `the output read keeps the first bytes up to the limit and reads past them`() = runBlocking {
        val output = ByteArray(3 * 8192 + 5) { i -> ('a' + i % 26).code.toByte() }
        val process = ExitedProcess(output)
        val kept = ClaudeBinary.outputOf(process, limit = 10_000)
        assertEquals(String(output, 0, 10_000, Charsets.UTF_8), kept)
        assertEquals(0, process.stdout.available(), "everything was read, so the process never stalls on a full pipe")
    }

    @Test
    fun `the output read keeps all of an output shorter than the limit`() = runBlocking {
        assertEquals("2.1.301 (Claude Code)\n", ClaudeBinary.outputOf(ExitedProcess("2.1.301 (Claude Code)\n".toByteArray())))
    }
}
