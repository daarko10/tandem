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

    /** The `claude` sessions run: the path saved in Settings | Tools | Tandem when it can run, else the one Tandem [detect]s. */
    fun find(): String? = configured()?.takeIf(::isExecutable) ?: detect()?.path

    /**
     * The `claude` Tandem finds by itself, whatever the settings say: the `tandem.claudePath` system property (a test hook) when it is
     * executable, then each entry of the login shell's `PATH` ([ShellEnvironment], which an IDE started from the Dock or Finder does
     * not have), then the CLI's usual [installLocations]. Blocks while the platform is still loading the shell's environment.
     */
    fun detect(
        override: String? = System.getProperty("tandem.claudePath"),
        environment: Map<String, String> = ShellEnvironment.get(),
        home: String = System.getProperty("user.home"),
        windows: Boolean = SystemInfo.isWindows,
    ): Found? = find(override, environment["PATH"].orEmpty(), installLocations(home, windows), windows)

    /** The path saved in Settings | Tools | Tandem, trimmed, or null when none is. Reads only the settings. */
    fun configured(): String? = TandemSettings.getInstance().state.claudePath?.trim()?.ifEmpty { null }

    /** Whether [path] is a file that can be run. A directory is not, though its execute bit (the right to search it) may be set. */
    fun isExecutable(path: String): Boolean = File(path).let { it.isFile && it.canExecute() }

    /**
     * Where `claude` is usually installed, for a `PATH` that lacks it: the CLI's own installers (the native one's `~/.local/bin`, the
     * older local install's `~/.claude/local`), then, outside Windows, Homebrew's bin on Apple silicon and on Intel (also npm's global
     * bin for a Node from Homebrew or nodejs.org), then the global bins of npm with the user prefix its documentation suggests, of Bun
     * and of Volta.
     */
    fun installLocations(home: String, windows: Boolean): List<String> {
        val installers = listOf("$home/.local/bin", "$home/.claude/local")
        if (windows) return installers
        return installers + listOf("/opt/homebrew/bin", "/usr/local/bin", "$home/.npm-global/bin", "$home/.bun/bin", "$home/.volta/bin")
    }

    /** [override] wins when it is executable; then each `PATH` entry; then the [locations], in their order. */
    fun find(override: String?, path: String, locations: List<String>, windows: Boolean): Found? {
        override?.takeIf(::isExecutable)?.let { return Found(it, Where.OVERRIDE) }
        val names = if (windows) listOf("claude.exe", "claude.cmd", "claude") else listOf("claude")
        val dirs = path.split(File.pathSeparator).filter { it.isNotBlank() }.map { it to Where.PATH } +
            locations.map { it to Where.INSTALL_LOCATION }
        return dirs.asSequence()
            .flatMap { (dir, where) -> names.asSequence().map { File(dir, it) to where } }
            .firstOrNull { (file, _) -> file.isFile && file.canExecute() }
            ?.let { (file, where) -> Found(file.path, where) }
    }

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
