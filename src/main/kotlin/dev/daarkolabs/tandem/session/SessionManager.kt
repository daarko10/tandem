package dev.daarkolabs.tandem.session

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.protocol.ContextBreakdown
import dev.daarkolabs.tandem.protocol.ImageBlock
import dev.daarkolabs.tandem.protocol.McpAuthStart
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.McpStatus
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.SlashCommand
import dev.daarkolabs.tandem.session.transcript.LoadedTranscript
import dev.daarkolabs.tandem.session.transcript.SessionIndex
import dev.daarkolabs.tandem.session.transcript.SessionSummary
import dev.daarkolabs.tandem.session.transcript.TranscriptFiles
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Looked up on every use, as [ShellRunner]'s is: a test's capturing factory must see every record of a shell escape's run. */
private val LOG: Logger
    get() = logger<SessionManager>()

/** How often the session list is read again where the transcript folders cannot be watched. */
private const val INDEX_POLL_MILLIS: Long = 30_000

/** The chat's warning, before the reason, when a session's transcript cannot be read, so that its history is empty. */
private const val NOT_READ_TEXT: String = "The session's history could not be read: "

/** The chat's warning, before the reason, when reading a session's transcript failed part way, after the history it shows. */
private const val PARTLY_READ_TEXT: String = "The session's history was read only partly: "

/** How long an ended sign-in wait stays in [SessionHandle.mcpAuth], so that the panel shows its outcome. */
private const val MCP_AUTH_OUTCOME_MILLIS: Long = 5_000

/** What a new session starts with; null leaves it to the CLI. */
data class NewSessionOptions(val model: String? = null, val effort: String? = null, val permissionMode: PermissionMode? = null)

/**
 * The slash commands a process offered: its `initialize` answer's [commands] ([ChatState.commands]), and the [terminal] ones its
 * latest `system/init` named ([ChatState.terminalCommands]), which the palette leaves out.
 */
data class CommandCatalogue(val commands: List<SlashCommand> = emptyList(), val terminal: Set<String> = emptySet())

/** What [SessionManager.forkResult] did: [Started] a fork, or [Refused] to, with the reason the chat shows (hard rule 5). */
sealed interface ForkResult {
    /** The fork, added, selected and starting. */
    data class Started(val handle: SessionHandle) : ForkResult

    /**
     * Nothing forked: [NO_TRANSCRIPT], there is no transcript to fork yet; [NOT_IN_HISTORY], the line asked for is no main-thread
     * reply line of the session's history; [BUSY], a turn started before the original's idle process could stop (spec 13.1).
     */
    enum class Refused : ForkResult { NO_TRANSCRIPT, NOT_IN_HISTORY, BUSY }
}

/**
 * The sessions of one project. Each open session has a [SessionHandle] in [sessions], which runs its own `claude` process
 * ([ClaudeSession]) while it is live; [summaries] lists every session the CLI has for the project, from [index].
 *
 * Coroutines: all of them run in [scope], which the caller owns (the project service's). Each session gets a child scope for
 * its own coroutines. Processes are children of [scope] itself, since one can move to another session (see below); cancelling
 * [scope] ends every process. Process and file work runs on [Dispatchers.IO].
 *
 * Threads: every function may be called from any thread, the EDT included. [create], [select], [send], [interrupt], [allow],
 * [deny], [setModel], [setPermissionMode] and [setEffort] never wait: they change state or queue work, and return. [open], [fork], [rename],
 * [stop], [close], [delete] and [refreshIndex] suspend until they are done.
 *
 * Processes: a session starts or stops one process at a time (its spawn lock). [send] to a stopped session starts one and
 * queues the prompt until it is attached, so prompts sent in quick succession start a single process and keep their order. A
 * process that cannot start, or that ends, leaves its error notice in the chat and the session STOPPED; the next prompt tries
 * again. Without a [binary] the chat shows the install notice instead. A session never has two processes, not even one that is
 * closing and opened again: [open] waits until the old one has ended.
 *
 * At most [maxLive] sessions run a process at once: before a process starts, the least recently used idle session is stopped
 * ([SessionSpawner.makeRoom]). If none is idle, a process the user asked for ([create], [fork], a prompt) starts anyway and its chat says the cap
 * was exceeded; a session that is only being opened ([open]) gets no process then, and starts with its first prompt.
 *
 * A process whose first `system/init` reports another session id than the one it was started for does not run that session:
 * its row is never relabelled. The row stays, STOPPED, and a new row for the reported id takes over the process ([SessionSpawner.takeOver]).
 *
 * The list follows [SessionIndex.changes]; where the folders cannot be watched, it is read again every 30 s.
 */
class SessionManager internal constructor(
    private val scope: CoroutineScope,
    private val index: SessionIndex,
    private val launcher: ProcessLauncher,
    /** The `claude` executable, or null if none is installed. Called off the EDT before each process starts. */
    private val binary: () -> String?,
    private val workingDirectory: Path,
    private val maxLive: () -> Int,
    private val clock: () -> Long,
    /** Reads a session's history; tests pass one that fails, since a test run as root cannot make a file unreadable. */
    private val readTranscript: suspend (Path) -> LoadedTranscript,
    /** Appends a rename's `custom-title` line; tests pass one that fails, for the same reason. */
    private val appendTitle: (transcript: Path, id: SessionId, title: String, time: Instant) -> Unit = TranscriptFiles::appendCustomTitle,
    /** Removes a transcript and its directory; tests pass one that waits, to delete while something else happens. */
    private val deleteFiles: (transcript: Path) -> Unit = TranscriptFiles::delete,
    /** How often a sign-in wait asks for the servers ([startMcpAuth]). */
    private val mcpAuthEvery: Duration = 2.seconds,
    /** How long a sign-in wait lasts at most. */
    private val mcpAuthTimeout: Duration = 3.minutes,
    /** Whether Claude replies to a shell escape's output (the setting), read when each command ends. */
    private val respondToShell: () -> Boolean = { true },
    /** Runs the shell escapes ([runShell]). */
    private val shell: ShellRunner = ShellRunner(environment = System::getenv),
) : McpControls, ContextControls {
    /** Reads histories with [TranscriptReader]. */
    constructor(
        scope: CoroutineScope,
        index: SessionIndex,
        launcher: ProcessLauncher,
        binary: () -> String?,
        workingDirectory: Path,
        maxLive: () -> Int,
        clock: () -> Long = System::currentTimeMillis,
        respondToShell: () -> Boolean = { true },
        shell: ShellRunner = ShellRunner(environment = System::getenv),
    ) : this(
        scope, index, launcher, binary, workingDirectory, maxLive, clock, TranscriptReader::read,
        respondToShell = respondToShell,
        shell = shell,
    )

    /** The source of shell escape ids, `shell-<n>`: distinct from a loaded entry's line uuid. */
    private val nextShell = AtomicLong()

    /** Set by [killAllNow]: the IDE exits, so a shell escape that ends sends no frames and starts no process. */
    @Volatile
    private var shuttingDown = false

    /** Guards changes of [sessions] and [selected] together, and a session's attention against a change of the selection. */
    private val lock = Any()
    private val mutableSessions = MutableStateFlow<List<SessionHandle>>(emptyList())
    private val mutableSelected = MutableStateFlow<SessionId?>(null)

    /** The index's last listing. */
    private val listing = MutableStateFlow<List<SessionSummary>>(emptyList())

    /** The models a process listed last, or those [seedModels] gave until one has. */
    private val mutableModels = MutableStateFlow<List<ModelOption>>(emptyList())

    /** The commands a process offered last, or those [seedCommands] gave until one has. */
    private val mutableCommands = MutableStateFlow(CommandCatalogue())

    /** The servers the last `mcp_status` answer of any process listed, cut to what the panel shows. */
    private val mutableKnownMcp = MutableStateFlow<List<McpServerInfo>>(emptyList())

    /** One listing at a time, so that an older one never replaces a newer one. */
    private val refreshLock = Mutex()

    /** Sessions being closed, until their process has ended: [open] waits for them. Guarded by [lock]. */
    private val closing = HashMap<SessionId, CompletableJob>()

    /**
     * How often each session was marked closing ([markClosing]), so that an [open] whose history read overlapped a whole close or
     * delete, one that came and went meanwhile, reads it again. Guarded by [lock].
     */
    private val closings = HashMap<SessionId, Long>()

    /**
     * Per session: the `mcp_status` run in flight ([Coalesced.running]) and the follow-up run that callers arriving meanwhile share
     * ([Coalesced.next]). Weak keys, so a closed session's entry goes with its handle. Guarded by [lock].
     */
    private val mcpRefreshes = WeakHashMap<SessionHandle, Coalesced<List<McpServerInfo>>>()

    /** Per session: the `get_context_usage` run in flight and its shared follow-up, as [mcpRefreshes]. Guarded by [lock]. */
    private val contextFetches = WeakHashMap<SessionHandle, Coalesced<ContextBreakdown>>()

    /** Per session: the sign-in wait of each server, by name ([startMcpAuth]). Guarded by [lock]. */
    private val mcpAuthJobs = WeakHashMap<SessionHandle, MutableMap<String, Job>>()

    /** Starts the sessions' processes. */
    private val spawner = SessionSpawner(
        scope, index, launcher, binary, workingDirectory, maxLive, clock, listing,
        sessions = { mutableSessions.value },
        addTakenOver = ::addTakenOver,
        beforeAddTakenOver = { beforeTakenOverAdded(it) },
    )

    /** Runs between a resumed-as-new session's row taking its process over and its being added: tests let a turn end there. */
    @Volatile
    internal var beforeTakenOverAdded: suspend (SessionHandle) -> Unit = {}

    /** The open sessions, in the order they were opened. */
    val sessions: StateFlow<List<SessionHandle>> = mutableSessions.asStateFlow()

    /**
     * The session the user looks at, or null. Changed by [select]; by [fork], which selects the fork; by [close] and [delete], which
     * select a neighbour of the session they drop; and when a resume comes back as another session, whose new row is selected if
     * the old one was ([SessionSpawner.takeOver]).
     */
    val selected: StateFlow<SessionId?> = mutableSelected.asStateFlow()

    /**
     * Every session of the project: the index's listing, newest first, after the open sessions it does not list yet (the CLI
     * writes a transcript with the first prompt). Those come first, the last opened first, with their [SessionHandle.title], no
     * file and the time now as [SessionSummary.lastModified]. Every open session shows its [SessionHandle.title], so that a rename
     * shows here at once, before the index has read it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val summaries: StateFlow<List<SessionSummary>> =
        combine(listing, mutableSessions.flatMapLatest(::titles)) { listed, open -> merge(listed, open) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The models the CLI offers, as the process that answered `initialize` last listed them; until one has, those [seedModels] gave
     * (remembered from an earlier IDE run); empty while there are neither. Every session runs the same `claude`, so a session whose
     * own process has not listed them ([ChatState.models]: it has none yet, a restored session for one) offers these. Nothing starts
     * a process to read them.
     */
    val models: StateFlow<List<ModelOption>> = mutableModels.asStateFlow()

    /**
     * The slash commands the CLI offers, as [models] are kept: the `commands` of the `initialize` answer a process gave last, and the
     * terminal-only names of the latest `system/init` a process reported (each part kept until a process gives it again); until a
     * process has, those [seedCommands] gave (remembered from an earlier IDE run); empty while there are neither. A session whose own
     * process has not offered them ([ChatState.commands]: it has none yet, a restored session for one) offers these in its palette.
     * Nothing starts a process to read them.
     */
    val commands: StateFlow<CommandCatalogue> = mutableCommands.asStateFlow()

    override val knownMcpServers: StateFlow<List<McpServerInfo>> = mutableKnownMcp.asStateFlow()

    init {
        // The first signal comes once the folders are watched, so it also gives the first listing.
        scope.launch(CoroutineName("SessionManager.index")) {
            index.changes().collect { refreshIndex() }
            LOG.info("transcript folders are not watched; the session list is read every ${INDEX_POLL_MILLIS / 1_000} s")
            while (true) {
                refreshIndex()
                delay(INDEX_POLL_MILLIS)
            }
        }
    }

    /** Starts a new session with [options] (`--session-id` with a new UUID) and returns its handle at once. It is not selected. */
    fun create(options: NewSessionOptions): SessionHandle {
        val initial = ChatState(requestedModel = options.model, permissionMode = options.permissionMode, effort = options.effort)
        val (handle, _) = synchronized(lock) { registerLocked(UUID.randomUUID().toString(), initial) }
        spawner.startInBackground(handle, forPrompts = false)
        return handle
    }

    /**
     * Session [id]'s handle. One that is open is returned as it is: a stopped session starts again with its next prompt ([send]).
     * Otherwise a new handle gets the history from the session's transcript (none if there is no transcript yet, and a warning
     * in the chat if it cannot be read, or only partly) and, with [spawn], a process if the cap leaves room for one: when [maxLive]
     * sessions run a process and none of them is idle, the session stays STOPPED, without a notice, until its first prompt. A
     * session that is closing is opened again only once its process has ended. The selection does not change.
     */
    suspend fun open(id: SessionId, spawn: Boolean = true): SessionHandle {
        while (true) {
            val (pendingClose, closed) = synchronized(lock) { closing[id] to closings[id] }
            if (pendingClose != null) {
                pendingClose.join()
                continue
            }
            handle(id)?.let { return it }
            val history = history(id)
            // Meanwhile a close may have started, or come and gone (a delete, say), or another open may have added the session.
            val (handle, isNew) = synchronized(lock) {
                if (closing[id] == null && closings[id] == closed) registerLocked(id, history) else null
            } ?: continue
            if (isNew && spawn) spawner.start(handle, forPrompts = false, overCapAllowed = false)
            return handle
        }
    }

    /**
     * Forks session [id]: a new session, with a new id, that goes on from [id]'s history while [id] stays as it is, or, with
     * [atMessage], from that history up to the end of the turn whose main-thread reply holds the line [atMessage]
     * ([ChatState.forkedAt], `--resume-session-at`, spec 13.1). The fork is added to [sessions], selected and returned at once,
     * and its process starts in the background, as for [create]. Null when nothing forked; [forkResult] says why.
     */
    suspend fun fork(id: SessionId, atMessage: String? = null): SessionHandle? =
        (forkResult(id, atMessage) as? ForkResult.Started)?.handle

    /**
     * [fork], with the reason when nothing forked: [ForkResult.Refused.NO_TRANSCRIPT] when there is no transcript to fork yet,
     * [ForkResult.Refused.NOT_IN_HISTORY] when [atMessage] is no main-thread reply line of [id]'s history.
     *
     * The transcript forked is [id]'s; a fork with none of its own yet (no prompt sent) shows its original's lines, so it forks its
     * original again, at its own cut unless [atMessage] names another. The fork starts from [id]'s settled history: the open
     * session's chat, else its transcript's. Its title is "<title of [id]> (fork)" at once, and the CLI is told so
     * (`rename_session`) as soon as the fork's process reports its first `system/init`, which comes with its first prompt: the CLI
     * does not title a fork itself. [SessionSpawner.configFor] says how a fork starts. Tandem never falls back to a whole fork: an
     * argv the CLI refuses shows in the fork's chat through the result and exit paths.
     */
    suspend fun forkResult(id: SessionId, atMessage: String? = null): ForkResult {
        val original = handle(id)
        val own = withContext(Dispatchers.IO) { index.transcript(id) }
        val inherited = original?.forkOf?.takeIf { own == null }
        val source = inherited ?: id
        val file = own
            ?: inherited?.let { withContext(Dispatchers.IO) { index.transcript(it) } }
            ?: return ForkResult.Refused.NO_TRANSCRIPT
        val (history, title) = if (original != null) {
            // A message waiting in the original stays the original's (spec 12.5).
            original.state.value.forkHistory() to original.title.value
        } else {
            val (loaded, loadedTitle) = load(id, file)
            loaded to (loadedTitle ?: id)
        }
        val start = if (atMessage == null) history else history.forkedAt(atMessage) ?: return ForkResult.Refused.NOT_IN_HISTORY
        val cut = atMessage ?: original?.forkAt?.takeIf { inherited != null }
        val forkId = UUID.randomUUID().toString()
        val fork = synchronized(lock) {
            // Its id comes with its first system/init: until then the state carries none, not the original's.
            val (fork, _) =
                registerLocked(forkId, start.copy(sessionId = null), forkOf = source, forkAt = cut, pendingTitle = "$title (fork)")
            selectLocked(forkId)
            fork
        }
        spawner.startInBackground(fork, forPrompts = false)
        LOG.info("session $id forked as $forkId" + if (cut != null) " at a reply" else " whole")
        return ForkResult.Started(fork)
    }

    /** Offers [models], the list remembered from an earlier IDE run, as [SessionManager.models] until a process lists them. */
    fun seedModels(models: List<ModelOption>) {
        mutableModels.compareAndSet(emptyList(), models)
    }

    /** Offers [catalogue], remembered from an earlier IDE run, as [SessionManager.commands] until a process offers its own. */
    fun seedCommands(catalogue: CommandCatalogue) {
        mutableCommands.compareAndSet(CommandCatalogue(), catalogue)
    }

    /** Selects session [id], or none; the selected session's [SessionHandle.attention] clears. An [id] that is not open is ignored. */
    fun select(id: SessionId?) {
        synchronized(lock) { selectLocked(id) }
    }

    /**
     * Sends a prompt: [text] and the [images] attached to it, in order, with `"priority":"later"` when [later]. A stopped session
     * starts a process first, and gets the prompt once it is up.
     */
    fun send(id: SessionId, text: String, images: List<ImageBlock> = emptyList(), later: Boolean = false) {
        val handle = handle(id) ?: return
        handle.lastUsed = clock()
        if (handle.sendOrQueue(Outbound.Prompt(text, images, later))) spawner.startInBackground(handle, forPrompts = true)
    }

    /**
     * Withdraws the waiting message [uuid] of session [id] (spec 12.3): through its live process ([ClaudeSession.withdraw]). Without
     * one nothing: the stopped chat gave every waiting message back already.
     */
    fun withdraw(id: SessionId, uuid: String, restore: Boolean) {
        handle(id)?.liveSession()?.withdraw(uuid, restore)
    }

    /** The composer of session [id] took the returned messages [uuids] back: they leave the live process's state, or the stopped chat. */
    fun takeReturned(id: SessionId, uuids: List<String>) {
        val event = SessionEvent.ReturnedTaken(uuids)
        handle(id)?.pick(live = { it.event(event) }, stopped = event)
    }

    /**
     * Runs [command], a shell escape (`! command`), in the login shell in the project directory, off the EDT, and shows it in the
     * chat as it runs (spec 12). Once it ends, its frames go to the CLI ([ClaudeSession.sendShellEscape]), started first if no process
     * runs, as a prompt starts one; Claude replies when [respondToShell] says so. A command that did not start, or whose output could
     * not be read, says so in its row and sends nothing, as one the IDE's exit kills ([killAllNow]); one stopped ([stopShell]) or
     * capped sends its output with a note. Ignored while a command runs in this session, and once it is closed.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun runShell(id: SessionId, command: String) {
        val handle = handle(id) ?: return
        handle.lastUsed = clock()
        handle.startShell {
            val shellId = "shell-${nextShell.incrementAndGet()}"
            val started = clock()
            handle.shellEvent(SessionEvent.ShellStarted(shellId, command, started))
            // ATOMIC: the body runs even when the job is cancelled before it is dispatched, so that its entry still ends Stopped.
            handle.scope.launch(Dispatchers.IO + CoroutineName("SessionManager.shell"), start = CoroutineStart.ATOMIC) {
                runShellJob(handle, shellId, command, started)
            }
        }
    }

    /** Stops the shell escape that runs in session [id], if any: its tree is killed, and its output goes out with a note. */
    fun stopShell(id: SessionId) {
        handle(id)?.shellJob?.cancel()
    }

    /** The body of [runShell]'s job: it always ends the entry, once. */
    private suspend fun runShellJob(handle: SessionHandle, shellId: String, command: String, started: Long) {
        val soFar = StringBuilder()
        val result = try {
            shell.run(command, workingDirectory) { text ->
                soFar.append(text)
                handle.shellEvent(SessionEvent.ShellOutput(shellId, text))
            }
        } catch (e: CancellationException) {
            val ms = clock() - started // read once: the row and the frame say the same
            withContext(NonCancellable) {
                handle.shellEvent(SessionEvent.ShellEnded(shellId, ShellEnd.Stopped, ms))
                LOG.info("shell escape stopped after $ms ms")
                // A closed session, a project that closes (its scope cancelled without a close) or the IDE's exit sends nothing.
                if (!shuttingDown && !handle.isClosed && handle.scope.isActive) {
                    sendShellFrames(handle, shellId, command, withNote(soFar.toString(), "Stopped after ${elapsed(ms)}"))
                } else {
                    // Its place among the held frames goes: the prompts behind it are not held with nothing to release them.
                    handle.liveSession()?.shellUnsent(shellId)
                }
            }
            throw e
        } catch (e: Exception) {
            handle.shellEvent(SessionEvent.ShellEnded(shellId, ShellEnd.Failed(e.message ?: e.javaClass.simpleName), clock() - started))
            LOG.info("shell escape failed: ${e.javaClass.simpleName}")
            handle.liveSession()?.shellUnsent(shellId)
            return
        }
        handle.shellEvent(SessionEvent.ShellEnded(shellId, result.end, result.durationMs))
        // Not started: nothing ran. Stopped here means killAllNow killed it (the IDE exits): no claude may start now.
        if (result.end is ShellEnd.NotStarted || result.end is ShellEnd.Stopped || shuttingDown) {
            handle.liveSession()?.shellUnsent(shellId)
            return
        }
        val output = if (result.end is ShellEnd.TimedOut) withNote(result.output, "Killed after 10 minutes") else result.output
        sendShellFrames(handle, shellId, command, output)
    }

    /** Sends a shell escape's frames to [handle]'s process, or queues them and starts one. */
    private fun sendShellFrames(handle: SessionHandle, shellId: String, command: String, output: String) {
        if (handle.sendOrQueue(Outbound.ShellEscape(shellId, command, output, respondToShell()))) {
            spawner.startInBackground(handle, forPrompts = true)
        }
    }

    /** Interrupts the turn in progress, if a process runs. */
    fun interrupt(id: SessionId) {
        handle(id)?.liveSession()?.interrupt()
    }

    /**
     * Answers the pending permission request [requestId] with [answer] ([PermissionAnswers]). Nothing happens when no process runs
     * or the request is no longer pending (answered already, cancelled, or its turn ended), so a second click writes no frame.
     */
    fun answer(id: SessionId, requestId: String, answer: PermissionAnswer) {
        answerPending(id, requestId) { answer }
    }

    /** Allows the pending permission request [requestId]; [always] allows the same again for the rest of the session. */
    fun allow(id: SessionId, requestId: String, always: Boolean) {
        answerPending(id, requestId) { if (always) PermissionAnswers.allowForSession(it) else PermissionAnswers.allowOnce(it) }
    }

    /** Denies the pending permission request [requestId]. */
    fun deny(id: SessionId, requestId: String) {
        answerPending(id, requestId) { PermissionAnswers.deny() }
    }

    private inline fun answerPending(id: SessionId, requestId: String, answer: (PendingPermission) -> PermissionAnswer) {
        val session = handle(id)?.liveSession() ?: return
        val pending = session.state.value.pendingPermissions.firstOrNull { it.requestId == requestId } ?: return
        session.answer(pending, answer(pending))
    }

    /** Picks a model (null: the CLI's default). A running process switches (`set_model`); a stopped session starts with it. */
    fun setModel(id: SessionId, model: String?) {
        handle(id)?.pick(live = { it.setModel(model) }, stopped = SessionEvent.ModelRequested(model))
    }

    /** Picks a permission mode. A running process switches (`set_permission_mode`); a stopped session starts with it. */
    fun setPermissionMode(id: SessionId, mode: PermissionMode) {
        handle(id)?.pick(live = { it.setPermissionMode(mode) }, stopped = SessionEvent.PermissionModeRequested(mode))
    }

    /**
     * Picks an effort. A running process changes it from its next turn (`apply_flag_settings`); a stopped session starts with it
     * (`--effort`).
     */
    fun setEffort(id: SessionId, level: String) {
        handle(id)?.pick(live = { it.setEffort(level) }, stopped = SessionEvent.EffortRequested(level))
    }

    /**
     * Renames session [id] to [title], trimmed; a blank title is ignored. An open session shows the new title at once
     * ([SessionHandle.title]). A running process that has reported its first `system/init` is asked to rename the session
     * (`rename_session`), and the CLI appends the `custom-title` line to the transcript itself. Otherwise Tandem appends the line the
     * SDK's `renameSession` appends ([TranscriptFiles.appendCustomTitle]); an open session without a transcript yet keeps the
     * title until its next process reports its first `system/init`, and the CLI is told then. A process that has not reported its
     * first `system/init` yet is also told, with that init, as a fork is its title, and so is a session whose earlier title is still
     * to be told: the last title wins ([SessionHandle.rename]). Either way the index then reads the transcript again. Throws the
     * [IOException] of a line that cannot be appended, and the title shown before the rename comes back.
     */
    suspend fun rename(id: SessionId, title: String) {
        val name = title.trim()
        if (name.isEmpty()) return
        val handle = handle(id)
        val transcript = withContext(Dispatchers.IO) { index.transcript(id) }
        val sent = handle?.rename(name, noTranscript = transcript == null) ?: false
        try {
            if (!sent && transcript != null) {
                try {
                    withContext(Dispatchers.IO) { appendTitle(transcript, id, name, Instant.ofEpochMilli(clock())) }
                } catch (e: IOException) {
                    handle?.revertRename(name)
                    throw e
                }
            }
        } finally {
            index.invalidate(id)
            refreshIndex()
        }
        LOG.info("session $id renamed")
    }

    /** Ends the session's process, if one runs, and returns once it is gone. The session stays open, STOPPED, with its chat. */
    suspend fun stop(id: SessionId) {
        val handle = handle(id) ?: return
        handle.stop()
        LOG.info("session $id stopped")
    }

    /**
     * Stops the session and drops it from [sessions]; its transcript stays. If it was selected, a neighbour is: the next row in
     * [sessions], else the previous one, else none. Returns once the process has ended, as does a second close meanwhile.
     */
    suspend fun close(id: SessionId) {
        if (closeThen(id) {}) LOG.info("session $id closed")
    }

    /**
     * Deletes session [id]: stops its process if one runs, drops it from [sessions] (if it was selected, a neighbour is, as for
     * [close]) and removes its transcript `<id>.jsonl` and the directory `<id>/` beside it, with everything in it. Only the
     * transcript that [SessionIndex.transcript] finds goes, with its directory, and links are never followed, so nothing outside the
     * transcript folders is touched. A session that is not open loses its files only. Returns once the process has ended and the
     * files are gone; throws the [IOException] of a file that cannot be removed.
     */
    suspend fun delete(id: SessionId) {
        closeThen(id) {
            try {
                withContext(Dispatchers.IO) { index.transcript(id)?.let(deleteFiles) }
            } finally {
                // Still marked closing: an open waiting for the delete comes after the list no longer has the session.
                index.invalidate(id)
                refreshIndex()
            }
        }
        LOG.info("session $id deleted")
    }

    /** Lists the transcript folders again, on [Dispatchers.IO], and updates [summaries] and the titles. */
    suspend fun refreshIndex() {
        refreshLock.withLock { listing.value = withContext(Dispatchers.IO) { index.list() } }
    }

    override suspend fun refreshMcp(id: SessionId): McpResult<List<McpServerInfo>> {
        val handle = handle(id) ?: return McpResult.Failed(McpError.NotRunning)
        handle.liveSession() ?: return McpResult.Failed(McpError.NotRunning)
        return coalesced(handle, mcpRefreshes, "SessionManager.mcpStatus") {
            val listed = handle.mcpActions.withLock { handle.liveSession()?.mcpStatus() ?: McpResult.Failed(McpError.NotRunning) }
            if (listed is McpResult.Ok) mutableKnownMcp.value = listed.value.map { it.lastKnown() }
            listed
        }
    }

    /** Not under [SessionHandle.mcpActions]: a breakdown changes nothing, so it need not wait for an MCP action. */
    override suspend fun contextBreakdown(id: SessionId): ControlResult<ContextBreakdown> {
        val handle = handle(id) ?: return McpResult.Failed(McpError.NotRunning)
        handle.liveSession() ?: return McpResult.Failed(McpError.NotRunning)
        return coalesced(handle, contextFetches, "SessionManager.contextUsage") {
            handle.liveSession()?.contextUsage() ?: McpResult.Failed(McpError.NotRunning)
        }
    }

    /**
     * Asks again for [part] of the usage card of [uuid] in session [id] ([ClaudeSession.retryUsage]); without a live process the
     * part reads [McpError.NotRunning] at once.
     */
    fun retryUsage(id: SessionId, uuid: String, part: UsagePart) {
        val handle = handle(id) ?: return
        handle.pick(
            live = { it.retryUsage(uuid, part) },
            stopped = SessionEvent.UsageFetched(uuid, part, McpResult.Failed(McpError.NotRunning)),
        )
    }

    /**
     * [run]'s answer for [handle], one run in flight at a time: a call made while one runs waits for, and shares, one follow-up run
     * ([Coalesced.next]). [entries] holds each session's [Coalesced].
     */
    private suspend fun <T> coalesced(
        handle: SessionHandle,
        entries: WeakHashMap<SessionHandle, Coalesced<T>>,
        name: String,
        run: suspend () -> McpResult<T>,
    ): McpResult<T> {
        val answer = synchronized(lock) {
            val entry = entries.getOrPut(handle) { Coalesced() }
            if (entry.running != null) {
                entry.next ?: CompletableDeferred<McpResult<T>>().also { entry.next = it }
            } else {
                CompletableDeferred<McpResult<T>>().also { first -> startCoalesced(handle, entry, first, name, run) }
            }
        }
        return answer.await()
    }

    override suspend fun toggleMcp(id: SessionId, server: String, enabled: Boolean): McpResult<Unit> =
        mcpAction(id) { it.mcpToggle(server, enabled) }

    override suspend fun reconnectMcp(id: SessionId, server: String): McpResult<Unit> =
        mcpAction(id) { it.mcpReconnect(server) }

    override suspend fun startMcpAuth(id: SessionId, server: String, open: suspend (url: String) -> Unit): McpResult<McpAuthStart> {
        val handle = handle(id) ?: return McpResult.Failed(McpError.NotRunning)
        val session = handle.liveSession() ?: return McpResult.Failed(McpError.NotRunning)
        // In the session's scope: the caller (a panel that closes) only waits for the result, and cannot cut the sign-in off.
        val signIn = handle.scope.async(CoroutineName("SessionManager.mcpSignIn")) { signIn(id, handle, session, server, open) }
        return try {
            signIn.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the caller's own cancellation goes on
            McpResult.Failed(McpError.Ended) // the session's scope ended first
        }
    }

    override fun cancelMcpAuth(id: SessionId, server: String) {
        val handle = handle(id) ?: return
        synchronized(lock) { mcpAuthJobs[handle]?.remove(server) }?.cancel()
        handle.removeMcpAuth(server)
        LOG.info("session $id: sign-in wait for MCP server $server cancelled")
    }

    override suspend fun startForMcp(id: SessionId, overCap: Boolean): McpStart {
        val handle = handle(id) ?: return McpStart.Failed(CLOSED_TEXT)
        if (handle.liveSession() != null) return McpStart.Running
        handle.lastUsed = clock()
        // In the session's scope, as a sign-in: a panel that closes meanwhile cannot cut the start off half way.
        val started = handle.scope.async(CoroutineName("SessionManager.mcpStart")) {
            spawner.start(handle, forPrompts = false, overCapAllowed = overCap)
        }
        val outcome = try {
            started.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the caller's own cancellation goes on
            return McpStart.Failed(CLOSED_TEXT) // the session's scope ended first: it was closed
        }
        LOG.info("session $id: started for its MCP panel (${outcome.javaClass.simpleName})")
        return when (outcome) {
            SpawnOutcome.Running -> McpStart.Running
            SpawnOutcome.NoRoom -> McpStart.NoRoom(maxLive())
            is SpawnOutcome.Failed -> McpStart.Failed(outcome.reason)
            SpawnOutcome.Skipped -> McpStart.Failed(CLOSED_TEXT)
        }
    }

    /**
     * Kills every process this manager started that has not ended yet, and what it started, at once ([ClaudeSession.killNow]): those
     * of open sessions, and those still being ended, such as a closed or stopped session's. The last resort of a JVM shutdown hook,
     * which cannot wait for coroutines to end them. A shell escape it kills ends [ShellEnd.Stopped] and sends nothing, so that no
     * process starts after it. Never waits; safe to call from any thread, and more than once.
     */
    fun killAllNow() {
        shuttingDown = true // ahead of the kills: a command they end must not queue its frames and start a process
        spawner.killAllNow()
        shell.killAllNow()
    }

    private fun handle(id: SessionId): SessionHandle? = mutableSessions.value.firstOrNull { it.id == id }

    /** One MCP action of session [id]'s live process, under its [SessionHandle.mcpActions]. */
    private suspend fun <T> mcpAction(id: SessionId, request: suspend (ClaudeSession) -> McpResult<T>): McpResult<T> {
        val handle = handle(id) ?: return McpResult.Failed(McpError.NotRunning)
        val session = handle.liveSession() ?: return McpResult.Failed(McpError.NotRunning)
        return handle.mcpActions.withLock { request(session) }
    }

    /**
     * Starts [handle]'s runs of [run] in its scope, under [lock]: the first answers [first]; then, while callers arrived meanwhile
     * ([Coalesced.next]), one more run answers all of them. A run cut off by the session's end answers [McpError.Ended].
     */
    private fun <T> startCoalesced(
        handle: SessionHandle,
        refresh: Coalesced<T>,
        first: CompletableDeferred<McpResult<T>>,
        name: String,
        run: suspend () -> McpResult<T>,
    ) {
        val job = handle.scope.launch(CoroutineName(name)) {
            var target: CompletableDeferred<McpResult<T>>? = first
            try {
                while (target != null) {
                    target.complete(run())
                    target = synchronized(lock) {
                        refresh.next?.also { refresh.next = null } ?: run {
                            refresh.running = null
                            null
                        }
                    }
                }
            } finally {
                target?.complete(McpResult.Failed(McpError.Ended))
            }
        }
        refresh.running = job
        // A job cancelled before it ran never reaches its finally: whoever waits for it is answered here.
        job.invokeOnCompletion {
            val waiting = synchronized(lock) {
                if (refresh.running !== job) {
                    null
                } else {
                    refresh.running = null
                    refresh.next.also { refresh.next = null }
                }
            }
            first.complete(McpResult.Failed(McpError.Ended))
            waiting?.complete(McpResult.Failed(McpError.Ended))
        }
    }

    /**
     * [startMcpAuth] in [handle]'s scope: `mcp_authenticate`, then the browser and the wait for [server] to connect, or without a link
     * one look at the servers.
     */
    private suspend fun signIn(
        id: SessionId,
        handle: SessionHandle,
        session: ClaudeSession,
        server: String,
        open: suspend (url: String) -> Unit,
    ): McpResult<McpAuthStart> {
        val started = handle.mcpActions.withLock { session.mcpAuthenticate(server) }
        val start = when (started) {
            is McpResult.Failed -> {
                signInFailed(handle, server, started.error)
                return started
            }
            is McpResult.Ok -> started.value
        }
        val url = start.authUrl
        if (url == null || start.requiresUserAction == false) {
            // Nothing to open (unrecorded): what the servers say now is the outcome.
            val outcome = when (val listed = refreshMcp(id)) {
                is McpResult.Ok -> signInOutcome(listed.value, server) ?: McpAuthOutcome.NoLink
                is McpResult.Failed -> McpAuthOutcome.Failed(listed.error.endedIfGone())
            }
            launchSignIn(handle, McpAuthWait(server, clock(), outcome)) { outcome }
            return started
        }
        try {
            open(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("session $id: the browser did not open for MCP server $server (${e.javaClass.simpleName})")
            val failed = McpResult.Failed(McpError.Rejected(e.message ?: e.javaClass.simpleName, linkFailed = true))
            signInFailed(handle, server, failed.error)
            return failed
        }
        launchSignIn(handle, McpAuthWait(server, clock())) { pollSignIn(id, server) }
        return started
    }

    /**
     * The sign-in failed to start with [error]: the chat says so, unless the session ended, and the panel offers the terminal's
     * sign-in when `mcp_authenticate` itself failed or timed out (spec 4.2), not when only its link could not be used.
     */
    private fun signInFailed(handle: SessionHandle, server: String, error: McpError) {
        val text = error.text() ?: return
        if ((error as? McpError.Rejected)?.linkFailed != true) handle.hintMcpLogin(server)
        handle.notice(NoticeLevel.ERROR, "Sign-in to $server failed: $text")
        LOG.info("session ${handle.id}: mcp_authenticate for MCP server $server failed (${error.javaClass.simpleName})")
    }

    /**
     * Publishes [wait] as [handle]'s wait for its server, in place of an earlier one, and runs [outcome] for it in the session's scope,
     * then [settleSignIn].
     */
    private fun launchSignIn(handle: SessionHandle, wait: McpAuthWait, outcome: suspend () -> McpAuthOutcome) {
        synchronized(lock) {
            val jobs = mcpAuthJobs.getOrPut(handle, ::HashMap)
            jobs.remove(wait.server)?.cancel()
            handle.putMcpAuth(wait)
            val job = handle.scope.launch(CoroutineName("SessionManager.mcpSignInWait")) { settleSignIn(handle, wait, outcome()) }
            jobs[wait.server] = job
            job.invokeOnCompletion { synchronized(lock) { if (jobs[wait.server] === job) jobs.remove(wait.server) } }
        }
    }

    /** Waits for [server] to connect: its status every [mcpAuthEvery], for up to [mcpAuthTimeout]. */
    private suspend fun pollSignIn(id: SessionId, server: String): McpAuthOutcome {
        var lastError: McpError? = null
        val settled = withTimeoutOrNull(mcpAuthTimeout) { pollUntilSettled(id, server) { lastError = it } }
        return settled ?: lastError?.let { McpAuthOutcome.Failed(it) } ?: McpAuthOutcome.StillNeedsAuth
    }

    /** Asks for the servers every [mcpAuthEvery] until [server] leaves `needs-auth`; [failed] gets each check's error, null after an answer. */
    private suspend fun pollUntilSettled(id: SessionId, server: String, failed: (McpError?) -> Unit): McpAuthOutcome {
        while (true) {
            delay(mcpAuthEvery)
            when (val listed = refreshMcp(id)) {
                is McpResult.Ok -> {
                    failed(null)
                    signInOutcome(listed.value, server)?.let { return it }
                }
                is McpResult.Failed -> when (val error = listed.error) {
                    is McpError.Rejected, is McpError.TimedOut -> failed(error) // no change: the next check tries again
                    McpError.NotRunning, McpError.Ended -> return McpAuthOutcome.Failed(McpError.Ended)
                }
            }
        }
    }

    /** Shows [outcome] on [wait] and in the chat, unless the wait was cancelled or replaced meanwhile, and drops it 5 s later. */
    private suspend fun settleSignIn(handle: SessionHandle, wait: McpAuthWait, outcome: McpAuthOutcome) {
        val settled = if (wait.outcome == outcome) wait else wait.copy(outcome = outcome)
        if (!handle.replaceMcpAuth(wait, settled)) return
        LOG.info("session ${handle.id}: sign-in to MCP server ${wait.server} ended (${outcome.javaClass.simpleName})")
        signInNotice(wait.server, outcome)?.let { (level, text) -> handle.notice(level, text) }
        try {
            delay(MCP_AUTH_OUTCOME_MILLIS)
        } finally {
            handle.removeMcpAuth(settled)
        }
    }

    /**
     * Takes session [id] out of [sessions] (selecting a neighbour if it was selected), stops it and runs [then], all while it is
     * marked closing, so that [open] waits for both. If it is closing already, waits for that first; if it is not open, it is
     * marked closing all the same while [then] runs. True if it was open. Goes on to the end when the caller is cancelled:
     * otherwise [open] could start a second process while the first is still stopping.
     */
    private suspend fun closeThen(id: SessionId, then: suspend () -> Unit): Boolean = withContext(NonCancellable) {
        val (handle, done) = markClosing(id)
        try {
            handle?.close()
            then()
        } finally {
            synchronized(lock) { closing.remove(id) }
            done.complete()
        }
        handle != null
    }

    /**
     * Marks session [id] closing, once a close already under way has ended: the open handle, now out of [sessions] ([removeLocked]),
     * or null if it is not open, and the job to complete when done.
     */
    private suspend fun markClosing(id: SessionId): Pair<SessionHandle?, CompletableJob> {
        while (true) {
            val pending = synchronized(lock) {
                closing[id] ?: run {
                    closings[id] = (closings[id] ?: 0) + 1
                    return removeLocked(id) ?: (null to Job().also { closing[id] = it })
                }
            }
            pending.join()
        }
    }

    /**
     * [listed], after the open sessions it does not list: the last opened first, each with the time now and no file. An open
     * session has its own [SessionHandle.title], which shows a rename before the index has read it.
     */
    private fun merge(listed: List<SessionSummary>, open: List<Pair<SessionHandle, String>>): List<SessionSummary> {
        val titles = open.associate { (handle, title) -> handle.id to title }
        val shown = listed.map { summary ->
            titles[summary.id]?.takeIf { it != summary.title }?.let { summary.copy(title = it) } ?: summary
        }
        val known = listed.mapTo(HashSet()) { it.id }
        val now = clock()
        val unlisted = open.asReversed()
            .filter { (handle, _) -> handle.id !in known }
            .map { (handle, title) -> SessionSummary(handle.id, title, now, handle.state.value.firstPrompt(), file = null) }
        return if (unlisted.isEmpty()) shown else unlisted + shown
    }

    /**
     * The open handle for [id] and false, or a new one with [state], added to [sessions], and true. [forkOf], [forkAt] and
     * [pendingTitle] are a fork's, see [SessionHandle].
     */
    private fun registerLocked(
        id: SessionId,
        state: ChatState,
        forkOf: SessionId? = null,
        forkAt: String? = null,
        pendingTitle: String? = null,
    ): Pair<SessionHandle, Boolean> {
        handle(id)?.let { return it to false }
        val handle = SessionHandle(id, scope, state, listing, clock(), forkOf, forkAt, pendingTitle)
        addLocked(handle)
        return handle to true
    }

    /** [SessionSpawner]'s `addTakenOver`: adds [handle], selected if [from] is, unless its id is open already. */
    private fun addTakenOver(from: SessionHandle, handle: SessionHandle): Boolean = synchronized(lock) {
        if (handle(handle.id) != null) return@synchronized false
        addLocked(handle)
        if (mutableSelected.value == from.id) selectLocked(handle.id)
        true
    }

    private fun addLocked(handle: SessionHandle) {
        mutableSessions.value += handle
        watch(handle)
    }

    /**
     * Takes session [id] out of [sessions], selects a neighbour if it was selected, and marks it closing until its process has
     * ended: the handle and the job to complete then. Null if [id] is not open.
     */
    private fun removeLocked(id: SessionId): Pair<SessionHandle, CompletableJob>? {
        val open = mutableSessions.value
        val position = open.indexOfFirst { it.id == id }
        if (position < 0) return null
        val rest = open.filterIndexed { i, _ -> i != position }
        mutableSessions.value = rest
        if (mutableSelected.value == id) selectLocked((rest.getOrNull(position) ?: rest.lastOrNull())?.id)
        val done = Job()
        closing[id] = done
        return open[position] to done
    }

    private fun selectLocked(id: SessionId?) {
        val handle = id?.let(::handle)
        if (id != null && handle == null) return
        mutableSelected.value = id
        handle?.clearAttention()
        handle?.lastUsed = clock()
    }

    /**
     * Follows the session's status: every change counts as use, and a session that is not selected asks for attention when it
     * starts waiting for a permission, or when a turn ends ([ChatState.turnsEnded] grows: a count, which no conflation of the status
     * hides, and which a permission answered outside a turn or a Stop leaves alone), from [SessionHandle.turnsEndedAtStart] on: a
     * turn that ended before this, as while a resumed-as-new session is taken over, still asks. The models its process lists become
     * [models], and the commands it offers [commands].
     */
    private fun watch(handle: SessionHandle) {
        // Undispatched, so that it sees the status from the start: the session's process may start right away.
        handle.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous = handle.status.value
            handle.status.collect { status ->
                if (status == previous) return@collect
                handle.lastUsed = clock()
                if (status == SessionStatus.WAITING) raiseAttentionUnlessSelected(handle)
                previous = status
            }
        }
        handle.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var ended = handle.turnsEndedAtStart
            // A turn that ends while a message waits to run, or as the next turn opens, raises nothing: the last one does (spec 12.5).
            handle.state.map { it.turnsEnded to it.nothingNext() }.distinctUntilChanged { a, b -> a.first == b.first }.collect { (count, last) ->
                if (count > ended && last) raiseAttentionUnlessSelected(handle)
                ended = count
            }
        }
        handle.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            handle.state.map { it.models }.distinctUntilChanged().collect { listed ->
                if (listed.isNotEmpty()) mutableModels.value = listed
            }
        }
        handle.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            handle.state.map { it.commands to it.terminalCommands }.distinctUntilChanged().collect { (offered, terminal) ->
                mutableCommands.update { known ->
                    known.copy(
                        commands = offered.ifEmpty { known.commands },
                        terminal = terminal.ifEmpty { known.terminal },
                    )
                }
            }
        }
    }

    private fun raiseAttentionUnlessSelected(handle: SessionHandle) {
        synchronized(lock) { if (mutableSelected.value != handle.id) handle.raiseAttention() }
    }

    /** The history in session [id]'s transcript, as [load] reads it: empty while there is none. */
    private suspend fun history(id: SessionId): ChatState {
        val file = withContext(Dispatchers.IO) { index.transcript(id) } ?: return ChatState()
        return load(id, file).first
    }

    /**
     * The history in [file], session [id]'s transcript, and its title. When it cannot be read, the history is only a warning
     * saying why, so that an empty chat is never a mystery, and there is no title. When reading it fails part way, the history is
     * what was read, if anything, and then that warning.
     */
    private suspend fun load(id: SessionId, file: Path): Pair<ChatState, String?> =
        try {
            val loaded = readTranscript(file)
            val reason = loaded.readError
            when {
                reason == null -> loaded.state
                loaded.state.entries.isEmpty() -> {
                    LOG.warn("session $id: transcript not read ($reason)")
                    reduce(loaded.state, SessionEvent.HostNotice(NoticeLevel.WARNING, "$NOT_READ_TEXT$reason"))
                }
                else -> {
                    LOG.warn("session $id: transcript read only partly ($reason)")
                    reduce(loaded.state, SessionEvent.HostNotice(NoticeLevel.WARNING, "$PARTLY_READ_TEXT$reason"))
                }
            } to loaded.meta.title
        } catch (e: IOException) {
            val reason = e.javaClass.simpleName
            LOG.warn("session $id: transcript not read ($reason)")
            reduce(ChatState(), SessionEvent.HostNotice(NoticeLevel.WARNING, "$NOT_READ_TEXT$reason")) to null
        }

    companion object {
        /** The chat's notice when no `claude` executable is found. */
        internal const val NO_CLI_TEXT: String =
            "The claude CLI was not found. Tandem works with Claude Code: install it from https://code.claude.com, sign in once " +
                "with `claude` in a terminal, then reopen this tool window."

        /** Why the MCP panel's start found no session to start: it was closed meanwhile. */
        internal const val CLOSED_TEXT: String = "The session was closed."

        /**
         * The chat's notice when a fork without a transcript of its own is to start, and the transcript of the session it forks is
         * not found: deleted, or the lookup failed ([SessionIndex.transcript] gives null for both, and logs a failure).
         */
        internal const val FORK_ORIGINAL_GONE_TEXT: String =
            "The transcript of the session this fork was made from was not found (it was deleted, or could not be looked up), " +
                "so the fork cannot start. Close it and fork again from another session."
    }
}

/**
 * A session's request run in flight and the follow-up run shared by the callers that came meanwhile; see [SessionManager.refreshMcp]
 * and [SessionManager.contextBreakdown].
 */
private class Coalesced<T> {
    var running: Job? = null
    var next: CompletableDeferred<McpResult<T>>? = null
}

/**
 * What [servers] say about a sign-in to [server]: null while it still needs auth, or is pending (it may be reconnecting once the
 * browser's callback landed: unrecorded, so the wait goes on until it settles or the deadline).
 */
internal fun signInOutcome(servers: List<McpServerInfo>, server: String): McpAuthOutcome? {
    val entry = servers.firstOrNull { it.name == server } ?: return McpAuthOutcome.Removed
    return when (entry.status) {
        McpStatus.CONNECTED -> McpAuthOutcome.Connected
        McpStatus.NEEDS_AUTH, McpStatus.PENDING -> null
        else -> McpAuthOutcome.Changed(entry.status, entry.error)
    }
}

/** A process that is gone, whether it ended or was never there, ends a sign-in as [McpError.Ended]. */
private fun McpError.endedIfGone(): McpError = if (this == McpError.NotRunning) McpError.Ended else this

/** The chat's text for an error the user should see; null when the session ended, which the chat shows already. */
private fun McpError.text(): String? = when (this) {
    is McpError.Rejected -> message
    is McpError.TimedOut -> "The CLI did not answer within $seconds s."
    McpError.NotRunning, McpError.Ended -> null
}

/**
 * The chat's notice for a sign-in that ended with [outcome]: every outcome has one but the session's end, which the chat shows
 * already (MCP spec 3.3 step 5, hard rule 5); `Cancel` posts nothing and never gets here.
 */
internal fun signInNotice(server: String, outcome: McpAuthOutcome): Pair<NoticeLevel, String>? = when (outcome) {
    McpAuthOutcome.Connected -> NoticeLevel.INFO to "Signed in to $server."
    McpAuthOutcome.StillNeedsAuth -> NoticeLevel.WARNING to "Sign-in to $server did not complete within 3 minutes."
    McpAuthOutcome.NoLink -> NoticeLevel.ERROR to "Sign-in to $server failed: the CLI sent no sign-in link"
    is McpAuthOutcome.Failed -> outcome.error.text()?.let { NoticeLevel.ERROR to "Sign-in to $server failed: $it" }
    is McpAuthOutcome.Changed -> when (val error = outcome.error) {
        null -> NoticeLevel.WARNING to "Sign-in to $server ended: the server is now ${outcome.status.wire.ifEmpty { "in an unknown state" }}."
        else -> NoticeLevel.ERROR to "Sign-in to $server failed: $error"
    }
    McpAuthOutcome.Removed -> NoticeLevel.WARNING to "Sign-in to $server ended: $server is no longer configured."
}

/** Each session with its title, again whenever a title changes. */
private fun titles(handles: List<SessionHandle>): Flow<List<Pair<SessionHandle, String>>> =
    if (handles.isEmpty()) flowOf(emptyList()) else combine(handles.map { handle -> handle.title.map { handle to it } }) { it.toList() }

/** [output], then [note] on a line of its own: Tandem's own words for a stopped or capped command, as the last line of stdout. */
private fun withNote(output: String, note: String): String =
    if (output.isEmpty() || output.endsWith("\n")) output + note else output + "\n" + note

/**
 * A stopped command's elapsed time as the chat writes a duration (`chat/Text.formatDuration`, which `session/` may not import):
 * one decimal under ten seconds (`3.0s`), then whole seconds (`12s`, `1m 12s`, `1h 1m`).
 */
private fun elapsed(ms: Long): String {
    if (ms < 10_000) return String.format(Locale.ROOT, "%.1fs", ms.coerceAtLeast(0) / 1_000.0)
    val seconds = ms / 1_000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
    }
}

/**
 * Whether nothing follows the turn that just ended: no message waits to run and no next turn has opened. Attention and the IDE
 * notification for a turn's end wait for the last queued turn (spec 12.5).
 */
internal fun ChatState.nothingNext(): Boolean = pendingSends.isEmpty() && turn == null
