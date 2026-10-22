package dev.daarkolabs.tandem.session

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ControlPayloads
import dev.daarkolabs.tandem.protocol.HostMessages
import dev.daarkolabs.tandem.protocol.ImageBlock
import dev.daarkolabs.tandem.protocol.InboundRequest
import dev.daarkolabs.tandem.protocol.McpAuthStart
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.PermissionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Looked up on every use, as [ShellRunner]'s is: a test's capturing factory must see every record of a shell escape's run. */
private val LOG: Logger
    get() = logger<ClaudeSession>()

/** How long `mcp_status` and `mcp_toggle` wait for their answer. */
private const val MCP_STATUS_SECONDS = 15

/** How long `mcp_reconnect` and `mcp_authenticate` wait for their answer (a guess: a reconnect that succeeds is unrecorded). */
private const val MCP_SLOW_SECONDS = 30

/** How a [ClaudeSession.request] came out: the CLI's [Answered] answer, no answer in time, or the process [Ended] first. */
internal sealed interface Reply {
    data class Answered(val response: CliMessage.ControlResponse) : Reply

    data object TimedOut : Reply

    data object Ended : Reply
}

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
        // Thinking text as the CLI summarizes it, streamed as thinking_delta (hidden flag; docs/protocol.md 1.1).
        addAll(listOf("--thinking-display", "summarized"))
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
 * [initialState] is the chat so far when a transcript is resumed; the picks in [config] win over what it recorded. [clock] stamps
 * every line, prompt and end ([SessionEvent.Cli], [SessionEvent.UserSent], [SessionEvent.Ended]) and [lastFrameAtMillis].
 */
class ClaudeSession(
    parentScope: CoroutineScope,
    private val config: SessionConfig,
    private val launcher: ProcessLauncher = DefaultProcessLauncher(),
    initialState: ChatState = ChatState(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long one second of an MCP request's wait is; tests shrink it. The error still names the seconds ([McpError.TimedOut]). */
    private val mcpMillisPerSecond: Long = 1_000L,
    /** How long a shell escape's output frame waits for the quiet result of its command frame before it goes out anyway. */
    private val quietWaitMillis: Long = 5_000L,
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

    /** The [request]s waiting for their answer, by request id. The reader hands each answer to its waiter, or else to [reduce]. */
    private val replies = ConcurrentHashMap<String, CompletableDeferred<Reply>>()

    /** Set once the process is over (it exited, never started, or the session was closed): no request is answered any more. */
    @Volatile
    private var ended = false

    /** Set once Tandem ends the process ([terminate], [killNow]): from then on, its output failing under a read is no failure. */
    @Volatile
    private var stopping = false

    /** Set by [killNow]: Tandem ended the process at once, as when the IDE exits, so the turn in progress did not fail. */
    @Volatile
    private var killed = false

    /**
     * Guards [heldEscapes] and [pendingOutput]: a shell escape's frames go out in the recorded order (spec 12.4), the output frame never
     * right behind its command frame and an escape never right behind a prompt, since the CLI batches the sends queued before it starts
     * processing and merges them into one (`bash-mode:3-6`). A prompt is not held (spec 12.9 item 7).
     */
    private val shellLock = Any()

    /** Shell escapes waiting for the turn in progress (a prompt sent just before them) to end, in order. */
    private val heldEscapes = ArrayDeque<HeldEscape>()

    /** A responding shell escape's output frame, waiting for the quiet result of its command frame. */
    private var pendingOutput: PendingOutput? = null

    private class HeldEscape(val id: String, val command: String, val output: String, val respond: Boolean)

    /** Shell escape [id]'s output [frame], to send once [quiet] has left [ChatState.quietSends]; [fallback] sends it anyway later. */
    private class PendingOutput(val id: String, val command: String, val quiet: String, val frame: JsonObject) {
        var fallback: Job? = null
    }

    private val _reportedSessionId = MutableStateFlow<String?>(null)
    private val _lastFrameAtMillis = MutableStateFlow(0L)

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

    /**
     * When the process last wrote a line to stdout, by [clock]; 0 before the first. Every line counts, `keep_alive` and unknown types
     * included, though those change no [state]: the chat tells a CLI that is still working from one that stopped responding by it.
     */
    val lastFrameAtMillis: StateFlow<Long> = _lastFrameAtMillis.asStateFlow()

    /** The running process, for tests and diagnostics. */
    @Volatile
    var process: Process? = null
        private set

    init {
        // A backstop: a session cancelled before its process coroutine ran never gets to that coroutine's own end.
        scope.coroutineContext.job.invokeOnCompletion { endRequests() }
    }

    fun start(): Job {
        // initialize must be the first frame on stdin, ahead of anything the user sends.
        send(HostMessages.initialize(newRequestId()))
        return scope.launch(Dispatchers.IO + CoroutineName("ClaudeSession.io")) {
            // Every end of the process passes here: a natural exit, a failed launch, and close() or stop() cancelling it.
            try {
                run()
            } finally {
                endRequests()
            }
        }
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
            ended = true // ahead of the chat saying so: a request made once it shows answers Ended at once
            endWith(SessionEvent.Ended(text, isError = true, atMillis = clock()))
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
            ended = true // ahead of the chat saying so: a request made once it shows answers Ended at once
            endWith(exitEvent(code))
            this.coroutineContext.job.children.forEach { it.cancel() }
        }
    }

    /**
     * Sends a prompt: [text], then the [images] attached to it, in order. Each image's id is the prompt's uuid and its index. Blank
     * text is left off the wire, so the entry's text is `""` then, as the reloaded line gives it.
     */
    fun sendUserMessage(text: String, images: List<ImageBlock> = emptyList()) {
        val uuid = UUID.randomUUID().toString()
        val shown = images.mapIndexed { i, image -> UserImage("$uuid#$i", image.mediaType, image.base64) }
        dispatch(SessionEvent.UserSent(uuid, if (text.isBlank()) "" else text, clock(), shown))
        send(HostMessages.userMessage(text, images, uuid))
    }

    /**
     * Interrupts the turn in progress. While a shell escape's output frame still waits for its command frame's quiet result, no model
     * call runs yet: the frame is dropped instead, the turn it would have started ends as stopped, and no request goes out.
     */
    fun interrupt() {
        val dropped = synchronized(shellLock) {
            pendingOutput?.also {
                pendingOutput = null
                it.fallback?.cancel()
                dispatch(SessionEvent.ShellReplyDropped(it.id))
            }
        }
        if (dropped == null) send(HostMessages.interrupt(newRequestId())) else advanceShell()
    }

    /**
     * Sends shell escape [id]'s frames: its [command] and its [output] (stdout and stderr merged; `<bash-stderr>` stays empty), so
     * that the model sees what the terminal shows. With [respond], two frames as `bash-mode-respond` sent them: the command quietly,
     * then, once its zero-turn result is in (or [quietWaitMillis] later), the output without `shouldQuery`, which starts the turn
     * that answers it. Without, one quiet frame with both (context only). An escape sent while a turn is in progress (a prompt sent
     * just before it) waits whole for that turn's result: an escape never goes out right behind a prompt (a prompt right behind a
     * quiet frame is spec 12.9 item 7). One sent once the process is over, before the chat shows it, sends nothing and says so.
     */
    fun sendShellEscape(id: String, command: String, output: String, respond: Boolean) {
        synchronized(shellLock) {
            val escape = HeldEscape(id, command, output, respond)
            when {
                // Read under shellLock: set before dropShellFrames takes it, so an escape not dropped there is seen as unsent here.
                ended -> notice(NoticeLevel.WARNING, unsentShellText(command))
                heldEscapes.isEmpty() && pendingOutput == null && _state.value.turn == null -> startEscapeLocked(escape)
                else -> heldEscapes.addLast(escape)
            }
        }
    }

    /** Applies [event] to this chat, as one of the CLI's lines would be: a shell escape's progress, which Tandem runs itself. */
    internal fun event(event: SessionEvent): Unit = dispatch(event)

    private fun startEscapeLocked(escape: HeldEscape) {
        if (!escape.respond) {
            val uuid = UUID.randomUUID().toString()
            dispatch(SessionEvent.ShellSent(escape.id, quiet = listOf(uuid), query = null, atMillis = clock()))
            send(HostMessages.bashContext(escape.command, escape.output, "", uuid))
            return
        }
        val quiet = UUID.randomUUID().toString()
        val query = UUID.randomUUID().toString()
        dispatch(SessionEvent.ShellSent(escape.id, quiet = listOf(quiet), query = query, atMillis = clock()))
        send(HostMessages.bashInput(escape.command, quiet))
        val pending = PendingOutput(escape.id, escape.command, quiet, HostMessages.bashOutput(escape.output, "", query, query = true))
        pendingOutput = pending
        pending.fallback = scope.launch(CoroutineName("ClaudeSession.quietWait")) {
            delay(quietWaitMillis)
            sendOutput(pending)
        }
    }

    /** Sends [pending] if it still waits; then the escapes held behind it may start. */
    private fun sendOutput(pending: PendingOutput) {
        synchronized(shellLock) {
            if (pendingOutput !== pending) return
            pendingOutput = null
            pending.fallback?.cancel()
            send(pending.frame)
        }
    }

    /**
     * After each line the reader reduced: the output frame goes out once its quiet send's result is in, and the escapes held behind a
     * turn start, one at a time, once no turn is in progress.
     */
    private fun advanceShell() {
        synchronized(shellLock) {
            val pending = pendingOutput
            if (pending != null && pending.quiet !in _state.value.quietSends) {
                pendingOutput = null
                pending.fallback?.cancel()
                send(pending.frame)
            }
            while (pendingOutput == null && heldEscapes.isNotEmpty() && _state.value.turn == null) {
                startEscapeLocked(heldEscapes.removeFirst())
            }
        }
    }

    /**
     * The process is over: no shell escape frame goes out any more. Returns the commands of the escapes whose output never went out,
     * in order: the one whose output frame waited for its quiet result if [withPending], then those still held behind a turn.
     */
    private fun dropShellFrames(withPending: Boolean): List<String> =
        synchronized(shellLock) {
            val pending = pendingOutput
            pending?.fallback?.cancel()
            pendingOutput = null
            val held = heldEscapes.map { it.command }
            heldEscapes.clear()
            listOfNotNull(pending?.command?.takeIf { withPending }) + held
        }

    /**
     * The process ended by itself or never started: [end] goes in the chat in one step with a notice for each escape whose output
     * never reached the CLI (hard rule 5), so that the handle never takes an ended chat without them. An output frame that waited for
     * its quiet result needs none: the turn its escape opened ends with the process, as failed.
     */
    private fun endWith(end: SessionEvent.Ended) {
        val unsent = dropShellFrames(withPending = false).map { SessionEvent.HostNotice(NoticeLevel.WARNING, unsentShellText(it)) }
        _state.update { s -> unsent.fold(reduce(s, end)) { acc, notice -> reduce(acc, notice) } }
    }

    /**
     * The handle lets this process go before it ends ([SessionHandle.stop]): no shell escape frame goes out any more, and each escape
     * whose output had not gone out says so, ahead of the chat being taken from [state] and settled (which ends the turn in progress
     * without a word).
     */
    internal fun letGoShellFrames() {
        dropShellFrames(withPending = true).forEach { notice(NoticeLevel.WARNING, unsentShellText(it)) }
    }

    /**
     * Asks for another model. The switch only counts once `system/init` reports it (see [ChatState.modelWarning]); an error answer
     * puts back the model shown before, with a notice ([reduce]).
     */
    fun setModel(model: String?) {
        val requestId = newRequestId()
        dispatch(SessionEvent.ModelRequested(model, requestId))
        send(HostMessages.setModel(requestId, model))
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

    /**
     * Asks for another effort from the next turn on (`apply_flag_settings`). It shows at once; an error answer puts back the effort
     * shown before, with a notice ([reduce]).
     */
    fun setEffort(level: String) {
        val requestId = newRequestId()
        dispatch(SessionEvent.EffortRequested(level, requestId))
        send(HostMessages.applyFlagSettings(requestId, level))
    }

    /** Asks the CLI to give the session a custom title (`rename_session`). */
    fun rename(title: String): Unit = send(HostMessages.renameSession(newRequestId(), title))

    /** Shows [text] in this chat as a notice of [level]: something Tandem itself tells the user about this process. */
    internal fun notice(level: NoticeLevel, text: String): Unit = dispatch(SessionEvent.HostNotice(level, text))

    /**
     * Sends the control request [build] makes for a new request id, and waits up to [timeoutMillis] for the CLI's answer to it. The
     * id is registered before the frame goes out, so the answer is either this call's or, when it comes after the timeout or after
     * the caller was cancelled, the reducer's (an error then shows in the chat), never lost. Once the process is over, it answers
     * [Reply.Ended] and writes nothing; one that ends while this waits answers [Reply.Ended] too.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun request(timeoutMillis: Long, build: (requestId: String) -> JsonObject): Reply {
        val id = newRequestId()
        val waiter = CompletableDeferred<Reply>()
        replies[id] = waiter
        var delivered = false // set just before the reply is handed back to the caller
        try {
            if (ended || !job.isActive) return Reply.Ended.also { delivered = true }
            send(build(id))
            withTimeoutOrNull(timeoutMillis) { waiter.await() }?.let { return it.also { delivered = true } }
            replies.remove(id)
            if (!waiter.complete(Reply.TimedOut)) return waiter.getCompleted().also { delivered = true } // raced the timeout: the caller's
            LOG.info("control request $id timed out after $timeoutMillis ms")
            return Reply.TimedOut.also { delivered = true }
        } finally {
            // On every exit, the caller's cancellation included: a later answer finds no waiter and goes to the reducer.
            replies.remove(id)
            waiter.cancel()
            // Not cancelled: the reader completed the waiter first, and skipped the reducer. If the caller was cancelled before it
            // resumed (await() is promptly cancellable), the answer never reached it, so it goes to the reducer now, once.
            if (!delivered && !waiter.isCancelled) {
                (waiter.getCompleted() as? Reply.Answered)?.let { dispatch(SessionEvent.Cli(it.response, clock())) }
            }
        }
    }

    /** Lists the session's MCP servers (`mcp_status`); a success also replaces the servers of [ChatState.mcp]. */
    suspend fun mcpStatus(): McpResult<List<McpServerInfo>> {
        val result = mcpRequest(MCP_STATUS_SECONDS, HostMessages::mcpStatus) { payload ->
            val listed = ControlPayloads.mcpStatus(payload)
            if (listed == null) McpResult.Failed(McpError.Rejected("the CLI's answer listed no servers")) else McpResult.Ok(listed.servers)
        }
        if (result is McpResult.Ok) dispatch(SessionEvent.McpServersListed(result.value))
        return result
    }

    /** Disables or enables the MCP server [name] (`mcp_toggle`). */
    suspend fun mcpToggle(name: String, enabled: Boolean): McpResult<Unit> =
        mcpRequest(MCP_STATUS_SECONDS, { HostMessages.mcpToggle(it, name, enabled) }) { McpResult.Ok(Unit) }

    /** Reconnects the MCP server [name] (`mcp_reconnect`). */
    suspend fun mcpReconnect(name: String): McpResult<Unit> =
        mcpRequest(MCP_SLOW_SECONDS, { HostMessages.mcpReconnect(it, name) }) { McpResult.Ok(Unit) }

    /**
     * Starts the MCP server [name]'s sign-in (`mcp_authenticate`): the link to open, if any. A success without one (a bare success)
     * is [McpResult.Ok] with no `authUrl`; a link that is not an `http(s)` URL is never handed on.
     */
    suspend fun mcpAuthenticate(name: String): McpResult<McpAuthStart> =
        mcpRequest(MCP_SLOW_SECONDS, { HostMessages.mcpAuthenticate(it, name) }) { payload ->
            val start = ControlPayloads.mcpAuthenticate(payload) ?: McpAuthStart(authUrl = null)
            if (start.authUrl != null && ControlPayloads.browsableUrl(start.authUrl) == null) {
                McpResult.Failed(McpError.Rejected("the sign-in link is not an http(s) URL", linkFailed = true))
            } else {
                McpResult.Ok(start)
            }
        }

    /**
     * One MCP [request] of [seconds]: a success's payload goes to [decode], off the caller's thread; an error answer is
     * [McpError.Rejected] with the CLI's text.
     */
    private suspend fun <T> mcpRequest(seconds: Int, build: (String) -> JsonObject, decode: (JsonObject?) -> McpResult<T>): McpResult<T> =
        when (val reply = request(seconds * mcpMillisPerSecond, build)) {
            is Reply.Answered -> {
                val response = reply.response
                if (response.isSuccess) {
                    withContext(Dispatchers.Default) { decode(response.payload) }
                } else {
                    McpResult.Failed(McpError.Rejected(response.error ?: "unknown error"))
                }
            }
            Reply.TimedOut -> McpResult.Failed(McpError.TimedOut(seconds))
            Reply.Ended -> McpResult.Failed(McpError.Ended)
        }

    /** The process is over: every waiting [request] answers [Reply.Ended], and so does every later one. */
    private fun endRequests() {
        ended = true
        replies.values.forEach { it.complete(Reply.Ended) }
        replies.clear()
    }

    /**
     * Answers [permission] with [answer] ([PermissionAnswers] makes them): the `control_response` goes to stdin, and the request is
     * no longer pending. Only enqueues, so safe on the EDT. An allow without `updatedPermissions` sends none.
     */
    fun answer(permission: PendingPermission, answer: PermissionAnswer) {
        val frame = when (answer) {
            is PermissionAnswer.Allow ->
                HostMessages.allow(permission.requestId, permission.toolUseId, answer.updatedInput, answer.updatedPermissions)
            is PermissionAnswer.Deny -> HostMessages.deny(permission.requestId, permission.toolUseId, answer.message)
        }
        send(frame)
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
        killed = true
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
            val at = clock()
            _lastFrameAtMillis.value = at
            if (line.isBlank()) continue
            val message = Ndjson.decode(line)
            when (message) {
                is CliMessage.Unknown -> LOG.debug { "unhandled line type=${message.type} subtype=${message.subtype} length=${line.length}" }
                is CliMessage.ControlRequest -> if (message.request is InboundRequest.Other) {
                    LOG.info("unsupported control request ${message.request.subtype}")
                    send(HostMessages.error(message.requestId, "Unsupported request: ${message.request.subtype}"))
                    // An MCP server's question (spec 2): answered as unsupported, as before, and no longer silently.
                    if (message.request.subtype == "elicitation") {
                        notice(NoticeLevel.WARNING, "An MCP server asked for input Tandem cannot show yet.")
                    }
                }
                // The answer to a request() that waits for it is that call's alone; any other goes to the reducer.
                is CliMessage.ControlResponse -> if (replies.remove(message.requestId)?.complete(Reply.Answered(message)) == true) continue
                else -> {}
            }
            dispatch(SessionEvent.Cli(message, at))
            if (message is CliMessage.SystemInit && message.sessionId.isNotEmpty()) _reportedSessionId.value = message.sessionId
            if (message is CliMessage.Result) advanceShell()
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

    /** How the process ended with [code]; ended by the host when Tandem killed it at once ([killNow]), as when the IDE exits. */
    private fun exitEvent(code: Int): SessionEvent.Ended {
        val byHost = killed
        val at = clock()
        if (code == 0) return SessionEvent.Ended("The claude CLI exited.", isError = false, endedByHost = byHost, atMillis = at)
        val tail = stderrTail.text()
        return SessionEvent.Ended(
            "The claude CLI exited with code $code." + if (tail.isEmpty()) "" else "\n$tail",
            isError = true,
            endedByHost = byHost,
            atMillis = at,
        )
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
}

/** The notice for a shell escape of [command] whose output never reached the CLI (hard rule 5). */
internal fun unsentShellText(command: String): String = "The output of ! ${command.lineSequence().first()} was not sent: the CLI ended first."
