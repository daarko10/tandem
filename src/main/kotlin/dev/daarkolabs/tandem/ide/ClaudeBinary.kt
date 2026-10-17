package dev.daarkolabs.tandem.ide

import com.intellij.openapi.util.SystemInfo
import dev.daarkolabs.tandem.session.cliEnvironment
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Finds the user's `claude` executable, and asks one for its version. Call off the EDT: both touch the file system. */
internal object ClaudeBinary {
    /** How long [version] waits for an answer by default. */
    val VERSION_TIMEOUT: Duration = 5.seconds

    /**
     * The `claude` to run. The path set in Settings | Tools | Tandem when it is not blank, else the `tandem.claudePath` system property
     * (a test hook), wins when it is executable; then each `PATH` entry; then the CLI's usual install locations.
     */
    fun find(): String? = find(
        override = TandemSettings.getInstance().state.claudePath?.takeIf { it.isNotBlank() } ?: System.getProperty("tandem.claudePath"),
        path = System.getenv("PATH").orEmpty(),
        home = System.getProperty("user.home"),
        windows = SystemInfo.isWindows,
    )

    /** [override] wins when it is executable; then each `PATH` entry; then the CLI's usual install locations under [home]. */
    fun find(override: String?, path: String, home: String, windows: Boolean): String? {
        override?.takeIf { File(it).canExecute() }?.let { return it }
        val names = if (windows) listOf("claude.exe", "claude.cmd", "claude") else listOf("claude")
        val dirs = path.split(File.pathSeparator).filter { it.isNotBlank() } + listOf("$home/.local/bin", "$home/.claude/local")
        return dirs.asSequence()
            .flatMap { dir -> names.asSequence().map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.path
    }

    /**
     * Runs `[binary] --version` in the environment a session's CLI gets ([cliEnvironment]) and says what came of it: the first line
     * it printed, or how it failed. Gives up after [timeout]. Never throws for the process: an I/O failure is an answer too. The
     * process, and whatever it started, is gone when this returns, also when the caller is cancelled. Blocks: call on `Dispatchers.IO`.
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
            val exited = runInterruptible { process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
            if (!exited) return "No answer within $timeout"
            val first = process.inputStream.bufferedReader().useLines { lines -> lines.map(String::trim).firstOrNull(String::isNotEmpty) }
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
}
