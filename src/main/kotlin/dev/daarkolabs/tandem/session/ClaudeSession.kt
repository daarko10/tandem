package dev.daarkolabs.tandem.session

import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.HostMessages
import dev.daarkolabs.tandem.protocol.InboundRequest
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.PermissionMode
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

/** How to start the CLI for one chat. */
data class SessionConfig(
    val binary: String,
    val workingDirectory: Path,
    /** Null lets the CLI pick its default. */
    val model: String? = null,
    val permissionMode: PermissionMode? = null,
    val resumeSessionId: String? = null,
) {
    /** argv per docs/protocol.md 1.1. Built-in edit tools stay disabled: edits go through the IDE (hard rule 4). */
    fun argv(): List<String> = buildList {
        add(binary)
        addAll(listOf("--output-format", "stream-json", "--verbose", "--input-format", "stream-json"))
        add("--include-partial-messages")
        add("--permission-prompt-tool=stdio")
        model?.let { addAll(listOf("--model", it)) }
        permissionMode?.let { addAll(listOf("--permission-mode", it.wire)) }
        addAll(listOf("--disallowedTools", "Edit,Write,MultiEdit,NotebookEdit"))
        resumeSessionId?.let { addAll(listOf("--resume", it)) }
    }
}

/** Starts a process. Replaced in tests by a fake CLI. */
fun interface ProcessLauncher {
    fun launch(argv: List<String>, workingDirectory: Path): Process

    companion object {
        val Default: ProcessLauncher = ProcessLauncher { argv, dir ->
            ProcessBuilder(argv).directory(dir.toFile()).apply {
                environment().remove("NODE_OPTIONS")
                environment()["CLAUDE_CODE_ENTRYPOINT"] = "sdk-tandem"
            }.start()
        }
    }
}

/**
 * One long-lived `claude` process for one chat. A reader and a writer coroutine own stdout and stdin;
 * every decoded line goes through [reduce] into [state]. Cancelling [parentScope] (or calling [close])
 * stops the process and its children: nothing outlives the session.
 *
 * All methods are safe to call from any thread, including the EDT: they only enqueue work.
 */
class ClaudeSession(
    parentScope: CoroutineScope,
    private val config: SessionConfig,
    private val launcher: ProcessLauncher = ProcessLauncher.Default,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job) + CoroutineName("ClaudeSession"))
    private val _state = MutableStateFlow(ChatState(requestedModel = config.model, permissionMode = config.permissionMode))
    private val outbox = Channel<JsonObject>(Channel.UNLIMITED)
    private val nextId = AtomicLong()

    val state: StateFlow<ChatState> = _state.asStateFlow()

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
        } catch (e: IOException) {
            LOG.warn("could not start claude", e)
            dispatch(SessionEvent.Ended("Could not start `${config.binary}`: ${e.message}. Is Claude Code installed?", isError = true))
            return
        }
        process = p
        LOG.info("claude started, pid=${p.pid()}")
        coroutineScope {
            // Cancellation of the session cannot interrupt a blocking readLine; ending the process does.
            launch { try { awaitCancellation() } finally { withContext(NonCancellable) { terminate(p) } } }
            val writer = launch { writeLoop(p) }
            val stderr = launch { runInterruptible { p.errorStream.bufferedReader().useLines { lines -> lines.forEach { stderrTail.add(it) } } } }
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

    fun setPermissionMode(mode: PermissionMode): Unit = send(HostMessages.setPermissionMode(newRequestId(), mode))

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
            val line = runInterruptible { reader.readLine() } ?: return
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
        }
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
        if (code == 0) return SessionEvent.Ended("Claude Code exited.", isError = false)
        val tail = stderrTail.text()
        return SessionEvent.Ended("Claude Code exited with code $code." + if (tail.isEmpty()) "" else "\n$tail", isError = true)
    }

    private fun terminate(p: Process) {
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
