package dev.daarkolabs.tandem.session

import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.HostMessages
import dev.daarkolabs.tandem.protocol.InboundRequest
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.PermissionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val LOG = logger<ClaudeSession>()

/**
 * How to start the CLI for one chat: a new session ([sessionId]), an existing one ([resumeSessionId]), or a fork of an existing one
 * ([resumeSessionId] with [forkSession], and optionally [sessionId] as the fork's own id). Only a fork may set both ids: the CLI
 * refuses `--session-id` with `--resume` unless `--fork-session` is given too.
 */
data class SessionConfig(
    val binary: String,
    val workingDirectory: Path,
    /** A new session, or with [forkSession] the fork: passed as `--session-id`, so its id is known before the CLI reports it. */
    val sessionId: String? = null,
    /** An existing session: passed as `--resume`. */
    val resumeSessionId: String? = null,
    /** Null lets the CLI pick its default. */
    val model: String? = null,
    val effort: String? = null,
    val permissionMode: PermissionMode? = null,
    /** With [resumeSessionId]: continue as a new session (`--fork-session`), leaving the original untouched. */
    val forkSession: Boolean = false,
) {
    init {
        require(!forkSession || resumeSessionId != null) { "A fork needs the resumeSessionId of the session it forks." }
        require((sessionId == null) != (resumeSessionId == null) || (sessionId != null && forkSession)) {
            "Pass sessionId for a new session or resumeSessionId for an existing one; both only for a fork, never neither."
        }
    }

    /** argv per docs/protocol.md 1.1. Built-in edit tools stay disabled: edits go through the IDE (hard rule 4). */
    fun argv(): List<String> = buildList {
        add(binary)
        addAll(listOf("--output-format", "stream-json", "--verbose", "--input-format", "stream-json"))
        add("--include-partial-messages")
        add("--permission-prompt-tool=stdio")
        model?.let { addAll(listOf("--model", it)) }
        effort?.let { addAll(listOf("--effort", it)) }
        permissionMode?.let { addAll(listOf("--permission-mode", it.wire)) }
        addAll(listOf("--disallowedTools", "Edit,Write,MultiEdit,NotebookEdit"))
        resumeSessionId?.let {
            addAll(listOf("--resume", it))
            if (forkSession) add("--fork-session")
        }
        sessionId?.let { addAll(listOf("--session-id", it)) }
    }
}

/** Starts a process. Replaced in tests by a fake CLI. */
fun interface ProcessLauncher {
    fun launch(argv: List<String>, workingDirectory: Path): Process
}

/**
 * Starts the real CLI. The child gets [cliEnvironment] of [parentEnvironment] and nothing else, so variables that identify
 * the session or hosted environment Tandem itself runs in never reach a chat's CLI.
 */
class DefaultProcessLauncher(private val parentEnvironment: () -> Map<String, String> = System::getenv) : ProcessLauncher {
    override fun launch(argv: List<String>, workingDirectory: Path): Process {
        val builder = ProcessBuilder(argv).directory(workingDirectory.toFile())
        builder.environment().run {
            clear()
            putAll(cliEnvironment(parentEnvironment()))
        }
        return builder.start()
    }
}

/**
 * One long-lived `claude` process for one chat. A reader and a writer coroutine own stdout and stdin;
 * every decoded line goes through [reduce] into [state]. Cancelling [parentScope] (or calling [close])
 * stops the process and its children: nothing outlives the session.
 *
 * All methods are safe to call from any thread, including the EDT: they only enqueue work.
 *
 * [initialState] is the chat so far when a transcript is resumed; the picks in [config] win over what it recorded.
 */
class ClaudeSession(
    parentScope: CoroutineScope,
    private val config: SessionConfig,
    private val launcher: ProcessLauncher = DefaultProcessLauncher(),
    initialState: ChatState = ChatState(),
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job) + CoroutineName("ClaudeSession"))
    private val _state = MutableStateFlow(
        initialState.copy(
            requestedModel = config.model ?: initialState.requestedModel,
            permissionMode = config.permissionMode ?: initialState.permissionMode,
            effort = config.effort ?: initialState.effort,
        ),
    )
    private val outbox = Channel<JsonObject>(Channel.UNLIMITED)
    private val nextId = AtomicLong()

    /** Set once Tandem ends the process ([terminate], [killNow]): from then on, its output failing under a read is no failure. */
    @Volatile
    private var stopping = false
    private val _reportedSessionId = MutableStateFlow<String?>(null)

    val state: StateFlow<ChatState> = _state.asStateFlow()

    /** Completes once this session is over and its process gone: after [close], or after its parent scope was cancelled. */
    internal val job: Job
        get() = scope.coroutineContext.job

    /**
     * The `session_id` of the CLI's latest `system/init` in this process: null until the first, which the CLI sends with the first
     * prompt (a turn's start), not when it starts. Unlike [ChatState.sessionId], a `result` never sets it: the `result` of a start
     * that fails carries a session id as well.
     */
    val reportedSessionId: StateFlow<String?> = _reportedSessionId.asStateFlow()

    /** The running process, for tests and diagnostics. */
    @Volatile
    var process: Process? = null
        private set

    fun start(): Job {
        // initialize must be the first frame on stdin, ahead of anything the user sends.
        send(HostMessages.initialize(newRequestId()))
        return scope.launch(Dispatchers.IO + CoroutineName("ClaudeSession.io")) { run() }
    }

    private suspend fun run() {
        val p = try {
            launcher.launch(config.argv(), config.workingDirectory)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not only an IOException: ProcessBuilder.start also throws for arguments it cannot pass, and a launcher can fail.
            LOG.warn("could not start claude", e)
            val reason = e.message ?: e.javaClass.simpleName
            val text = "Could not start `${config.binary}`: $reason. Is the claude CLI (Claude Code) installed?"
            dispatch(SessionEvent.Ended(text, isError = true))
            return
        }
        process = p
        LOG.info("claude started, pid=${p.pid()}")
        coroutineScope {
            // Cancellation of the session cannot interrupt a blocking readLine; ending the process does. Undispatched, so that it
            // runs even when the session was cancelled while the process started: a coroutine cancelled before it starts never runs.
            launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { terminate(p) } }
            }
            val writer = launch { writeLoop(p) }
            val stderr = launch { readOutput(p) { p.errorStream.bufferedReader().useLines { lines -> lines.forEach { stderrTail.add(it) } } } }
            readLoop(p)
            val code = runInterruptible { p.waitFor() }
            stderr.join()
            writer.cancel()
            LOG.info("claude exited with code $code")
            dispatch(exitEvent(code))
            this.coroutineContext.job.children.forEach { it.cancel() }
        }
    }

    fun sendUserMessage(text: String) {
        val uuid = UUID.randomUUID().toString()
        dispatch(SessionEvent.UserSent(uuid, text))
        send(HostMessages.userText(text, uuid))
    }

    fun interrupt(): Unit = send(HostMessages.interrupt(newRequestId()))

    /** Asks for another model. The switch only counts once `system/init` reports it (see [ChatState.modelWarning]). */
    fun setModel(model: String?) {
        dispatch(SessionEvent.ModelRequested(model))
        send(HostMessages.setModel(newRequestId(), model))
    }

    /**
     * Asks for another permission mode. It shows at once; the answer to the request, `system/status` or the next `system/init`
     * confirms it, and a rejection puts back the mode shown before ([reduce]).
     */
    fun setPermissionMode(mode: PermissionMode) {
        val requestId = newRequestId()
        dispatch(SessionEvent.PermissionModeRequested(mode, requestId))
        send(HostMessages.setPermissionMode(requestId, mode))
    }

    /** Asks the CLI to give the session a custom title (`rename_session`). */
    fun rename(title: String): Unit = send(HostMessages.renameSession(newRequestId(), title))

    /** Shows [text] in this chat as a notice of [level]: something Tandem itself tells the user about this process. */
    internal fun notice(level: NoticeLevel, text: String): Unit = dispatch(SessionEvent.HostNotice(level, text))

    fun allow(permission: PendingPermission, always: Boolean = false) {
        val updates = if (always) permission.suggestions.map(::forSession) else emptyList()
        send(HostMessages.allow(permission.requestId, permission.toolUseId, permission.input, updates))
        dispatch(SessionEvent.PermissionAnswered(permission.requestId))
    }

    fun deny(permission: PendingPermission, message: String = "The user denied this request.") {
        send(HostMessages.deny(permission.requestId, permission.toolUseId, message))
        dispatch(SessionEvent.PermissionAnswered(permission.requestId))
    }

    /** Stops the process (stdin closed, then terminate, then kill) and waits until it is gone. */
    suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
    }

    /**
     * Synchronous last resort for JVM shutdown (a shutdown hook), when coroutines may never get to run [terminate]:
     * kills the process and its children right away. Safe to call more than once.
     */
    fun killNow() {
        val p = process ?: return
        stopping = true
        val children = p.descendants().toList()
        p.destroyForcibly()
        children.forEach { it.destroyForcibly() }
    }

    private val stderrTail = object {
        private val lines = ArrayDeque<String>()

        @Synchronized
        fun add(line: String) {
            if (lines.size == 20) lines.removeFirst()
            lines.addLast(line)
        }

        @Synchronized
        fun text(): String = lines.joinToString("\n")
    }

    private suspend fun readLoop(p: Process) {
        val reader = p.inputStream.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = readOutput(p) { reader.readLine() } ?: return
            if (line.isBlank()) continue
            val message = Ndjson.decode(line)
            when (message) {
                is CliMessage.Unknown -> LOG.debug { "unhandled line type=${message.type} subtype=${message.subtype} length=${line.length}" }
                is CliMessage.ControlRequest -> if (message.request is InboundRequest.Other) {
                    LOG.info("unsupported control request ${message.request.subtype}")
                    send(HostMessages.error(message.requestId, "Unsupported request: ${message.request.subtype}"))
                }
                else -> {}
            }
            dispatch(SessionEvent.Cli(message))
            if (message is CliMessage.SystemInit && message.sessionId.isNotEmpty()) _reportedSessionId.value = message.sessionId
        }
    }

    /**
     * [read], a blocking read of [p]'s stdout or stderr; null if the output failed because the process is over. The JDK can close
     * the pipe of a process that exits under a waiting read: when Tandem ended the process, or it ended by itself, its output is
     * over and the exit code tells the rest. A failure while it runs on, and Tandem did not end it, is one, and is thrown.
     *
     * Cancelling this interrupts the reading thread ([runInterruptible]): a stream that can be interrupted then throws
     * `InterruptedIOException`, an [IOException], and runInterruptible interrupts only once the cancellation has begun. While this is
     * being cancelled, that, or any I/O failure, is the cancellation, never a failure to show.
     */
    private suspend fun <T> readOutput(p: Process, read: () -> T): T? =
        try {
            runInterruptible(block = read)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // being cancelled: this is the cancellation, thrown as such
            if (stopping || !p.isAlive) null else throw e
        }

    private suspend fun writeLoop(p: Process) {
        val writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
        for (message in outbox) {
            try {
                runInterruptible {
                    writer.write(Ndjson.encode(message))
                    writer.flush()
                }
            } catch (e: IOException) {
                LOG.info("stdin closed: ${e.message}")
                return
            }
        }
    }

    private fun exitEvent(code: Int): SessionEvent.Ended {
        if (code == 0) return SessionEvent.Ended("The claude CLI exited.", isError = false)
        val tail = stderrTail.text()
        return SessionEvent.Ended("The claude CLI exited with code $code." + if (tail.isEmpty()) "" else "\n$tail", isError = true)
    }

    private fun terminate(p: Process) {
        stopping = true
        val children = p.descendants().toList()
        if (p.isAlive) {
            runCatching { p.outputStream.close() } // end of input: the CLI shuts down on its own
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroy()
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
            }
        }
        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
        LOG.info("claude stopped")
    }

    private fun dispatch(event: SessionEvent) = _state.update { reduce(it, event) }

    private fun send(message: JsonObject) {
        outbox.trySend(message)
    }

    private fun newRequestId(): String = "tandem-${nextId.incrementAndGet()}"

    /** "Always allow" lasts for this session only; persisting rules to settings is a separate, explicit choice. */
    private fun forSession(suggestion: JsonObject): JsonObject = JsonObject(suggestion.toMutableMap().apply { put("destination", JsonPrimitive("session")) })
}
