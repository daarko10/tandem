package dev.daarkolabs.tandem.session

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ContextBreakdown
import dev.daarkolabs.tandem.protocol.ControlPayloads
import dev.daarkolabs.tandem.protocol.HostMessages
import dev.daarkolabs.tandem.protocol.ImageBlock
import dev.daarkolabs.tandem.protocol.InboundRequest
import dev.daarkolabs.tandem.protocol.McpAuthStart
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.TandemTools
import dev.daarkolabs.tandem.protocol.ToolReply
import dev.daarkolabs.tandem.protocol.UsageAnswer
import dev.daarkolabs.tandem.protocol.cancelAsyncAnswer
import dev.daarkolabs.tandem.protocol.interruptReceipt
import dev.daarkolabs.tandem.protocol.jsonRpcError
import dev.daarkolabs.tandem.protocol.jsonRpcRequest
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
import kotlinx.coroutines.asExecutor
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
import kotlinx.coroutines.future.await
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Looked up on every use, as [ShellRunner]'s is: a test's capturing factory must see every record of a shell escape's run. */
private val LOG: Logger
    get() = logger<ClaudeSession>()

/** How long `mcp_status` and `mcp_toggle` wait for their answer. */
private const val MCP_STATUS_SECONDS = 15

/** How long `mcp_reconnect` and `mcp_authenticate` wait for their answer (a guess: a reconnect that succeeds is unrecorded). */
private const val MCP_SLOW_SECONDS = 30

/** How long `get_context_usage` waits for its answer (spec 4.2). */
private const val CONTEXT_USAGE_SECONDS = 30

/** How long `get_usage` with `skip_behaviors` waits (the session block, no scan; a guess, spec 4.2). */
private const val USAGE_SESSION_SECONDS = 15

/** How long `get_usage` without `skip_behaviors` waits (it scans this machine's transcripts; a guess, spec 4.2). */
private const val USAGE_HISTORY_SECONDS = 60

/** How long `cancel_async_message` waits for its answer (spec 12.3). */
private const val CANCEL_ASYNC_SECONDS = 5

/** How long an `interrupt` with `cancel_queued` waits for its receipt (a guess: the recorded one came at once, `queue-cancel:56`). */
private const val INTERRUPT_SECONDS = 15

/** The JSON-RPC error for a method, or a server, Tandem does not serve (spec 4.1). */
private const val METHOD_NOT_FOUND = -32601

/** What a `tools/call` answers when no [HostTools] serve Tandem's tools in this session (a test's, or one without the IDE side). */
internal const val NO_HOST_TOOLS_TEXT = "Tandem's edit tools are not available in this session."

/** The `system/init` capability that lets an `interrupt` cancel the queued messages too (spec 12.3). */
private const val CANCEL_QUEUED_CAPABILITY = "interrupt_cancel_queued_v1"

/**
 * How long the end waits, once the process has exited, for the rest of its stderr. A normal exit's last lines are there at once; a
 * process the CLI started that outlives it (a tool's subprocess, an MCP server) can keep the stderr open for as long as it runs.
 */
private const val STDERR_GRACE_MILLIS = 2_000L

/**
 * How long a read of the process's output that failed waits for the process to be over before the failure counts as one:
 * [Process.destroyForcibly] closes its pipes at once, but [Process.isAlive] turns false only once the JDK has seen it exit.
 */
private const val CUT_OFF_GRACE_MILLIS = 2_000L

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
    /** Folders the CLI may read and edit besides [workingDirectory]: one `--add-dir` each, last in the argv. */
    val additionalDirectories: List<String> = emptyList(),
    /** With [forkSession]: the uuid of the message the fork ends at (`--resume-session-at`); later messages stay in the original only. */
    val resumeSessionAt: String? = null,
) {
    init {
        require(!forkSession || resumeSessionId != null) { "A fork needs the resumeSessionId of the session it forks." }
        require((sessionId == null) != (resumeSessionId == null) || (sessionId != null && forkSession)) {
            "Pass sessionId for a new session or resumeSessionId for an existing one; both only for a fork, never neither."
        }
        require(resumeSessionAt == null || forkSession) {
            "--resume-session-at only with --fork-session: without it the CLI branches the original's transcript."
        }
        require(resumeSessionAt == null || (resumeSessionAt.isNotBlank() && !resumeSessionAt.startsWith("-"))) {
            "A fork point is a message uuid, never a flag."
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
        resumeSessionAt?.let { addAll(listOf("--resume-session-at", it)) }
        for (directory in additionalDirectories) addAll(listOf("--add-dir", directory))
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
 *
 * Tandem's own MCP server (`tandem`, spec 4.1, 4.2): its `mcp_message` requests are answered by [TandemTools], a `tools/call` by
 * [hostTools] in a child job of its own, so the reader goes on; its permission requests are allowed at once (the diff is the
 * question), and an init whose `tools` lists others but not Tandem's edit tool says so once per process (the version gate).
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
    /** What serves Tandem's tools; null answers each `tools/call` with [NO_HOST_TOOLS_TEXT]. */
    private val hostTools: HostTools? = null,
    /** The plugin's version, as Tandem's server names itself in its `initialize` answer. */
    private val version: String = "dev",
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job) + CoroutineName("ClaudeSession"))
    private val _state = MutableStateFlow(
        initialState.copy(
            requestedModel = config.model ?: initialState.requestedModel,
            permissionMode = config.permissionMode ?: initialState.permissionMode,
            effort = config.effort ?: initialState.effort,
            launchPermissionMode = config.permissionMode,
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
     * Guards [held] and [pendingOutput]: a shell escape's frames go out in the recorded order (MCP spec 12.4), the output frame never
     * right behind its command frame and an escape never right behind a prompt, since the CLI batches the sends queued before it starts
     * processing and merges them into one (`bash-mode:3-6`); a prompt never goes out right behind a quiet frame or ahead of an output
     * frame (spec 12.4 rule 2).
     */
    private val shellLock = Any()

    /**
     * What waits to go out, in order (spec 12.4): a running `!` command's place, which its escape takes once it ends; shell escapes
     * held behind a turn or a queued message; prompts held behind either, or behind an output frame or a quiet send's result.
     */
    private val held = ArrayDeque<Held>()

    /** A responding shell escape's output frame, waiting for the quiet result of its command frame. */
    private var pendingOutput: PendingOutput? = null

    /**
     * The uuid of the latest usage line ([isUsageLine]) whose local turn has not ended yet: the next `result` launches its fetch.
     * Read and written by the reader coroutine only.
     */
    private var pendingUsage: String? = null

    /** The `tools/call` jobs still running, by JSON-RPC id: whoever removes a job's entry owns its end (its reply, or its withdrawal). */
    private val calls = ConcurrentHashMap<JsonElement, Job>()

    /** Set once the open calls were ended with the process ([endCalls]): [HostTools.cancelAll] runs once per process. */
    private val callsEnded = AtomicBoolean()

    /** Set once an init raised the version gate's notice: once per process. Read and written by the reader coroutine only. */
    private var gateRaised = false

    private sealed interface Held {
        /** The place of shell escape [id], whose command still runs: its escape comes once it ends. */
        class Shell(val id: String) : Held

        /** Shell escape [id]'s frames, still to go out. */
        class Escape(val id: String, val command: String, val output: String, val respond: Boolean) : Held

        /** The prompt [uuid], whose [frame] is still to be written. */
        class Prompt(val uuid: String, val frame: JsonObject) : Held
    }

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
        // A process attached while a `!` command runs starts with that command's place (spec 12.4).
        _state.value.runningShell?.let { held.addLast(Held.Shell(it)) }
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
            val stderr = launch { readOutput(p) { readStderr(p) } }
            readLoop(p)
            // Past the CLI's stdout end it can neither answer a request nor read a frame: from here a request answers Ended and a
            // shell escape says its output was not sent, rather than writing to a process that no longer reads (hard rule 5).
            ended = true
            val code = runInterruptible { p.waitFor() }
            writer.cancel() // which closes the process's input, without the end waiting for that (writeLoop)
            // A normal exit's last stderr lines go in the exit notice. A process the CLI started that keeps the stderr open holds the
            // end back no longer than the grace: the chat shows the end whatever the reader does, and the reader is let go below.
            if (withTimeoutOrNull(STDERR_GRACE_MILLIS) { stderr.join() } == null) {
                LOG.info("claude's stderr is still open $STDERR_GRACE_MILLIS ms after it exited: a process it started holds it")
            }
            LOG.info("claude exited with code $code")
            ended = true // ahead of the chat saying so: a request made once it shows answers Ended at once
            endWith(exitEvent(code))
            endRequests() // and so does one that waits, once the chat shows the end
            this.coroutineContext.job.children.forEach { it.cancel() }
        }
    }

    /**
     * Sends a prompt: [text], then the [images] attached to it, in order, with `"priority":"later"` when [later]. Each image's id is the
     * prompt's uuid and its index. Blank text is left off the wire, so the entry's text is `""` then, as the reloaded line gives it.
     *
     * Written at once, also while a turn runs: the CLI queues it and its lifecycle lines tell the rest ([reduce]). Held instead, in
     * send order, while a `!` command runs, escapes or prompts are held, an output frame waits for its quiet result, or a quiet send's
     * result is still due (spec 12.4 rule 2): [advanceShell] writes it once those frames are out.
     */
    fun sendUserMessage(text: String, images: List<ImageBlock> = emptyList(), later: Boolean = false) {
        val uuid = UUID.randomUUID().toString()
        val shown = images.mapIndexed { i, image -> UserImage("$uuid#$i", image.mediaType, image.base64) }
        val frame = HostMessages.userMessage(text, images, uuid, later)
        val entry = if (text.isBlank()) "" else text
        synchronized(shellLock) {
            if (held.isNotEmpty() || pendingOutput != null || _state.value.quietSends.isNotEmpty()) {
                held.addLast(Held.Prompt(uuid, frame))
                dispatch(SessionEvent.UserSent(uuid, entry, clock(), shown, later = later, live = true, held = true))
                LOG.info("prompt $uuid held behind shell frames")
            } else {
                dispatch(SessionEvent.UserSent(uuid, entry, clock(), shown, later = later, live = true))
                send(frame)
            }
        }
    }

    /**
     * Withdraws the waiting message [uuid] (spec 12.3); with [restore] it goes back to the composer (Edit), else it is dropped (Cancel).
     * A held prompt leaves at once, with no frame. One in the CLI's queue is asked for with `cancel_async_message` (5 s): `true`
     * withdraws it, while `false`, an error or no answer puts it back with a notice. Nothing for a uuid that does not wait.
     */
    fun withdraw(uuid: String, restore: Boolean) {
        val local = synchronized(shellLock) {
            held.firstOrNull { it is Held.Prompt && it.uuid == uuid }?.also {
                held.remove(it)
                dispatch(SessionEvent.SendWithdrawn(uuid, restore, notice = null))
            }
        }
        if (local != null) {
            LOG.info("held prompt $uuid withdrawn")
            advanceShell()
            return
        }
        val send = _state.value.pendingSends.firstOrNull { it.uuid == uuid && it.stage == PendingStage.QUEUED } ?: return
        dispatch(SessionEvent.WithdrawRequested(uuid, restore))
        scope.launch(CoroutineName("ClaudeSession.withdraw")) {
            val result = mcpRequest(CANCEL_ASYNC_SECONDS, { HostMessages.cancelAsyncMessage(it, uuid) }) { McpResult.Ok(cancelAsyncAnswer(it)) }
            LOG.info("cancel_async_message $uuid: ${(result as? McpResult.Ok)?.value ?: result.javaClass.simpleName}")
            when {
                result is McpResult.Ok && result.value == true -> dispatch(SessionEvent.SendWithdrawn(uuid, restore, notice = null))
                // The process is over: its end gave every waiting message back already.
                result is McpResult.Failed && result.error == McpError.Ended -> {}
                else -> dispatch(SessionEvent.WithdrawRefused(uuid, ((result as? McpResult.Failed)?.error as? McpError.Rejected)?.message, send.text))
            }
            advanceShell()
        }
    }

    /**
     * Interrupts the turn in progress. While a shell escape's output frame still waits for its command frame's quiet result, no model
     * call runs yet: the frame is dropped instead, the turn it would have started ends as stopped, and no request goes out.
     *
     * Stop stops everything (spec 12.3): held prompts go back to the composer, and while a message waits in the CLI's queue and its
     * init listed [CANCEL_QUEUED_CAPABILITY], the `interrupt` carries `cancel_queued`; each message its receipt cancelled, and each
     * still waiting that the receipt names nowhere, comes back too. Otherwise the frame is today's, byte for byte.
     */
    fun interrupt() {
        val dropped = synchronized(shellLock) {
            val prompts = held.filterIsInstance<Held.Prompt>()
            held.removeAll(prompts)
            prompts.forEach { returnStopped(it.uuid) }
            pendingOutput?.also {
                pendingOutput = null
                it.fallback?.cancel()
                dispatch(SessionEvent.ShellReplyDropped(it.id))
            }
        }
        if (dropped != null) {
            advanceShell()
            return
        }
        val chat = _state.value
        val cancelQueued = CANCEL_QUEUED_CAPABILITY in chat.cliCapabilities.orEmpty() && chat.pendingSends.any { it.stage == PendingStage.QUEUED }
        if (!cancelQueued) {
            send(HostMessages.interrupt(newRequestId()))
            return
        }
        scope.launch(CoroutineName("ClaudeSession.interrupt")) {
            val reply = request(INTERRUPT_SECONDS * mcpMillisPerSecond) { HostMessages.interrupt(it, cancelQueued = true) }
            if (reply !is Reply.Answered) {
                LOG.info("interrupt with cancel_queued: ${reply.javaClass.simpleName}")
                return@launch
            }
            if (!reply.response.isSuccess) {
                dispatch(SessionEvent.Cli(reply.response, clock())) // the chat says the CLI rejected it, as for any request
                return@launch
            }
            val receipt = withContext(Dispatchers.Default) { interruptReceipt(reply.response.payload) }
            LOG.info("interrupt receipt: ${receipt.cancelled.size} cancelled, ${receipt.stillQueued.size} still queued")
            receipt.cancelled.forEach(::returnStopped)
            // No longer held by the CLI, and no later line would clear it: Stop always clears what waits.
            _state.value.pendingSends.filter { it.stage == PendingStage.QUEUED && it.uuid !in receipt.stillQueued && it.uuid !in receipt.cancelled }
                .forEach { returnStopped(it.uuid) }
            advanceShell()
        }
    }

    /** The waiting message [uuid] goes back to the composer with Stop's notice; nothing once it no longer waits. */
    private fun returnStopped(uuid: String) {
        val send = _state.value.pendingSends.firstOrNull { it.uuid == uuid } ?: return
        dispatch(SessionEvent.SendWithdrawn(uuid, restore = true, notice = notSentText(send.text)))
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
            val escape = Held.Escape(id, command, output, respond)
            val place = held.indexOfFirst { it is Held.Shell && it.id == id }
            when {
                // Read under shellLock: set before dropShellFrames takes it, so an escape not dropped there is seen as unsent here.
                ended -> {
                    if (place >= 0) held.removeAt(place)
                    notice(NoticeLevel.WARNING, unsentShellText(command))
                }
                // The escape takes its command's place, so the prompts sent while it ran go out after its frames (spec 12.4).
                place >= 0 -> held[place] = escape
                else -> held.addLast(escape)
            }
        }
        advanceShell()
    }

    /**
     * Shell escape [id] ended without sending anything (not started, failed, killed at the IDE's exit, the session closing): its
     * place leaves [held], so the prompts behind it go out now, never held with nothing to release them.
     */
    fun shellUnsent(id: String) {
        synchronized(shellLock) { held.removeAll { it is Held.Shell && it.id == id } }
        advanceShell()
    }

    /**
     * Applies [event] to this chat, as one of the CLI's lines would be: a shell escape's progress, which Tandem runs itself. A command
     * that starts takes its place among the held frames (spec 12.4).
     */
    internal fun event(event: SessionEvent) {
        if (event is SessionEvent.ShellStarted) {
            synchronized(shellLock) {
                held.addLast(Held.Shell(event.id))
                dispatch(event)
            }
        } else {
            dispatch(event)
        }
    }

    private fun startEscapeLocked(escape: Held.Escape) {
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

    /** Sends [pending] if it still waits; then what is held behind it may go. */
    private fun sendOutput(pending: PendingOutput) {
        synchronized(shellLock) {
            if (pendingOutput !== pending) return
            pendingOutput = null
            pending.fallback?.cancel()
            send(pending.frame)
        }
        advanceShell()
    }

    /**
     * After each result or lifecycle line the reader reduced, and whenever something held may go: the output frame goes out once its
     * quiet send's result is in; then, from the head of [held], prompts are written once no output frame waits and no quiet result is
     * due, and escapes start, one at a time, once no turn is in progress and no message waits in the CLI's queue (spec 12.4 rule 1: a
     * `later` message starts right after the running turn's result and would race the escape's quiet frame). A running command's
     * place holds everything behind it.
     */
    private fun advanceShell() {
        synchronized(shellLock) {
            val pending = pendingOutput
            if (pending != null && pending.quiet !in _state.value.quietSends) {
                pendingOutput = null
                pending.fallback?.cancel()
                send(pending.frame)
            }
            while (true) {
                val chat = _state.value
                when (val head = held.firstOrNull() ?: break) {
                    is Held.Shell -> break
                    is Held.Prompt -> {
                        if (pendingOutput != null || chat.quietSends.isNotEmpty()) break
                        held.removeFirst()
                        send(head.frame)
                        dispatch(SessionEvent.SendReleased(head.uuid))
                        LOG.info("held prompt ${head.uuid} released")
                    }
                    is Held.Escape -> {
                        if (pendingOutput != null || chat.turn != null || chat.pendingSends.any { it.stage == PendingStage.QUEUED }) break
                        held.removeFirst()
                        startEscapeLocked(head)
                    }
                }
            }
        }
    }

    /**
     * The process is over: no shell escape frame goes out any more, and no held prompt is written (each comes back through the end or
     * the settled chat). Returns the commands of the escapes whose output never went out, in order: the one whose output frame waited
     * for its quiet result if [withPending], then those still held.
     */
    private fun dropShellFrames(withPending: Boolean): List<String> =
        synchronized(shellLock) {
            val pending = pendingOutput
            pending?.fallback?.cancel()
            pendingOutput = null
            val escapes = held.filterIsInstance<Held.Escape>().map { it.command }
            held.clear()
            listOfNotNull(pending?.command?.takeIf { withPending }) + escapes
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
     * The session's context window by category (`get_context_usage`, 30 s). A success also replaces [ChatState.contextBreakdown]; one
     * without `categories` is [McpError.Rejected].
     */
    suspend fun contextUsage(): ControlResult<ContextBreakdown> {
        val result = mcpRequest(CONTEXT_USAGE_SECONDS, HostMessages::getContextUsage) { payload ->
            ControlPayloads.contextUsage(payload)?.let { McpResult.Ok(it) }
                ?: McpResult.Failed(McpError.Rejected("the CLI's answer had no categories"))
        }
        if (result is McpResult.Ok) dispatch(SessionEvent.ContextBreakdownListed(result.value))
        LOG.info("get_context_usage: ${result.javaClass.simpleName}")
        return result
    }

    /**
     * This session's usage (`get_usage`): with [skipBehaviors] the session block only (15 s), without it also the scan of this
     * machine's transcripts (60 s). A success without an object payload is [McpError.Rejected].
     */
    suspend fun usage(skipBehaviors: Boolean): ControlResult<UsageAnswer> {
        val seconds = if (skipBehaviors) USAGE_SESSION_SECONDS else USAGE_HISTORY_SECONDS
        val result = mcpRequest(seconds, { HostMessages.getUsage(it, skipBehaviors) }) { payload ->
            ControlPayloads.usage(payload)?.let { McpResult.Ok(it) } ?: McpResult.Failed(McpError.Rejected("the CLI's answer had no session"))
        }
        LOG.info("get_usage skip_behaviors=$skipBehaviors: ${result.javaClass.simpleName}")
        return result
    }

    /**
     * Asks again for [part] of the usage card of [uuid] ([SessionEvent.UsageRetry] first): [UsagePart.SESSION] sends both requests,
     * as the first fetch did, [UsagePart.HISTORY] the full request alone.
     */
    fun retryUsage(uuid: String, part: UsagePart) {
        dispatch(SessionEvent.UsageRetry(uuid, part))
        when (part) {
            UsagePart.SESSION -> fetchUsage(uuid)
            UsagePart.HISTORY -> scope.launch(CoroutineName("ClaudeSession.usage")) {
                dispatch(SessionEvent.UsageFetched(uuid, UsagePart.HISTORY, usage(skipBehaviors = false)))
            }
        }
    }

    /**
     * Fills the usage card of [uuid], in this session's scope: the session block first, then, unless the process ended meanwhile,
     * the full request with the scan (spec 3.2). A part whose answer cannot come any more reads [McpError.Ended].
     */
    private fun fetchUsage(uuid: String) {
        scope.launch(CoroutineName("ClaudeSession.usage")) {
            dispatch(SessionEvent.UsageFetched(uuid, UsagePart.SESSION, usage(skipBehaviors = true)))
            if (!ended) dispatch(SessionEvent.UsageFetched(uuid, UsagePart.HISTORY, usage(skipBehaviors = false)))
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
        endCalls()
    }

    /**
     * The process is over: every `tools/call` still open is cancelled with no reply (nobody reads it), and [hostTools] closes this
     * session's diffs and queued calls ([HostTools.cancelAll]); once per process (spec 7.11).
     */
    private fun endCalls() {
        if (!callsEnded.compareAndSet(false, true)) return
        val open = calls.keys.toList()
        open.forEach { calls.remove(it)?.cancel() }
        LOG.info("process over: ${open.size} tool calls withdrawn")
        hostTools?.cancelAll(sessionKey())
    }

    /** The session the calls of this process belong to: the id its init reported, else the one it was started for. */
    private fun sessionKey(): SessionId = _reportedSessionId.value ?: config.sessionId ?: config.resumeSessionId.orEmpty()

    /**
     * Answers [request], an `mcp_message` of control request [requestId]: one for Tandem's server goes to [TandemTools], each
     * `tools/call` in a job of its own keyed by its JSON-RPC id (the reader goes on); any other server's gets `-32601`, never nothing.
     */
    private suspend fun serveMcp(requestId: String, request: InboundRequest.McpMessage) {
        val rpc = jsonRpcRequest(request.message)
        if (request.serverName != TandemTools.SERVER_NAME) {
            LOG.info("mcp_message for unknown server ${request.serverName}: ${rpc?.method}")
            send(HostMessages.mcpResponse(requestId, jsonRpcError(rpc?.id, METHOD_NOT_FOUND, "Method not found: ${rpc?.method.orEmpty()}")))
            return
        }
        LOG.debug { "mcp_message ${rpc?.method} id=${rpc?.id}" }
        val reply = TandemTools.answer(request.message, version, ::callHostTools, ::cancelCall)
        val id = rpc?.id
        if (rpc?.method != "tools/call" || id == null) {
            reply()?.let { send(HostMessages.mcpResponse(requestId, it)) }
            return
        }
        val job = scope.launch(Dispatchers.Default + CoroutineName("ClaudeSession.toolCall"), start = CoroutineStart.LAZY) {
            val answer = reply() ?: return@launch
            // Withdrawn meanwhile (notifications/cancelled, the process's end): the cancel took the entry, and no reply goes out.
            if (calls.remove(id, currentCoroutineContext().job)) send(HostMessages.mcpResponse(requestId, answer))
        }
        if (calls.put(id, job) != null) LOG.info("tools/call id=$id again while one runs: only the newer one answers")
        if (callsEnded.get()) {
            // The process ended meanwhile: a lazy job cancelled before its start never runs, so the host never sees the call.
            calls.remove(id, job)
            job.cancel()
            return
        }
        job.invokeOnCompletion { calls.remove(id, job) }
        job.start()
    }

    /** [hostTools]' answer to the call [name] with [arguments]; a host that fails answers an error, so the CLI never waits. */
    private suspend fun callHostTools(name: String, arguments: JsonObject, toolUseId: String?, id: JsonElement?): ToolReply? {
        val tools = hostTools ?: return ToolReply(NO_HOST_TOOLS_TEXT, isError = true)
        val requestId = id ?: return null
        return try {
            tools.call(ToolCall(name, arguments, toolUseId, requestId, sessionKey()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("tools/call $name id=$requestId tool_use=$toolUseId failed in the IDE", e)
            ToolReply("Tandem could not run $name: ${e.javaClass.simpleName}.", isError = true)
        }
    }

    /** `notifications/cancelled` for [requestId]: its job is cancelled with no reply, and [hostTools] withdraws the call. */
    private fun cancelCall(requestId: JsonElement) {
        val job = calls.remove(requestId)
        LOG.info("tools/call id=$requestId cancelled by the CLI${if (job == null) " (not open)" else ""}")
        hostTools?.cancel(sessionKey(), requestId) // first: the host knows the call is withdrawn before its job sees the cancellation
        job?.cancel()
    }

    /** The version gate (spec 4.2, deviation 1): an init that lists tools, none of them Tandem's edit tool, says so once per process. */
    private fun checkEditTools(init: CliMessage.SystemInit) {
        if (gateRaised || init.tools.isEmpty() || TANDEM_EDIT_TOOL in init.tools) return
        gateRaised = true
        LOG.info("init without $TANDEM_EDIT_TOOL (claude ${init.claudeCodeVersion})")
        notice(NoticeLevel.WARNING, editsOffNotice(init.claudeCodeVersion))
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
            val line = readOutput(p) { runInterruptible { reader.readLine() } } ?: return
            val at = clock()
            _lastFrameAtMillis.value = at
            if (line.isBlank()) continue
            val message = Ndjson.decode(line)
            when (message) {
                is CliMessage.Unknown -> LOG.debug { "unhandled line type=${message.type} subtype=${message.subtype} length=${line.length}" }
                is CliMessage.ControlRequest -> when (val request = message.request) {
                    // Tandem's own tool: allowed at once with its input, never a card (the diff is the question, spec 4.2).
                    is InboundRequest.CanUseTool -> if (request.toolName.startsWith(TANDEM_TOOL_PREFIX)) {
                        LOG.info("can_use_tool ${request.toolName} tool_use=${request.toolUseId}: allowed")
                        send(HostMessages.allow(message.requestId, request.toolUseId, request.input))
                        continue
                    }
                    is InboundRequest.McpMessage -> {
                        serveMcp(message.requestId, request)
                        continue
                    }
                    is InboundRequest.Other -> {
                        LOG.info("unsupported control request ${request.subtype}")
                        send(HostMessages.error(message.requestId, "Unsupported request: ${request.subtype}"))
                        // An MCP server's question (spec 2): answered as unsupported, as before, and no longer silently.
                        if (request.subtype == "elicitation") {
                            notice(NoticeLevel.WARNING, "An MCP server asked for input Tandem cannot show yet.")
                        }
                    }
                }
                // The answer to a request() that waits for it is that call's alone; any other goes to the reducer.
                is CliMessage.ControlResponse -> if (replies.remove(message.requestId)?.complete(Reply.Answered(message)) == true) continue
                else -> {}
            }
            dispatch(SessionEvent.Cli(message, at))
            if (message is CliMessage.SystemInit) {
                if (message.sessionId.isNotEmpty()) _reportedSessionId.value = message.sessionId
                checkEditTools(message)
            }
            // A usage line's numbers are asked for once its local turn has ended, between turns, as the probes sent them (spec 3.2).
            if (message is CliMessage.Assistant && message.isUsageLine()) pendingUsage = message.uuid
            if (message is CliMessage.Result) {
                pendingUsage?.let {
                    pendingUsage = null
                    fetchUsage(it)
                }
                advanceShell()
            }
            // A message that started or was cancelled no longer waits: an escape held behind it may start.
            if (message is CliMessage.CommandLifecycle) advanceShell()
        }
    }

    /**
     * [read], a read of [p]'s stdout or stderr; null if the output failed because the process is over. The JDK can close the pipe of
     * a process that exits under a waiting read, and [Process.destroyForcibly] closes them before the process is seen to exit: when
     * Tandem ended the process, or it is over within [CUT_OFF_GRACE_MILLIS], its output is over and the exit code tells the rest. A
     * failure while it runs on, and Tandem did not end it, is one, and is thrown.
     *
     * Cancelling a read in [runInterruptible] interrupts the reading thread: a stream that can be interrupted then throws
     * `InterruptedIOException`, an [IOException], and runInterruptible interrupts only once the cancellation has begun. While this is
     * being cancelled, that, or any I/O failure, is the cancellation, never a failure to show.
     */
    private suspend fun <T> readOutput(p: Process, read: suspend () -> T): T? =
        try {
            read()
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // being cancelled: this is the cancellation, thrown as such
            if (stopping || runInterruptible { p.waitFor(CUT_OFF_GRACE_MILLIS, TimeUnit.MILLISECONDS) }) null else throw e
        }

    /**
     * Reads [p]'s stderr to its end into [stderrTail], on a thread of [Dispatchers.IO] that this waits for only until it is cancelled.
     * A process the CLI started can keep the stderr open after the CLI is gone, and a read of a pipe can be neither interrupted nor
     * closed under it: that thread then ends when the last process that holds the pipe does, and nothing waits for it.
     */
    private suspend fun readStderr(p: Process) {
        val read = { p.errorStream.bufferedReader().useLines { lines -> lines.forEach { stderrTail.add(it) } } }
        CompletableFuture.runAsync(read, Dispatchers.IO.asExecutor()).await()
    }

    /** Writes [outbox] to [p]'s input, and closes the input once it ends: cancelled, or the input failed. */
    private suspend fun writeLoop(p: Process) {
        val writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
        try {
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
        } finally {
            // Here, not on the end's path: a write that waits on a full pipe holds the stream until it returns.
            closeInput(p)
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
        closeInput(p) // the CLI shuts down on its own
        if (p.isAlive && !p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
        }
        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
        LOG.info("claude stopped")
    }

    /**
     * Ends [p]'s input, also once [p] has exited: a process it started that reads the same pipe sees the end too. The JDK closes it
     * at the exit only after a read of the stderr that waits has returned, and such a process can be what keeps that read waiting.
     */
    private fun closeInput(p: Process) {
        runCatching { p.outputStream.close() }
    }

    private fun dispatch(event: SessionEvent) = _state.update { reduce(it, event) }

    private fun send(message: JsonObject) {
        outbox.trySend(message)
    }

    private fun newRequestId(): String = "tandem-${nextId.incrementAndGet()}"
}

/** The notice for a shell escape of [command] whose output never reached the CLI (hard rule 5). */
internal fun unsentShellText(command: String): String = "The output of ! ${command.lineSequence().first()} was not sent: the CLI ended first."
