package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.ImageBlock
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.transcript.SessionSummary
import dev.daarkolabs.tandem.session.transcript.promptTitle
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A session's id: the CLI's session UUID, which also names its transcript, `<id>.jsonl`. */
typealias SessionId = String

/** A folder added to a session (spec 3.4): its real [path], passed as `--add-dir` on each spawn, and what the CLI made of it. */
data class AddedFolder(val path: String, val state: FolderState)

/** Where an [AddedFolder] stands. */
enum class FolderState {
    /** Not yet passed to a process that reported its `system/init`: it applies with the next prompt. */
    PENDING,

    /** The init of the process spawned with it lists it. */
    ACTIVE,

    /** The process was spawned with it, but its init does not list it. */
    MISSING,
}

/** What a session is doing, as the session list shows it. */
enum class SessionStatus {
    /** A turn is in progress, or a message waits to run. */
    RUNNING,

    /** A permission request waits for the user. */
    WAITING,

    /** A process runs, or is starting, and nothing is in progress. */
    IDLE,

    /** No process: not started in this IDE run, stopped, or ended (the chat then ends with a notice that says why). */
    STOPPED,
}

/**
 * A session the [SessionManager] has open: its chat, which outlives each process started for it, and that process while one runs.
 *
 * [state] is the running process's own [ClaudeSession.state] while there is one; after it, what that process left; before the
 * first, the history loaded from the transcript. [status] and [title] derive from it in the manager's scope. Every property is
 * safe to read from any thread; only the manager changes anything.
 *
 * A process can move to another session ([handOver], [adopt]) when the CLI reports another session id for it, so a process is a
 * child of the manager's scope, not of this session's: [stop] and [close] end this session's own.
 */
class SessionHandle internal constructor(
    val id: SessionId,
    parentScope: CoroutineScope,
    initialState: ChatState,
    /** The index's last listing, for the [title]. */
    listing: StateFlow<List<SessionSummary>>,
    createdAt: Long,
    /** The session this one is a fork of: its processes fork that one again for as long as this one has no transcript. */
    internal val forkOf: SessionId? = null,
    /**
     * With [forkOf]: the line of [forkOf]'s transcript this fork starts after (`--resume-session-at`, spec 13.1); null for a whole
     * fork. Passed, as [forkOf] is, until this session has a transcript of its own.
     */
    internal val forkAt: String? = null,
    /** A title the CLI is still to be told, such as a fork's: shown at once, and sent once a process reports its `system/init`. */
    pendingTitle: String? = null,
) {
    /**
     * How many turns had ended in the chat this handle was made with: who follows its turns counts from here, so that a turn that
     * ends before they first look, as while a resumed-as-new session is taken over, is still one.
     */
    internal val turnsEndedAtStart: Int = initialState.turnsEnded

    /** A process attached to a session: its [session], the job that runs it, and the chat it started from. */
    internal class Attachment(val session: ClaudeSession, val running: Job, val startedFrom: ChatState)

    /** This session's coroutines: a child of the manager's scope, cancelled by [close]. */
    internal val scope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job) + CoroutineName("SessionHandle"))

    /** Held while a process is started, stopped or taken over, so that a session never has two. */
    internal val spawnLock: Mutex = Mutex()

    /**
     * Guards [attachment], [mirror], [queued], [closed], [renameOnInit], [replaced], [shellJob] and what the [title] is worked out
     * from, and makes each change of the chat ([publishLocked]) one step with them: a pick or a prompt is either in the state a process
     * starts from, or goes to that process. Only held for steps that never wait.
     */
    private val lock = Any()

    /** The process, from [attach] or [adopt] until it is let go ([releaseLocked]) or handed over ([handOver]). */
    private var attachment: Attachment? = null

    /** Copies the process's state into the chat, and waits for its first `system/init`. */
    private var mirror: Job? = null

    /** Prompts and shell escapes sent while no process runs, in order: the next process gets them. */
    private val queued = ArrayList<Outbound>()
    private var closed = false

    /** The running shell escape's job ([startShell]); [close] cancels it. */
    private var runningShellJob: Job? = null

    /**
     * A title the CLI is still to be told (`rename_session`) once a process reports its first `system/init`: a fork's, or one set
     * ([rename]) while the process had not reported it yet, or without a process while a title was still to be told or there was no
     * transcript. Only the last counts. Kept apart from [titleOverride], which the index's listing clears.
     */
    private var renameOnInit: String? = pendingTitle

    /** What the last [rename] replaced, [titleOverride] and [renameOnInit], until [revertRename] puts them back. */
    private var replaced: Pair<String?, String?>? = null

    /** A chat and whether a process is attached to it ([attachment] is set), which together make the [SessionStatus]. */
    private data class Snapshot(val chat: ChatState, val attached: Boolean) {
        val status: SessionStatus
            get() = when {
                !attached || chat.ended -> SessionStatus.STOPPED
                chat.pendingPermissions.isNotEmpty() -> SessionStatus.WAITING
                // A message waiting in the CLI's queue runs soon: no eviction or folder respawn until it has (spec 12.5).
                chat.busy || chat.pendingSends.isNotEmpty() -> SessionStatus.RUNNING
                else -> SessionStatus.IDLE
            }
    }

    /**
     * The chat and the process as one value, written once per change, so that [status] never sees one changed without the
     * other: a stopped session's settled chat with its process still attached would read as IDLE between RUNNING and STOPPED.
     */
    private val snapshot = MutableStateFlow(Snapshot(initialState, attached = false))

    /** [Snapshot.chat], written in the same step as [snapshot] and just before it, so that [status] never runs ahead of [state]. */
    private val mutableState = MutableStateFlow(initialState)
    private val mutableAttention = MutableStateFlow(false)
    private val mutableLastFrameAt = MutableStateFlow(0L)

    private val mutableFolders = MutableStateFlow<List<AddedFolder>>(emptyList())

    /** Folders removed from this session ([removeFolder]) and not added again: the CLI must not still list them. Guarded by [lock]. */
    private val removedFolders = LinkedHashSet<String>()

    /** The folders the last spawn passed ([foldersForSpawn]), in order. Guarded by [lock]. */
    private var spawnFolders: List<String> = emptyList()

    /** The folders this spawn's notices were raised for already, so that each is raised once per folder per spawn. Guarded by [lock]. */
    private val warnedMissing = HashSet<String>()
    private val warnedKept = HashSet<String>()

    /** A title set in Tandem ([rename], or a fork's) that the index does not list yet: it wins until the index lists the same. */
    private var titleOverride: String? = pendingTitle

    /** The index's title for this session, from its last listing. */
    private var indexedTitle: String? = listing.value.titleOf(id)

    /**
     * The chat's first prompt (a prompt or a shell escape, as [firstPromptKey] tells it), and its title: worked out again only when
     * that prompt changes, not on every streamed word or line of output.
     */
    private var firstPromptKey: Any? = initialState.firstPromptEntry()?.let(::firstPromptKey)
    private var promptTitle: String? = initialState.firstPromptEntry()?.asTitle()

    private val mutableTitle = MutableStateFlow(titleOverride ?: indexedTitle ?: promptTitle ?: id)

    /** The chat: the running process's state, else what the last process left (settled once it was stopped), else the loaded history. */
    val state: StateFlow<ChatState> = mutableState.asStateFlow()

    /** STOPPED without a process or once it ended; else WAITING with a permission pending, RUNNING during a turn, IDLE otherwise. */
    val status: StateFlow<SessionStatus> =
        snapshot.map { it.status }.stateIn(scope, SharingStarted.Eagerly, snapshot.value.status)

    /**
     * A title set in Tandem until the index lists it ([rename]), else the index's title for this session, else its first prompt
     * (the first line, cut to 80 characters), else the [id].
     *
     * Worked out in the same step as the chat changes, ahead of [status], which follows the chat a step later: whoever sees a
     * session ask for permission after its first prompt also sees that prompt's title, never the [id] it had before.
     */
    val title: StateFlow<String> = mutableTitle.asStateFlow()

    /** Whether the user should look at this session: it asked for permission or finished a turn while another one was selected. */
    val attention: StateFlow<Boolean> = mutableAttention.asStateFlow()

    private val mutableSelfEnds = MutableStateFlow(0)

    /**
     * How many processes of this session ended by themselves (the CLI exited, crashed or was killed) while attached: counted where
     * the process's own exit lets it go, never for a stop Tandem makes, which lets the process go first (desktop-parity spec 18.1).
     */
    internal val selfEnds: StateFlow<Int> = mutableSelfEnds.asStateFlow()

    /**
     * The attached process's [ClaudeSession.lastFrameAtMillis]: when it last wrote a line, 0 until it wrote one. Keeps the last
     * value once the process is let go, until the next one is attached.
     */
    val lastFrameAtMillis: StateFlow<Long> = mutableLastFrameAt.asStateFlow()

    /**
     * The folders added to this session, in the order added (spec 3.4): each goes to the CLI as `--add-dir` on the next spawn. They
     * live as long as this handle, across stops and resumes.
     */
    val folders: StateFlow<List<AddedFolder>> = mutableFolders.asStateFlow()

    /** The session's MCP servers as the chat knows them ([ChatState.mcp]): every `system/init`'s list, then every `mcp_status` answer. */
    val mcpServers: StateFlow<McpState> =
        mutableState.map { it.mcp }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, initialState.mcp)

    private val mutableMcpAuth = MutableStateFlow<Map<String, McpAuthWait>>(emptyMap())
    private val mutableMcpLoginHinted = MutableStateFlow<Set<String>>(emptySet())

    /** The running or just-ended sign-in waits ([SessionManager.startMcpAuth]), by server name: at most one per server. */
    val mcpAuth: StateFlow<Map<String, McpAuthWait>> = mutableMcpAuth.asStateFlow()

    /**
     * The servers whose `mcp_authenticate` failed or timed out in this session, so that the panel keeps offering the terminal's
     * sign-in hint, also when it is opened again.
     */
    val mcpLoginHinted: StateFlow<Set<String>> = mutableMcpLoginHinted.asStateFlow()

    /**
     * Held for exactly one MCP control request (status, toggle, reconnect or authenticate) at a time, never across a follow-up
     * refresh: a kotlinx [Mutex] is not reentrant.
     */
    val mcpActions: Mutex = Mutex()

    init {
        // The index's listing comes from the manager's scope; its title counts from the next step on.
        scope.launch {
            listing.map { it.titleOf(id) }.distinctUntilChanged().collect { indexed ->
                synchronized(lock) {
                    indexedTitle = indexed
                    updateTitleLocked()
                }
            }
        }
    }

    /** When the session was last selected, sent a prompt or changed its [status], by the manager's clock. */
    @Volatile
    var lastUsed: Long = createdAt
        internal set

    /** Whether a title set in Tandem still wins over the index's ([title]): the index has not listed it yet. For tests. */
    internal val titleOverridden: Boolean
        get() = synchronized(lock) { titleOverride != null }

    /** Whether [close] was called: no process starts any more. */
    internal val isClosed: Boolean
        get() = synchronized(lock) { closed }

    /** The job of the shell escape that runs, or ran last ([startShell]); null before the first. */
    internal val shellJob: Job?
        get() = synchronized(lock) { runningShellJob }

    /**
     * Starts a shell escape: [launch] starts its job, in one step with the check that none runs, so that at most one command runs
     * per session. False, and nothing is launched, when one runs or this session is closed.
     */
    internal fun startShell(launch: () -> Job): Boolean = synchronized(lock) {
        if (closed || runningShellJob?.isActive == true) return false
        runningShellJob = launch()
        true
    }

    /**
     * Shows a shell escape's [event] in the chat: through the running process, whose state the chat follows, or straight in the chat
     * without one. The command runs in Tandem's own process, with or without the CLI's.
     */
    internal fun shellEvent(event: SessionEvent) {
        pick(live = { it.event(event) }, stopped = event)
    }

    /** The process's session while it runs or starts: attached, and not ended. */
    internal fun liveSession(): ClaudeSession? = synchronized(lock) { liveLocked() }

    /**
     * Sends [outbound] (a prompt or a shell escape's frames) to the running process; without one, queues it for the next, which
     * [attach] hands it to. True when it was queued, so that a process has to be started. Either way they keep their order. Dropped
     * after [close].
     */
    internal fun sendOrQueue(outbound: Outbound): Boolean = synchronized(lock) {
        if (closed) return false
        val live = liveLocked()
        if (live != null && queued.isEmpty()) {
            live.deliver(outbound)
            false
        } else {
            queued.add(outbound)
            true
        }
    }

    /** Whether prompts wait for a process. */
    internal fun hasQueued(): Boolean = synchronized(lock) { queued.isNotEmpty() }

    /** Hands the queued prompts to the running process, if one runs: then true. */
    internal fun deliverQueued(): Boolean = synchronized(lock) {
        val live = liveLocked() ?: return false
        flushLocked(live)
        true
    }

    /**
     * Starts the session [create] makes from the chat's current state, in one step with every pick and prompt: a pick made before
     * is in the state [create] gets, one made after goes to the process ([pick]), and the queued prompts go to it first, in order.
     * A previous session that ended is let go first. Nothing happens after [close]. [onFirstInit] gets the session id of the
     * process's first `system/init`, unless the process is let go before that comes.
     */
    internal fun attach(create: (ChatState) -> ClaudeSession, onFirstInit: (ClaudeSession, SessionId) -> Unit) {
        synchronized(lock) {
            if (closed) return
            attachment?.let { releaseLocked(it.session) }
            val chat = mutableState.value
            val created = create(chat)
            val running = created.start() // initialize goes to stdin first, ahead of the queued prompts
            connectLocked(Attachment(created, running, chat), onFirstInit)
            flushLocked(created)
        }
    }

    /**
     * Takes over [process], which another session handed over ([handOver]), as [attach] would have started it: its state becomes
     * the chat, prompts queued here go to it, and [onFirstInit] is called as for [attach]. False, and nothing changes, if this
     * session is closed or has a process of its own.
     */
    internal suspend fun adopt(process: Attachment, onFirstInit: (ClaudeSession, SessionId) -> Unit): Boolean =
        spawnLock.withLock {
            synchronized(lock) {
                val free = !closed && liveLocked() == null
                if (free) {
                    attachment?.let { releaseLocked(it.session) } // one that ended: its state must not overwrite the one taken over
                    connectLocked(process, onFirstInit)
                    flushLocked(process.session)
                }
                free
            }
        }

    /**
     * Lets [session] go without ending it, for another session to [adopt]: the CLI reported another session id for it. The chat
     * goes back to what it was when that process started, settled, and the session is STOPPED. Null if [session] is no longer
     * attached: it was stopped, or it ended.
     */
    internal fun handOver(session: ClaudeSession): Attachment? = synchronized(lock) {
        val process = attachment?.takeIf { it.session === session } ?: return null
        detachLocked()
        publishLocked(process.startedFrom.settled(), attached = false)
        process
    }

    /**
     * Shows [title] as this session's at once, until the index lists it. A running process that has reported its first
     * `system/init` is asked to rename the session (`rename_session`), which makes the CLI append the `custom-title` line, and true
     * is returned: no older title is to be told at the init any more. Otherwise false, and the caller writes the line if the
     * session has a transcript. [title] is then told with the next first `system/init` ([sendPendingTitle]), in place of a title
     * still to be told (a fork's, or one set before that init): when a process runs that has not reported it yet (before then the
     * CLI may keep a title without writing it, docs/protocol.md 4.4), when a title is still to be told, and when [noTranscript]:
     * nothing else would bring the title to the CLI.
     */
    internal fun rename(title: String, noTranscript: Boolean = false): Boolean = synchronized(lock) {
        replaced = titleOverride to renameOnInit
        titleOverride = title
        updateTitleLocked()
        val live = liveLocked()
        if (live != null && live.reportedSessionId.value != null) {
            renameOnInit = null
            live.rename(title)
            return true
        }
        if (live != null || renameOnInit != null || noTranscript) renameOnInit = title
        false
    }

    /**
     * Takes back [rename] to [title], whose `custom-title` line could not be written: the title shown before it, and the one the CLI
     * was still to be told, count again. Nothing if the title changed since, by another rename or the index.
     */
    internal fun revertRename(title: String) {
        synchronized(lock) {
            val (override, onInit) = replaced ?: return
            if (titleOverride != title) return
            replaced = null
            titleOverride = override
            updateTitleLocked()
            if (renameOnInit == title) renameOnInit = onInit
        }
    }

    /**
     * Tells [session] the title the CLI is still to be told ([renameOnInit]), a fork's or one set before a process reported its
     * first `system/init`: from that init on, the CLI has a transcript to write it to. Nothing if [session] is no longer attached.
     */
    internal fun sendPendingTitle(session: ClaudeSession) {
        synchronized(lock) {
            val title = renameOnInit ?: return
            if (attachment?.session !== session) return
            renameOnInit = null
            session.rename(title)
        }
    }

    /**
     * Applies a pick: to the running process with [live] (a control request, which its state shows), else to the chat with
     * [stopped], so that the next process starts with it.
     */
    internal fun pick(live: (ClaudeSession) -> Unit, stopped: SessionEvent) {
        synchronized(lock) {
            val running = liveLocked()
            if (running != null) {
                live(running)
            } else {
                attachment?.let { releaseLocked(it.session) } // one that ended: its state must not overwrite the pick
                publishLocked(reduce(mutableState.value, stopped), attached = false)
            }
        }
    }

    /**
     * Picks the mode [next] gives for the chat as it stands, and returns it. Read and pick are one step under the lock, from the live
     * process's own state, which already holds every earlier pick ([ClaudeSession.setPermissionMode] changes it at once), else from
     * the chat: quick presses step one mode each. A running process switches (`set_permission_mode`); a stopped session starts
     * with it (`--permission-mode`). Nothing here waits: the frame is queued for the process's writer.
     */
    internal fun pickMode(next: (ChatState) -> PermissionMode): PermissionMode = synchronized(lock) {
        val mode = next(liveLocked()?.state?.value ?: mutableState.value)
        pick(live = { it.setPermissionMode(mode) }, stopped = SessionEvent.PermissionModeRequested(mode))
        mode
    }

    /** Shows a notice in the chat: through the running process, whose state the chat follows, or straight in the chat without one. */
    internal fun notice(level: NoticeLevel, text: String) {
        pick(live = { it.notice(level, text) }, stopped = SessionEvent.HostNotice(level, text))
    }

    /**
     * Shows in the chat why no process could start ([SessionEvent.Ended] with [detail]) and drops the queued prompts, which
     * nothing can take now, with a warning after it for each queued shell escape whose output is not sent (hard rule 5). Returns
     * how many were dropped.
     */
    internal fun failToStart(detail: String): Int = synchronized(lock) {
        attachment?.let { releaseLocked(it.session) }
        val dropped = queued.size
        val unsent = queued.filterIsInstance<Outbound.ShellEscape>()
            .map { SessionEvent.HostNotice(NoticeLevel.WARNING, unsentShellText(it.command)) }
        queued.clear()
        val ended = reduce(mutableState.value, SessionEvent.Ended(detail, isError = true))
        publishLocked(unsent.fold(ended) { acc, notice -> reduce(acc, notice) }, attached = false)
        dropped
    }

    /** Ends the process, if one runs, and returns once it is gone. The chat stays, settled: no turn in progress, nothing pending. */
    internal suspend fun stop() {
        spawnLock.withLock {
            // Let go in the same step as it is taken, so that a process handed over meanwhile is never ended here.
            val current = synchronized(lock) { attachment?.session?.also { releaseLocked(it, settle = true) } } ?: return
            current.close()
        }
    }

    /**
     * [stop], but only while a process runs and is idle ([isIdle]), checked again in the same step as the process is let go, so
     * that a prompt sent meanwhile is never cut off. Gives up at once, with false, while a start or a stop of this session holds
     * its spawn lock: waiting for it could deadlock with a start of this session that waits for room under the cap.
     */
    internal suspend fun stopIfIdle(): Boolean {
        if (!spawnLock.tryLock()) return false
        try {
            val idle = synchronized(lock) { attachment?.session?.takeIf(::idleLocked)?.also { releaseLocked(it, settle = true) } }
                ?: return false
            idle.close()
            return true
        } finally {
            spawnLock.unlock()
        }
    }

    /**
     * Whether a process runs and nothing is in progress: no turn, no pending permission, no prompt waiting to be sent, no message
     * waiting in the CLI's queue.
     */
    internal fun isIdle(): Boolean = synchronized(lock) { attachment?.session?.let(::idleLocked) ?: false }

    /**
     * Stops for good: queued prompts are dropped, a running shell escape is stopped (its entry ends Stopped, and nothing is sent), no
     * process starts again, and this session's coroutines end.
     */
    internal suspend fun close() {
        val shell = synchronized(lock) {
            closed = true
            queued.clear()
            runningShellJob
        }
        shell?.cancelAndJoin() // ahead of the process: its end reaches the chat the process leaves
        stop()
        scope.coroutineContext.job.cancelAndJoin()
    }

    /**
     * Adds [realPath] (checked by the caller on IO) as [FolderState.PENDING]; false when it is listed already (the same string). It
     * is no longer a removed folder then.
     */
    internal fun addFolder(realPath: String): Boolean = synchronized(lock) {
        if (mutableFolders.value.any { it.path == realPath }) return false
        removedFolders.remove(realPath)
        mutableFolders.value += AddedFolder(realPath, FolderState.PENDING)
        true
    }

    /** Removes [realPath] and remembers it as removed, so that a CLI that still lists it is caught ([confirmFolders]); false if not listed. */
    internal fun removeFolder(realPath: String): Boolean = synchronized(lock) {
        val listed = mutableFolders.value
        if (listed.none { it.path == realPath }) return false
        mutableFolders.value = listed.filter { it.path != realPath }
        removedFolders.add(realPath)
        true
    }

    /** Takes [from]'s folders, all [FolderState.PENDING]: a fork's, before its first spawn. */
    internal fun copyFolders(from: SessionHandle) {
        val copied = from.folders.value.map { AddedFolder(it.path, FolderState.PENDING) }
        synchronized(lock) { mutableFolders.value = copied }
    }

    /**
     * Takes [from]'s folders, all [FolderState.PENDING], with what its last spawn passed and what was removed: before [adopt] of the
     * process [from] spawned and handed over, so that this row lists the folders that process runs with and checks its inits.
     */
    internal fun inheritFolders(from: SessionHandle) {
        val (spawned, removed) = synchronized(from.lock) { from.spawnFolders to from.removedFolders.toList() }
        val copied = from.folders.value.map { AddedFolder(it.path, FolderState.PENDING) }
        synchronized(lock) {
            mutableFolders.value = copied
            spawnFolders = spawned
            removedFolders.clear()
            removedFolders.addAll(removed)
        }
    }

    /**
     * The folders the next spawn passes, in the order added, remembered as that spawn's. Checked on [Dispatchers.IO]: one that is no
     * longer a directory leaves the list, with a warning in the chat.
     */
    internal suspend fun foldersForSpawn(): List<String> {
        val paths = folders.value.map { it.path }
        val vanished = withContext(Dispatchers.IO) { paths.filterNot(::isDirectory).toSet() }
        val passed = synchronized(lock) {
            if (vanished.isNotEmpty()) mutableFolders.value = mutableFolders.value.filter { it.path !in vanished }
            val current = mutableFolders.value.mapTo(HashSet()) { it.path }
            spawnFolders = paths.filter { it !in vanished && it in current }
            warnedMissing.clear()
            warnedKept.clear()
            spawnFolders
        }
        vanished.forEach { notice(NoticeLevel.WARNING, "The folder $it no longer exists, so it was not added.") }
        return passed
    }

    /** Whether a live process runs whose spawn passed other folders than [folders] lists now: it has to be stopped and spawned again. */
    internal fun spawnDiffers(): Boolean = synchronized(lock) {
        liveLocked() != null && spawnFolders != mutableFolders.value.map { it.path }
    }

    /** Whether the inits of the attached process are checked against the folders: it was spawned with some, or one was removed. */
    private fun foldersToConfirm(): Boolean = synchronized(lock) { spawnFolders.isNotEmpty() || removedFolders.isNotEmpty() }

    /**
     * Compares a `system/init`'s [listed] directories with the spawn's folders, on [Dispatchers.IO] (string equality, then
     * [Files.isSameFile]): each found turns [FolderState.ACTIVE], each not found [FolderState.MISSING], with a warning once per folder
     * per spawn (hard rule 5). A removed folder the spawn did not pass that [listed] still names gets its own warning, as often.
     */
    internal suspend fun confirmFolders(listed: List<String>) {
        val (passed, removed) = synchronized(lock) { spawnFolders to removedFolders.filter { it !in spawnFolders } }
        val (found, kept) = withContext(Dispatchers.IO) {
            passed.filterTo(HashSet()) { listed.containsFolder(it) } to removed.filter { listed.containsFolder(it) }
        }
        val notices = ArrayList<String>()
        synchronized(lock) {
            mutableFolders.value = mutableFolders.value.map { folder ->
                when (folder.path) {
                    in found -> folder.copy(state = FolderState.ACTIVE)
                    in passed -> folder.copy(state = FolderState.MISSING)
                    else -> folder
                }
            }
            passed.filter { it !in found && warnedMissing.add(it) }.forEach { notices += "The CLI did not add the folder $it." }
            kept.filter { it in removedFolders && warnedKept.add(it) }.forEach { notices += "The CLI still lists the removed folder $it." }
        }
        notices.forEach { notice(NoticeLevel.WARNING, it) }
    }

    /** Publishes [wait] as its server's sign-in wait, in place of any earlier one. */
    internal fun putMcpAuth(wait: McpAuthWait) {
        mutableMcpAuth.update { it + (wait.server to wait) }
    }

    /** Replaces [old] with [new] if [old] is still its server's wait; false when it was cancelled or replaced meanwhile. */
    internal fun replaceMcpAuth(old: McpAuthWait, new: McpAuthWait): Boolean {
        var replaced = false
        mutableMcpAuth.update { waits ->
            replaced = waits[old.server] === old
            if (replaced) waits + (old.server to new) else waits
        }
        return replaced
    }

    /** Drops [server]'s sign-in wait, whichever it is. */
    internal fun removeMcpAuth(server: String) {
        mutableMcpAuth.update { it - server }
    }

    /** Drops [wait] if it is still its server's sign-in wait. */
    internal fun removeMcpAuth(wait: McpAuthWait) {
        mutableMcpAuth.update { waits -> if (waits[wait.server] === wait) waits - wait.server else waits }
    }

    /** Remembers that [server]'s `mcp_authenticate` failed or timed out ([mcpLoginHinted]). */
    internal fun hintMcpLogin(server: String) {
        mutableMcpLoginHinted.update { it + server }
    }

    internal fun raiseAttention() {
        mutableAttention.value = true
    }

    internal fun clearAttention() {
        mutableAttention.value = false
    }

    private fun liveLocked(): ClaudeSession? = attachment?.session?.takeIf { !it.state.value.ended }

    /**
     * [isIdle] for [session], from its own state, which a prompt changes at once ([status] follows a step later). Not while a shell
     * escape runs, nor while a quiet send waits for its result: the cap must not stop the CLI between a frame and its answer.
     */
    private fun idleLocked(session: ClaudeSession): Boolean {
        val chat = session.state.value
        return !chat.ended && !chat.busy && chat.pendingPermissions.isEmpty() && queued.isEmpty() &&
            runningShellJob?.isActive != true && chat.quietSends.isEmpty() && chat.pendingSends.isEmpty()
    }

    private fun flushLocked(live: ClaudeSession) {
        queued.forEach { live.deliver(it) }
        queued.clear()
    }

    /** Makes [process] this session's: its state becomes the chat, its first `system/init` goes to [onFirstInit], its end is followed. */
    private fun connectLocked(process: Attachment, onFirstInit: (ClaudeSession, SessionId) -> Unit) {
        val session = process.session
        attachment = process
        publishLocked(session.state.value, attached = true)
        mutableLastFrameAt.value = session.lastFrameAtMillis.value // in the same step, so state and frame time are one process's
        mirror = scope.launch {
            // A child of the mirror, so that it ends when the process is let go or handed over before its first system/init.
            launch { onFirstInit(session, session.reportedSessionId.filterNotNull().first()) }
            // After each system/init of a process spawned with folders, or once a folder was removed (spec 3.4).
            launch {
                session.state.map { it.mcp.initCount to it.additionalDirectories }
                    .distinctUntilChanged { a, b -> a.first == b.first }
                    .filter { (count, _) -> count > 0 && foldersToConfirm() }
                    .collect { (_, listed) -> confirmFolders(listed) }
            }
            launch {
                session.lastFrameAtMillis.collect { at ->
                    synchronized(lock) { if (attachment?.session === session) mutableLastFrameAt.value = at }
                }
            }
            session.state.collect { chat ->
                synchronized(lock) { if (attachment?.session === session) publishLocked(chat, attached = true) }
            }
        }
        // A process that ends by itself (it exits, crashes or cannot start) leaves its last state, error notice included.
        scope.launch {
            process.running.join()
            synchronized(lock) {
                if (releaseLocked(session) && !closed) mutableSelfEnds.value += 1
            }
            session.close()
        }
    }

    /**
     * Lets [ended] go if it is still the attached process: its last state becomes the chat's, [settle]d if asked. A shell escape it
     * still held is dropped first, with a notice that its output was not sent (a [stop] during the turn it waited for). Returns
     * whether it let [ended] go.
     */
    private fun releaseLocked(ended: ClaudeSession, settle: Boolean = false): Boolean {
        if (attachment?.session !== ended) return false
        detachLocked()
        ended.letGoShellFrames()
        val last = ended.state.value
        publishLocked(if (settle) last.settled() else last, attached = false)
        return true
    }

    /**
     * Works the [title] out again. The index lists the title set in Tandem: its title counts again, so that a later rename elsewhere
     * shows. Cleared here, with the listing this title is worked out from, so that the title never goes back to the index's older
     * one meanwhile.
     */
    private fun updateTitleLocked() {
        if (titleOverride != null && titleOverride == indexedTitle) titleOverride = null
        mutableTitle.value = titleOverride ?: indexedTitle ?: promptTitle ?: id
    }

    /** Stops following the attached process, which runs on until someone ends it. */
    private fun detachLocked() {
        attachment = null
        mirror?.cancel()
        mirror = null
    }

    /**
     * The chat, and whether a process is [attached], as one step: see [snapshot]. The [title] follows a new first prompt between
     * the two: after the chat, so that whoever sees the title sees its chat, and ahead of the [status] that comes from it.
     */
    private fun publishLocked(chat: ChatState, attached: Boolean) {
        mutableState.value = chat
        val first = chat.firstPromptEntry()
        val key = first?.let(::firstPromptKey)
        if (key != firstPromptKey) {
            firstPromptKey = key
            promptTitle = first?.asTitle()
            updateTitleLocked()
        }
        snapshot.value = Snapshot(chat, attached)
    }
}

/**
 * The first prompt of this chat as a title: its first line, trimmed and cut to 80 characters, `[Image #1]` for one of images only,
 * or `! <command>` for a shell escape; null without one.
 */
internal fun ChatState.firstPrompt(): String? = firstPromptEntry()?.asTitle()

/** The first prompt or shell escape of this chat, whichever comes first. */
private fun ChatState.firstPromptEntry(): ChatEntry? = entries.firstOrNull { it is ChatEntry.User || it is ChatEntry.Shell }

/** What a first prompt's title depends on: a prompt as it is, a shell escape by its id and command, not its output. */
private fun firstPromptKey(entry: ChatEntry): Any = if (entry is ChatEntry.Shell) entry.id to entry.command else entry

/** An images-only prompt is titled by its first image; a shell escape as the terminal's own last prompt, `! <command>`. */
private fun ChatEntry.asTitle(): String? = when (this) {
    is ChatEntry.User -> promptTitle(text).ifEmpty { if (images.isNotEmpty()) "[Image #1]" else null }
    is ChatEntry.Shell -> promptTitle("! $command")
    else -> null
}

/** What waits for a process, in order: a prompt, or a shell escape's frames. */
internal sealed interface Outbound {
    /** A prompt: [text] and the [images] attached to it; [later]: sent with `"priority":"later"` (spec 12.1). */
    data class Prompt(val text: String, val images: List<ImageBlock> = emptyList(), val later: Boolean = false) : Outbound

    /**
     * Shell escape [id]'s frames: its [command] and [output] (with Tandem's note for a stopped or capped one), and whether Claude
     * replies.
     */
    data class ShellEscape(val id: String, val command: String, val output: String, val respond: Boolean) : Outbound
}

/** Hands [outbound] to this process: a prompt as a user message, a shell escape as its frames. */
private fun ClaudeSession.deliver(outbound: Outbound) {
    when (outbound) {
        is Outbound.Prompt -> sendUserMessage(outbound.text, outbound.images, outbound.later)
        is Outbound.ShellEscape -> sendShellEscape(outbound.id, outbound.command, outbound.output, outbound.respond)
    }
}

private fun List<SessionSummary>.titleOf(id: SessionId): String? = firstOrNull { it.id == id }?.title

private fun isDirectory(path: String): Boolean = try {
    Files.isDirectory(Path.of(path))
} catch (_: InvalidPathException) {
    false
}

/** Whether these listed directories name [folder]: the same string, or the same file ([Files.isSameFile]). */
private fun List<String>.containsFolder(folder: String): Boolean = any { listed ->
    listed == folder || try {
        Files.isSameFile(Path.of(listed), Path.of(folder))
    } catch (_: IOException) {
        false
    } catch (_: InvalidPathException) {
        false
    }
}
