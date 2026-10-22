package dev.daarkolabs.tandem.session

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Looked up on every use rather than once: a test installs a capturing factory ([Logger.setFactory]) to check that no command or
 * output is ever logged, which a logger kept from before would bypass. Shell escapes log once per command at most.
 */
private val LOG: Logger
    get() = logger<ShellRunner>()

/** How much [ShellRunner] reads at a time, and how much output it batches at most before handing it on. */
private const val CHUNK: Int = 8_192

/** How long [ShellRunner] waits while the pipe is empty and the shell still runs. */
private const val POLL_MILLIS: Long = 15

/**
 * The OS's words in the JDK's launch failure: `error=2, No such file or directory` (fork/exec), or
 * `Exec failed, error: 2 (No such file or directory)` (the spawn helper of current JDKs).
 */
private val LAUNCH_ERROR: Regex = Regex("""error[=:]\s*\d+(?:,\s*(.+)|\s*\((.+)\))""")

/** Starts a shell for a command. Replaced in tests. */
fun interface ShellLauncher {
    fun launch(argv: List<String>, directory: Path, environment: Map<String, String>): Process
}

/** How Tandem runs a shell escape (spec 12.4): the login shell, its limits, and the launcher. */
object Shells {
    /** Output kept in memory and sent; the rest is drained and counted. */
    const val MAX_OUTPUT_CHARS: Int = 100_000

    /** The 10-minute cap. */
    const val TIMEOUT_MILLIS: Long = 600_000

    /** Output reaches the chat at most this often. */
    const val BATCH_MILLIS: Long = 50

    /**
     * The argv that runs [command]: on Windows (unverified) `%ComSpec% /c command`; elsewhere the login shell, `$SHELL -lc command`
     * (`/bin/zsh` when `SHELL` is missing or blank on macOS, `/bin/sh` elsewhere). A non-interactive login shell: the profile's PATH
     * and exports reach the command, aliases and functions of `.zshrc`/`.bashrc` do not.
     */
    fun argv(command: String, environment: Map<String, String>, os: String = System.getProperty("os.name")): List<String> {
        if (os.lowercase().contains("windows")) {
            val comSpec = environment["ComSpec"] ?: environment.entries.firstOrNull { it.key.equals("ComSpec", ignoreCase = true) }?.value
            return listOf(comSpec?.takeIf { it.isNotBlank() } ?: "cmd.exe", "/c", command)
        }
        val shell = environment["SHELL"]?.takeIf { it.isNotBlank() } ?: if (os.startsWith("Mac")) "/bin/zsh" else "/bin/sh"
        return listOf(shell, "-lc", command)
    }

    /** Starts [argv] in the directory with exactly the environment given, stderr merged into stdout in order. */
    fun defaultLauncher(): ShellLauncher = ShellLauncher { argv, directory, environment ->
        val builder = ProcessBuilder(argv).directory(directory.toFile()).redirectErrorStream(true)
        builder.environment().run {
            clear()
            putAll(environment)
        }
        builder.start()
    }
}

/** How a shell escape ended. */
sealed interface ShellEnd {
    /** The shell exited with [code]. */
    data class Exited(val code: Int) : ShellEnd

    /** Stop, Esc or the session's close ended it. */
    data object Stopped : ShellEnd

    /** The 10-minute cap ended it. */
    data object TimedOut : ShellEnd

    /** The [shell] did not launch, for [reason] (the OS's words): nothing ran. */
    data class NotStarted(val shell: String, val reason: String) : ShellEnd

    /** Reading the output failed, for [reason]; set by the manager, never by the runner. */
    data class Failed(val reason: String) : ShellEnd
}

/**
 * What a shell escape gave: its [output] (up to [Shells.MAX_OUTPUT_CHARS], then `\n… (cut, <cut> more characters)` when [cut] is not 0),
 * how it ended, and how long it ran.
 */
data class ShellResult(val output: String, val end: ShellEnd, val durationMs: Long, val cut: Int)

/**
 * Runs shell escapes in the user's login shell (spec 12.4), off the EDT, polling the output so that Stop, the cap and a close act at
 * once. Never logs the command or its output.
 */
class ShellRunner(
    private val launcher: ShellLauncher = Shells.defaultLauncher(),
    private val environment: () -> Map<String, String>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = Shells.TIMEOUT_MILLIS,
    private val batchMillis: Long = Shells.BATCH_MILLIS,
) {
    /** The shells running, from their start until they are gone: what [killAllNow] kills. */
    private val running: MutableSet<Process> = ConcurrentHashMap.newKeySet()

    /** The shells [killAllNow] killed: the JDK closes a destroyed process's pipe, so their output ends there, without a failure. */
    private val killed: MutableSet<Process> = ConcurrentHashMap.newKeySet()

    /**
     * Runs [command] in [directory] on [Dispatchers.IO], handing its output to [onOutput] in batches as it comes, and returns once
     * the shell has exited and the pipe holds nothing more (a backgrounded child that keeps the pipe open does not hold it). Stdin is
     * closed at once. The read never blocks: it reads only what the pipe has, so cancelling this, the cap and [killAllNow] end it at
     * once; each kills the shell and what it started. A shell that cannot start ends [ShellEnd.NotStarted] with nothing read; the
     * caller's cancellation is rethrown, and so is a read that fails, once the shell is killed.
     */
    suspend fun run(command: String, directory: Path, onOutput: suspend (String) -> Unit): ShellResult = withContext(Dispatchers.IO) {
        val env = environment()
        val argv = Shells.argv(command, env)
        val started = clock()
        val p = try {
            launcher.launch(argv, directory, env)
        } catch (e: Exception) {
            LOG.info("shell escape not started: ${e.javaClass.simpleName}")
            return@withContext ShellResult("", ShellEnd.NotStarted(argv[0], launchFailure(e)), 0, 0)
        }
        running += p
        val output = Output(onOutput, started)
        try {
            runCatching { p.outputStream.close() } // a command that reads stdin sees its end
            val finished = withTimeoutOrNull(timeoutMillis) {
                pump(p, output)
                true
            }
            val end = if (finished == null) ShellEnd.TimedOut else ShellEnd.Exited(exitCode(p))
            output.finish()
            val durationMs = clock() - started
            LOG.info("shell escape ended: ${end.javaClass.simpleName} in $durationMs ms, ${output.length} chars")
            ShellResult(output.text(), end, durationMs, output.cut)
        } finally {
            if (p.isAlive) kill(p)
            p.waitFor(2, TimeUnit.SECONDS) // bounded: a shell stuck in uninterruptible I/O must not hold a close
            running -= p
            killed -= p
        }
    }

    /** Destroys every running shell and what it started, at once, without waiting: the IDE's exit. */
    fun killAllNow() {
        running.forEach { p ->
            killed += p
            runCatching { kill(p) }
        }
    }

    /** [p]'s exit code once it has exited; a shell killed at once ([killAllNow]) gets its moment to be reaped, -1 if it takes longer. */
    private fun exitCode(p: Process): Int = if (p.isAlive && !p.waitFor(2, TimeUnit.SECONDS)) -1 else p.exitValue()

    /** The OS's reason a shell did not launch, from the JDK's exception (its cause holds the OS's words). */
    private fun launchFailure(e: Exception): String {
        val text = e.cause?.message ?: e.message ?: e.javaClass.simpleName
        val match = LAUNCH_ERROR.find(text) ?: return text.trim()
        return (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
    }

    /** Reads what [p] prints until it has exited and nothing is left, never by a call that blocks. */
    private suspend fun pump(p: Process, output: Output) {
        val input = p.inputStream
        val bytes = ByteArray(CHUNK)
        while (true) {
            val exited = !p.isAlive // before looking at the pipe, so that nothing written before the exit is missed
            val available: Int
            val read: Int
            try {
                available = input.available()
                read = if (available > 0) input.read(bytes, 0, minOf(available, CHUNK)) else 0
            } catch (e: IOException) {
                if (p in killed) return // killAllNow closed the pipe: the output ends here
                throw e
            }
            when {
                available > 0 -> {
                    if (read > 0) output.bytes(bytes, read)
                    output.handOffIfDue()
                    yield() // a command that keeps printing must not keep a cancellation or the cap from ending this
                }
                exited -> return
                else -> {
                    output.handOffIfDue()
                    delay(POLL_MILLIS)
                }
            }
        }
    }

    private fun kill(p: Process) {
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
    }

    /**
     * The output as it is decoded: UTF-8 with U+FFFD for a malformed byte, a sequence split between two reads kept whole. The first
     * [Shells.MAX_OUTPUT_CHARS] characters are kept, the rest only counted ([cut]). Kept text goes to [onOutput] once [batchMillis]
     * have passed since the last hand-off, or [CHUNK] characters wait.
     */
    private inner class Output(private val onOutput: suspend (String) -> Unit, startedAt: Long) {
        private val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        private val pendingBytes: ByteBuffer = ByteBuffer.allocate(CHUNK + 16)
        private val chars: CharBuffer = CharBuffer.allocate(CHUNK + 16)
        private val kept = StringBuilder()
        private val batch = StringBuilder()
        private var lastHandOff = startedAt
        var cut: Int = 0
            private set

        val length: Int
            get() = kept.length

        fun bytes(source: ByteArray, count: Int) {
            pendingBytes.put(source, 0, count)
            pendingBytes.flip()
            decode(endOfInput = false)
            pendingBytes.compact()
        }

        suspend fun handOffIfDue() {
            if (batch.isNotEmpty() && (clock() - lastHandOff >= batchMillis || batch.length >= CHUNK)) handOff()
        }

        /** The end: what the decoder still holds, the cut line, and the last batch. */
        suspend fun finish() {
            pendingBytes.flip()
            decode(endOfInput = true)
            while (decoder.flush(chars).isOverflow) drain()
            drain()
            if (cut > 0) {
                val note = "\n… (cut, $cut more characters)"
                kept.append(note)
                batch.append(note)
            }
            if (batch.isNotEmpty()) handOff()
        }

        fun text(): String = kept.toString()

        private fun decode(endOfInput: Boolean) {
            while (decoder.decode(pendingBytes, chars, endOfInput).isOverflow) drain()
            drain()
        }

        private fun drain() {
            chars.flip()
            val room = Shells.MAX_OUTPUT_CHARS - kept.length
            var take = minOf(room.coerceAtLeast(0), chars.remaining())
            // Never keep half of a surrogate pair at the cap.
            if (take in 1 until chars.remaining() && chars[take - 1].isHighSurrogate()) take--
            if (take > 0) {
                val text = chars.subSequence(0, take)
                kept.append(text)
                batch.append(text)
            }
            cut += chars.remaining() - take
            chars.clear()
        }

        private suspend fun handOff() {
            val text = batch.toString()
            batch.setLength(0)
            lastHandOff = clock()
            onOutput(text)
        }
    }
}
