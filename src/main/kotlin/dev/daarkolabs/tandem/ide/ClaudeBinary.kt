package dev.daarkolabs.tandem.ide

import com.intellij.openapi.util.SystemInfo
import dev.daarkolabs.tandem.session.cliEnvironment
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How much of `--version`'s output [ClaudeBinary.version] keeps; the rest is read and dropped. */
private const val OUTPUT_LIMIT: Int = 64 * 1024

/** How often [ClaudeBinary.version] looks for output while there is none. */
private const val POLL_MILLIS: Long = 20

/** What Windows runs by name when `PATHEXT` is unset: the extensions `cmd.exe` itself falls back to. */
private const val DEFAULT_PATHEXT: String = ".COM;.EXE;.BAT;.CMD"

/** Where npm's `claude.cmd` shim keeps the `claude.exe` it calls, below the shim's own directory (npm's global prefix). */
private const val NPM_CLAUDE_EXE: String = "node_modules/@anthropic-ai/claude-code/bin/claude.exe"

/** A `claude` [ClaudeBinary.detect] found, and [where]. */
internal data class Found(val path: String, val where: Where)

/** Where [ClaudeBinary.detect] found `claude`; [description] completes "<path>, …" on the settings page. */
internal enum class Where(val description: String) {
    OVERRIDE("the tandem.claudePath system property"),
    PATH("found on PATH"),
    INSTALL_LOCATION("found in a usual install location, not on PATH"),
}

/**
 * Finds the user's `claude` executable, and asks one for its version. Call off the EDT: everything here except [configured] touches
 * the file system.
 */
internal object ClaudeBinary {
    /** How long [version] waits for an answer by default. */
    val VERSION_TIMEOUT: Duration = 5.seconds

    /**
     * The `claude` sessions run: the path saved in Settings | Tools | Tandem when it can run (a saved npm `claude.cmd` as the
     * `claude.exe` it calls, [resolveShim]), else the one Tandem [detect]s.
     */
    fun find(): String? = configured()?.takeIf { isExecutable(it) }?.let { resolveShim(it) } ?: detect()?.path

    /**
     * The `claude` Tandem finds by itself, whatever the settings say: the `tandem.claudePath` system property (a test hook) when it is
     * executable, then each entry of the login shell's `PATH` ([ShellEnvironment], which an IDE started from the Dock or Finder does
     * not have; on Windows the IDE's own `Path`, [pathVariable]), then the CLI's usual [installLocations]. Blocks while the platform
     * is still loading the shell's environment.
     */
    fun detect(
        override: String? = System.getProperty("tandem.claudePath"),
        environment: Map<String, String> = ShellEnvironment.get(),
        home: String = System.getProperty("user.home"),
        windows: Boolean = SystemInfo.isWindows,
    ): Found? = find(override, pathVariable(environment)?.second.orEmpty(), installLocations(home, windows), windows)

    /**
     * The `PATH` of [environment] under whatever name it has, as its name to its value, or null when it has none. Windows names it
     * `Path`, and a plain map (unlike the JDK's and the platform's environment maps there) does not look it up ignoring case.
     */
    fun pathVariable(environment: Map<String, String>): Pair<String, String>? =
        environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) }?.let { it to environment.getValue(it) }

    /** The path saved in Settings | Tools | Tandem, or null when none is. Reads only the settings, which store it trimmed. */
    fun configured(): String? = TandemSettings.getInstance().state.claudePath

    /**
     * Whether [path] is a file that can be run. A directory is not, though its execute bit (the right to search it) may be set. On
     * Windows ([windows]) `canExecute` is true for every file the user can read, so the name must also end in an extension of
     * [pathExt] (`PATHEXT`; `.COM;.EXE;.BAT;.CMD` when it is unset or blank), compared ignoring case: `claude.ps1`, npm's POSIX
     * `claude` shim and `fake-claude.py` are no programs to `CreateProcess` (error 193).
     */
    fun isExecutable(path: String, windows: Boolean = SystemInfo.isWindows, pathExt: String? = System.getenv("PATHEXT")): Boolean {
        val file = File(path)
        if (!file.isFile || !file.canExecute()) return false
        if (!windows) return true
        val extensions = (pathExt?.takeIf { it.isNotBlank() } ?: DEFAULT_PATHEXT).split(';').filter { it.isNotBlank() }
        return extensions.any { file.name.endsWith(it, ignoreCase = true) }
    }

    /**
     * Where `claude` is usually installed, for a `PATH` that lacks it: the CLI's own installers (the native one's `~/.local/bin`, which
     * on Windows the installer does not put on the `PATH`; the older local install's `~/.claude/local`), then on Windows npm's global
     * prefix (`%APPDATA%\npm` from [appData], else `<home>\AppData\Roaming\npm`, npm's own default), where `claude.cmd` lands; elsewhere
     * Homebrew's bin on Apple silicon and on Intel (also npm's global bin for a Node from Homebrew or nodejs.org), then the global bins
     * of npm with the user prefix its documentation suggests, of Bun and of Volta.
     */
    fun installLocations(home: String, windows: Boolean, appData: String? = System.getenv("APPDATA")): List<String> {
        val installers = listOf("$home/.local/bin", "$home/.claude/local")
        if (windows) return installers + "${appData?.takeIf { it.isNotBlank() } ?: "$home/AppData/Roaming"}/npm"
        return installers + listOf("/opt/homebrew/bin", "/usr/local/bin", "$home/.npm-global/bin", "$home/.bun/bin", "$home/.volta/bin")
    }

    /**
     * [override] wins when it is executable; then each `PATH` entry; then the [locations], in their order. On Windows ([windows]) the
     * names are `claude.exe` then `claude.cmd`, never the bare `claude` (npm's `#!/bin/sh` shim, which Windows cannot run); a `PATH`
     * entry loses the double quotes an installer may have written around it; and a found `claude.cmd` is the `claude.exe` it calls
     * when that exists ([resolveShim]).
     */
    fun find(override: String?, path: String, locations: List<String>, windows: Boolean): Found? {
        override?.takeIf { isExecutable(it, windows) }?.let { return Found(resolveShim(it, windows), Where.OVERRIDE) }
        val names = if (windows) listOf("claude.exe", "claude.cmd") else listOf("claude")
        val dirs = path.split(File.pathSeparator).filter { it.isNotBlank() }
            .map { (if (windows) it.removeSurrounding("\"") else it) to Where.PATH } +
            locations.map { it to Where.INSTALL_LOCATION }
        return dirs.asSequence()
            .flatMap { (dir, where) -> names.asSequence().map { File(dir, it) to where } }
            .firstOrNull { (file, _) -> file.isFile && file.canExecute() }
            ?.let { (file, where) -> Found(resolveShim(file.path, windows), where) }
    }

    /**
     * What to run for [path]: on Windows ([windows]) a `claude.cmd` becomes its [npmTarget] when there is one, so that the process
     * Tandem holds is the CLI itself and not `cmd.exe` (whose `destroy` leaves `claude.exe` and a `conhost.exe` running), and so that no
     * argument has to survive `cmd.exe`'s quoting; anything else, and a `.cmd` with no `claude.exe` in npm's place (pnpm's, yarn's or
     * nvm-windows' shim), is run as it is, and a stop kills its whole tree.
     */
    internal fun resolveShim(path: String, windows: Boolean = SystemInfo.isWindows): String {
        if (!windows) return path
        val file = File(path)
        if (!file.name.equals("claude.cmd", ignoreCase = true)) return path
        return npmTarget(file)?.path ?: path
    }

    /** The `claude.exe` npm's [cmd] shim calls, `<its directory>/node_modules/@anthropic-ai/claude-code/bin/claude.exe`, if it is a file. */
    fun npmTarget(cmd: File): File? = File(cmd.absoluteFile.parentFile, NPM_CLAUDE_EXE).takeIf { it.isFile }

    /**
     * The settings page's status line for a saved [path] that [isExecutable] refuses: on Windows ([windows]) it names what would run,
     * since the file may well exist and be readable.
     */
    fun notRunnableText(path: String, windows: Boolean = SystemInfo.isWindows): String =
        if (windows) "$path is not something Windows can run (no .exe, .cmd or .bat)" else "Configured path is not executable"

    /**
     * The environment to run [binary] in: [environment] with the binary's directory at the end of its `PATH`, unless the `PATH` has it
     * already (ignoring case on Windows, [windows]). A `claude` found in an install location ([Where.INSTALL_LOCATION]) or saved in the
     * settings may be a script that npm, Volta or Bun installed, `#!/usr/bin/env node`, with its `node` beside it; with launchd's
     * `PATH`, which has no `node`, it would exit with 127. At the end, so that the user's own `node`, `npm` and system tools still win
     * for everything the CLI runs. Unchanged when there is no `PATH`, for a relative [binary], and for a directory whose name holds the
     * path separator, which a `PATH` cannot list.
     */
    fun environmentFor(binary: String, environment: Map<String, String>, windows: Boolean = SystemInfo.isWindows): Map<String, String> {
        val file = File(binary)
        if (!file.isAbsolute) return environment
        val dir = file.parent ?: return environment
        if (File.pathSeparator in dir) return environment
        // Windows names it Path; whatever its case, keep the name the environment uses.
        val name = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: return environment
        val entries = environment.getValue(name).split(File.pathSeparator).filter { it.isNotBlank() }
        if (entries.any { File(it).path.equals(dir, ignoreCase = windows) }) return environment
        return environment + (name to (entries + dir).joinToString(File.pathSeparator))
    }

    /**
     * Runs `[binary] --version` in the environment a session's CLI gets ([cliEnvironment] of the login shell's [environment], with
     * [environmentFor]'s `PATH`) and
     * says what came of it: the first line it printed, or how it failed. Returns within about [timeout] whatever the process does: its
     * output is polled, never waited on, so not even a child that keeps the output open after the process has exited holds it up.
     * Never throws for the process (an I/O failure is an answer too). When this returns, also when the caller is cancelled, the process
     * and its descendants at that moment are destroyed. Not a child that outlived it: once the process has exited, the system gives
     * its orphans another parent (init, or a subreaper), so they are no longer its descendants and keep running. Call on
     * `Dispatchers.IO`.
     */
    suspend fun version(
        binary: String,
        timeout: Duration = VERSION_TIMEOUT,
        environment: Map<String, String> = ShellEnvironment.get(),
    ): String {
        val process = try {
            ProcessBuilder(binary, "--version").redirectErrorStream(true).apply {
                environment().run {
                    clear()
                    putAll(cliEnvironment(environmentFor(binary, environment)))
                }
            }.start()
        } catch (e: IOException) {
            return "Could not run it: ${e.message}"
        }
        try {
            process.outputStream.close() // it is given no input
            val output = withTimeoutOrNull(timeout) { outputOf(process) } ?: return "No answer within $timeout"
            val first = output.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
            val code = process.exitValue()
            return when {
                code != 0 -> "Exited with code $code" + (first?.let { ": $it" } ?: "")
                first == null -> "It printed nothing"
                else -> first
            }
        } catch (e: IOException) {
            return "Could not read its answer: ${e.message}"
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    /**
     * Everything [process] printed, once it has exited: read as it comes, never by a call that blocks, so that cancelling stops this at
     * once. Only what [java.io.InputStream.available] says is there is read: a read blocked on a pipe could be neither cancelled nor
     * woken by closing the stream. Keeps the first [limit] bytes and reads on past them, so the process never stalls on a full pipe.
     */
    internal suspend fun outputOf(process: Process, limit: Int = OUTPUT_LIMIT): String {
        val input = process.inputStream
        val kept = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val exited = !process.isAlive // before looking at the pipe, so that nothing written before the exit is missed
            val available = input.available()
            when {
                available > 0 -> {
                    val read = input.read(buffer, 0, minOf(available, buffer.size))
                    if (read > 0) kept.write(buffer, 0, minOf(read, limit - kept.size()).coerceAtLeast(0))
                    yield() // a process that keeps printing must not keep the timeout from ending this
                }
                exited -> return kept.toString(Charsets.UTF_8)
                else -> delay(POLL_MILLIS)
            }
        }
    }
}
