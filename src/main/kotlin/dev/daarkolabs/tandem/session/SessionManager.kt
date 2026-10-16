package dev.daarkolabs.tandem.session

import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.transcript.LoadedTranscript
import dev.daarkolabs.tandem.session.transcript.SessionIndex
import dev.daarkolabs.tandem.session.transcript.SessionSummary
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.UUID

private val LOG = logger<SessionManager>()

/** How often the session list is read again where the transcript folders cannot be watched. */
private const val INDEX_POLL_MILLIS: Long = 30_000

/** The permission modes Tandem offers. A process never starts in another one (see [SessionManager.configFor]). */
private val OFFERED_MODES: Set<PermissionMode> = setOf(PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS, PermissionMode.PLAN)

/** What a new session starts with; null leaves it to the CLI. */
data class NewSessionOptions(val model: String? = null, val effort: String? = null, val permissionMode: PermissionMode? = null)

/**
 * The sessions of one project. Each open session has a [SessionHandle] in [sessions], which runs its own `claude` process
 * ([ClaudeSession]) while it is live; [summaries] lists every session the CLI has for the project, from [index].
 *
 * Coroutines: all of them run in [scope], which the caller owns (the project service's). Each session gets a child scope that
 * is the parent of its processes, so cancelling [scope] ends every process. Process and file work runs on [Dispatchers.IO].
 *
 * Threads: every function may be called from any thread, the EDT included. [create], [select], [send], [interrupt], [allow],
 * [deny], [setModel] and [setPermissionMode] never wait: they change state or queue work, and return. [open], [stop], [close]
 * and [refreshIndex] suspend until they are done.
 *
 * Processes: a session starts or stops one process at a time (its spawn lock). [send] to a stopped session starts one and
 * queues the prompt until it is attached, so prompts sent in quick succession start a single process and keep their order. A
 * process that cannot start, or that ends, leaves its error notice in the chat and the session STOPPED; the next prompt tries
 * again. Without a [binary] the chat shows the install notice instead. A session never has two processes, not even one that is
 * closing and opened again: [open] waits until the old one has ended.
 *
 * The list follows [SessionIndex.changes]; where the folders cannot be watched, it is read again every 30 s. [maxLive], the
 * most sessions to keep live at once, is not enforced yet.
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
) {
    /** Reads histories with [TranscriptReader]. */
    constructor(
        scope: CoroutineScope,
        index: SessionIndex,
        launcher: ProcessLauncher,
        binary: () -> String?,
        workingDirectory: Path,
        maxLive: () -> Int,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(scope, index, launcher, binary, workingDirectory, maxLive, clock, TranscriptReader::read)

    /** Guards changes of [sessions] and [selected] together, and a session's attention against a change of the selection. */
    private val lock = Any()
    private val mutableSessions = MutableStateFlow<List<SessionHandle>>(emptyList())
    private val mutableSelected = MutableStateFlow<SessionId?>(null)

    /** The index's last listing. */
    private val listing = MutableStateFlow<List<SessionSummary>>(emptyList())

    /** One listing at a time, so that an older one never replaces a newer one. */
    private val refreshLock = Mutex()

    /** Sessions being closed, until their process has ended: [open] waits for them. Guarded by [lock]. */
    private val closing = HashMap<SessionId, CompletableJob>()

    /** The open sessions, in the order they were opened. */
    val sessions: StateFlow<List<SessionHandle>> = mutableSessions.asStateFlow()

    /** The session the user looks at, or null. Only [select] and [close] change it. */
    val selected: StateFlow<SessionId?> = mutableSelected.asStateFlow()

    /**
     * Every session of the project: the index's listing, newest first, after the open sessions it does not list yet (the CLI
     * writes a transcript with the first prompt). Those come first, the last opened first, with their [SessionHandle.title], no
     * file and the time now as [SessionSummary.lastModified].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val summaries: StateFlow<List<SessionSummary>> =
        combine(listing, mutableSessions.flatMapLatest(::titles)) { listed, open -> merge(listed, open) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

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
        startInBackground(handle, forPrompts = false)
        return handle
    }

    /**
     * Session [id]'s handle. One that is open is returned as it is: a stopped session starts again with its next prompt ([send]).
     * Otherwise a new handle gets the history from the session's transcript (none if there is no transcript yet, and a warning
     * in the chat if it cannot be read) and, with [spawn], a process. A session that is closing is opened again only once its
     * process has ended. The selection does not change.
     */
    suspend fun open(id: SessionId, spawn: Boolean = true): SessionHandle {
        while (true) {
            val pendingClose = synchronized(lock) { closing[id] }
            if (pendingClose != null) {
                pendingClose.join()
                continue
            }
            handle(id)?.let { return it }
            val history = history(id)
            // Meanwhile a close may have started, or another open may have added the session.
            val (handle, isNew) = synchronized(lock) { if (closing[id] == null) registerLocked(id, history) else null } ?: continue
            if (isNew && spawn) start(handle, forPrompts = false)
            return handle
        }
    }

    /** Selects session [id], or none; the selected session's [SessionHandle.attention] clears. An [id] that is not open is ignored. */
    fun select(id: SessionId?) {
        synchronized(lock) { selectLocked(id) }
    }

    /** Sends a prompt. A stopped session starts a process first, and gets the prompt once it is up. */
    fun send(id: SessionId, text: String) {
        val handle = handle(id) ?: return
        handle.lastUsed = clock()
        if (handle.sendOrQueue(text)) startInBackground(handle, forPrompts = true)
    }

    /** Interrupts the turn in progress, if a process runs. */
    fun interrupt(id: SessionId) {
        handle(id)?.liveSession()?.interrupt()
    }

    /** Allows the pending permission request [requestId]; [always] allows the same again for the rest of the session. */
    fun allow(id: SessionId, requestId: String, always: Boolean) {
        val session = handle(id)?.liveSession() ?: return
        session.state.value.pendingPermissions.firstOrNull { it.requestId == requestId }?.let { session.allow(it, always) }
    }

    /** Denies the pending permission request [requestId]. */
    fun deny(id: SessionId, requestId: String) {
        val session = handle(id)?.liveSession() ?: return
        session.state.value.pendingPermissions.firstOrNull { it.requestId == requestId }?.let { session.deny(it) }
    }

    /** Picks a model (null: the CLI's default). A running process switches (`set_model`); a stopped session starts with it. */
    fun setModel(id: SessionId, model: String?) {
        handle(id)?.pick(live = { it.setModel(model) }, stopped = SessionEvent.ModelRequested(model))
    }

    /** Picks a permission mode. A running process switches (`set_permission_mode`); a stopped session starts with it. */
    fun setPermissionMode(id: SessionId, mode: PermissionMode) {
        handle(id)?.pick(live = { it.setPermissionMode(mode) }, stopped = SessionEvent.PermissionModeRequested(mode))
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
        val removed = synchronized(lock) { removeLocked(id) }
        if (removed == null) {
            synchronized(lock) { closing[id] }?.join() // closing already
            return
        }
        val (handle, done) = removed
        try {
            handle.close()
        } finally {
            synchronized(lock) { closing.remove(id) }
            done.complete()
        }
        LOG.info("session $id closed")
    }

    /** Lists the transcript folders again, on [Dispatchers.IO], and updates [summaries] and the titles. */
    suspend fun refreshIndex() {
        refreshLock.withLock { listing.value = withContext(Dispatchers.IO) { index.list() } }
    }

    private fun handle(id: SessionId): SessionHandle? = mutableSessions.value.firstOrNull { it.id == id }

    /** [listed], after the open sessions it does not list: the last opened first, each with the time now and no file. */
    private fun merge(listed: List<SessionSummary>, open: List<Pair<SessionHandle, String>>): List<SessionSummary> {
        val known = listed.mapTo(HashSet()) { it.id }
        val now = clock()
        val unlisted = open.asReversed()
            .filter { (handle, _) -> handle.id !in known }
            .map { (handle, title) -> SessionSummary(handle.id, title, now, handle.state.value.firstPrompt(), file = null) }
        return if (unlisted.isEmpty()) listed else unlisted + listed
    }

    /** The open handle for [id] and false, or a new one with [state], added to [sessions], and true. */
    private fun registerLocked(id: SessionId, state: ChatState): Pair<SessionHandle, Boolean> {
        handle(id)?.let { return it to false }
        val handle = SessionHandle(id, scope, state, listing, clock())
        mutableSessions.value += handle
        watch(handle)
        return handle to true
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
     * starts waiting for a permission, or when a turn ends (running, then idle).
     */
    private fun watch(handle: SessionHandle) {
        // Undispatched, so that it sees the status from the start: the session's process may start right away.
        handle.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous = handle.status.value
            handle.status.collect { status ->
                if (status == previous) return@collect
                handle.lastUsed = clock()
                if (status == SessionStatus.WAITING || (previous == SessionStatus.RUNNING && status == SessionStatus.IDLE)) {
                    synchronized(lock) { if (mutableSelected.value != handle.id) handle.raiseAttention() }
                }
                previous = status
            }
        }
    }

    /**
     * The history in session [id]'s transcript: empty while there is none, and only a warning saying why when it cannot be
     * read, so that an empty chat is never a mystery.
     */
    private suspend fun history(id: SessionId): ChatState {
        val file = withContext(Dispatchers.IO) { index.transcript(id) } ?: return ChatState()
        return try {
            readTranscript(file).state
        } catch (e: IOException) {
            val reason = e.javaClass.simpleName
            LOG.warn("session $id: transcript not read ($reason)")
            reduce(ChatState(), SessionEvent.HostNotice(NoticeLevel.WARNING, "The session's history could not be read: $reason"))
        }
    }

    /**
     * [start] in the session's scope, undispatched: it takes its place in line for the spawn lock before this returns, so that
     * what is asked later ([stop], another prompt) comes after it.
     */
    private fun startInBackground(handle: SessionHandle, forPrompts: Boolean) {
        handle.scope.launch(start = CoroutineStart.UNDISPATCHED) { start(handle, forPrompts) }
    }

    /**
     * Starts a process for the session unless one runs, and hands it the queued prompts. A start [forPrompts] (one that [send]
     * asked for) that finds none left does nothing: an earlier start sent them, or dropped them for want of a binary. Holds the
     * session's spawn lock throughout, so that a session never gets two processes. Without a binary the chat shows the install
     * notice instead.
     */
    private suspend fun start(handle: SessionHandle, forPrompts: Boolean) {
        handle.spawnLock.withLock {
            if (handle.isClosed || handle.deliverQueued()) return
            if (forPrompts && !handle.hasQueued()) return
            val (path, hasTranscript) = withContext(Dispatchers.IO) {
                val path = binary()
                path to (path != null && index.transcript(handle.id) != null)
            }
            if (path == null) {
                val dropped = handle.failToStart(NO_CLI_TEXT)
                LOG.info("session ${handle.id}: claude not found, $dropped prompt(s) not sent")
                return
            }
            LOG.info("session ${handle.id}: starting claude (${if (hasTranscript) "resume" else "new"})")
            handle.attach { chat -> newSession(handle, configFor(handle.id, path, hasTranscript, chat), chat) }
        }
    }

    /**
     * How to start session [id]'s process, from its [chat] state.
     *
     * `--resume <id>` once the CLI has written the session's transcript ([hasTranscript]), `--session-id <id>` before that.
     * The CLI (measured with 2.1.301) writes `<id>.jsonl` with the session's first prompt, not when it starts:
     * - `--session-id <id>` with no prompt leaves no transcript;
     * - `--resume <id>` then exits with code 1, "No conversation found with session ID: <id>";
     * - `--session-id <id>` for an id whose transcript exists exits with code 1, "Error: Session ID <id> is already in use."
     *
     * Either mistake fails again on every retry, so the transcript itself decides.
     *
     * The picks come from [chat]: `--model` is [ChatState.requestedModel] and `--effort` is [ChatState.effort]. `--permission-mode`
     * is [ChatState.permissionMode] when it is a mode Tandem offers (default, acceptEdits, plan). Any other mode a transcript
     * recorded (bypassPermissions, auto, dontAsk) starts as default; [newSession] says so in the chat.
     */
    private fun configFor(id: SessionId, binary: String, hasTranscript: Boolean, chat: ChatState): SessionConfig {
        val mode = chat.permissionMode
        return SessionConfig(
            binary = binary,
            workingDirectory = workingDirectory,
            sessionId = id.takeUnless { hasTranscript },
            resumeSessionId = id.takeIf { hasTranscript },
            model = chat.requestedModel,
            effort = chat.effort,
            permissionMode = if (mode == null || mode in OFFERED_MODES) mode else PermissionMode.DEFAULT,
        )
    }

    /** The process for [handle] with [config], starting from the settled [chat], plus a notice if [config] replaced its permission mode. */
    private fun newSession(handle: SessionHandle, config: SessionConfig, chat: ChatState): ClaudeSession {
        val recorded = chat.permissionMode
        val initial = if (recorded == null || recorded == config.permissionMode) {
            chat.settled()
        } else {
            val notice = "Permission mode ${recorded.wire} is not supported yet; using default."
            reduce(chat.settled(), SessionEvent.HostNotice(NoticeLevel.INFO, notice))
        }
        return ClaudeSession(handle.scope, config, launcher, initial)
    }

    companion object {
        /** The chat's notice when no `claude` executable is found. */
        internal const val NO_CLI_TEXT: String =
            "Claude Code was not found. Install it from https://code.claude.com, sign in once with `claude` in a terminal, " +
                "then reopen this tool window."
    }
}

/** Each session with its title, again whenever a title changes. */
private fun titles(handles: List<SessionHandle>): Flow<List<Pair<SessionHandle, String>>> =
    if (handles.isEmpty()) flowOf(emptyList()) else combine(handles.map { handle -> handle.title.map { handle to it } }) { it.toList() }
