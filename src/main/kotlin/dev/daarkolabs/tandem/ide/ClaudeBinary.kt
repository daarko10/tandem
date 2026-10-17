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

/**
 * Finds the user's `claude` executable, and asks one for its version. Call off the EDT: everything here except [configured] touches
 * the file system.
 */
internal object ClaudeBinary {
    /** How long [version] waits for an answer by default. */
    val VERSION_TIMEOUT: Duration = 5.seconds

    /** The `claude` sessions run: the path saved in Settings | Tools | Tandem when it can run, else the one Tandem [detect]s. */
    fun find(): String? = configured()?.takeIf(::isExecutable) ?: detect()

    /**
     * The `claude` Tandem finds by itself, whatever the settings say: the `tandem.claudePath` system property (a test hook) when it is
     * executable, then each `PATH` entry, then the CLI's usual install locations.
     */
    fun detect(): String? = find(
        override = System.getProperty("tandem.claudePath"),
        path = System.getenv("PATH").orEmpty(),
        home = System.getProperty("user.home"),
        windows = SystemInfo.isWindows,
    )

    /** The path saved in Settings | Tools | Tandem, trimmed, or null when none is. Reads only the settings. */
    fun configured(): String? = TandemSettings.getInstance().state.claudePath?.trim()?.ifEmpty { null }

    /** Whether [path] is a file that can be run. A directory is not, though its execute bit (the right to search it) may be set. */
    fun isExecutable(path: String): Boolean = File(path).let { it.isFile && it.canExecute() }

    /** [override] wins when it is executable; then each `PATH` entry; then the CLI's usual install locations under [home]. */
    fun find(override: String?, path: String, home: String, windows: Boolean): String? {
        override?.takeIf(::isExecutable)?.let { return it }
        val names = if (windows) listOf("claude.exe", "claude.cmd", "claude") else listOf("claude")
        val dirs = path.split(File.pathSeparator).filter { it.isNotBlank() } + listOf("$home/.local/bin", "$home/.claude/local")
        return dirs.asSequence()
            .flatMap { dir -> names.asSequence().map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.path
    }

    /**
     * Runs `[binary] --version` in the environment a session's CLI gets ([cliEnvironment]) and says what came of it: the first line
     * it printed, or how it failed. Returns within about [timeout] whatever the process does: its output is polled, never waited on,
     * so not even a child that keeps the output open after the process has exited holds it up. Never throws for the process (an I/O
     * failure is an answer too). The process, and whatever it started, is gone when this returns, also when the caller is cancelled.
     * Call on `Dispatchers.IO`.
     */
    suspend fun version(binary: String, timeout: Duration = VERSION_TIMEOUT): String {
        val process = try {
            ProcessBuilder(binary, "--version").redirectErrorStream(true).apply {
                environment().run {
                    clear()
                    putAll(cliEnvironment(System.getenv()))
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
     * woken by closing the stream. Keeps the first [OUTPUT_LIMIT] bytes and reads on past them, so the process never stalls on a full
     * pipe.
     */
    private suspend fun outputOf(process: Process): String {
        val input = process.inputStream
        val kept = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val exited = !process.isAlive // before looking at the pipe, so that nothing written before the exit is missed
            val available = input.available()
            when {
                available > 0 -> {
                    val read = input.read(buffer, 0, minOf(available, buffer.size))
                    if (read > 0) kept.write(buffer, 0, minOf(read, OUTPUT_LIMIT - kept.size()).coerceAtLeast(0))
                    yield() // a process that keeps printing must not keep the timeout from ending this
                }
                exited -> return kept.toString(Charsets.UTF_8)
                else -> delay(POLL_MILLIS)
            }
        }
    }
}
