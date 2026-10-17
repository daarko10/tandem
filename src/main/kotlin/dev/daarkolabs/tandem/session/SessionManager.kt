package dev.daarkolabs.tandem.session

import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.transcript.LoadedTranscript
import dev.daarkolabs.tandem.session.transcript.SessionIndex
import dev.daarkolabs.tandem.session.transcript.SessionSummary
import dev.daarkolabs.tandem.session.transcript.TranscriptFiles
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val LOG = logger<SessionManager>()

/** How often the session list is read again where the transcript folders cannot be watched. */
private const val INDEX_POLL_MILLIS: Long = 30_000

/**
 * The permission modes Tandem offers, in the order its picker lists them. A process never starts in another one (see
 * [SessionManager.configFor]).
 */
internal val OFFERED_MODES: Set<PermissionMode> = setOf(PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS, PermissionMode.PLAN)

/** The chat's notice when a process starts although [SessionManager]'s cap is reached and no session is idle. */
private const val CAP_EXCEEDED_TEXT: String = "Max live sessions exceeded"

/** What a new session starts with; null leaves it to the CLI. */
data class NewSessionOptions(val model: String? = null, val effort: String? = null, val permissionMode: PermissionMode? = null)

/**
 * The sessions of one project. Each open session has a [SessionHandle] in [sessions], which runs its own `claude` process
 * ([ClaudeSession]) while it is live; [summaries] lists every session the CLI has for the project, from [index].
 *
 * Coroutines: all of them run in [scope], which the caller owns (the project service's). Each session gets a child scope for
 * its own coroutines. Processes are children of [scope] itself, since one can move to another session (see below); cancelling
 * [scope] ends every process. Process and file work runs on [Dispatchers.IO].
 *
 * Threads: every function may be called from any thread, the EDT included. [create], [select], [send], [interrupt], [allow],
 * [deny], [setModel] and [setPermissionMode] never wait: they change state or queue work, and return. [open], [fork], [rename],
 * [stop], [close], [delete] and [refreshIndex] suspend until they are done.
 *
 * Processes: a session starts or stops one process at a time (its spawn lock). [send] to a stopped session starts one and
 * queues the prompt until it is attached, so prompts sent in quick succession start a single process and keep their order. A
 * process that cannot start, or that ends, leaves its error notice in the chat and the session STOPPED; the next prompt tries
 * again. Without a [binary] the chat shows the install notice instead. A session never has two processes, not even one that is
 * closing and opened again: [open] waits until the old one has ended.
 *
 * At most [maxLive] sessions run a process at once: before a process starts, the least recently used idle session is stopped
 * ([makeRoom]). If none is idle, the process starts anyway and its chat says the cap was exceeded.
 *
 * A process whose first `system/init` reports another session id than the one it was started for does not run that session:
 * its row is never relabelled. The row stays, STOPPED, and a new row for the reported id takes over the process ([takeOver]).
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

    /** One start at a time makes room under the cap and attaches its process, so that two starts never count the same room. */
    private val admission = Mutex()

    /**
     * Every process started and not yet ended, whether a session runs it, is stopping it or hands it over: a session lets go of its
     * process before ending it, which takes up to a few seconds ([ClaudeSession.close]). What [killAllNow] kills.
     */
    private val processes: MutableSet<ClaudeSession> = ConcurrentHashMap.newKeySet()

    /** The open sessions, in the order they were opened. */
    val sessions: StateFlow<List<SessionHandle>> = mutableSessions.asStateFlow()

    /** The session the user looks at, or null. Only [select] and [close] change it. */
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

    /**
     * Forks session [id]: a new session, with a new id, that goes on from [id]'s history while [id] stays as it is. The fork is
     * added to [sessions], selected and returned at once, and its process starts in the background, as for [create]. Null if [id]
     * has no transcript yet, since there is nothing to fork then.
     *
     * The fork starts from [id]'s settled history: the open session's chat, else its transcript's. Its title is
     * "<title of [id]> (fork)" at once, and the CLI is told so (`rename_session`) as soon as the fork's process reports its first
     * `system/init`, which comes with its first prompt: the CLI does not title a fork itself. [configFor] says how a fork starts.
     */
    suspend fun fork(id: SessionId): SessionHandle? {
        val file = withContext(Dispatchers.IO) { index.transcript(id) } ?: return null
        val original = handle(id)
        val (history, title) = if (original != null) {
            original.state.value.settled() to original.title.value
        } else {
            val (loaded, loadedTitle) = load(id, file)
            loaded to (loadedTitle ?: id)
        }
        val forkId = UUID.randomUUID().toString()
        val fork = synchronized(lock) {
            // Its id comes with its first system/init: until then the state carries none, not the original's.
            val (fork, _) = registerLocked(forkId, history.copy(sessionId = null), forkOf = id, pendingTitle = "$title (fork)")
            selectLocked(forkId)
            fork
        }
        startInBackground(fork, forPrompts = false)
        LOG.info("session $id forked as $forkId")
        return fork
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

    /**
     * Renames session [id] to [title], trimmed; a blank title is ignored. An open session shows the new title at once
     * ([SessionHandle.title]). A running process is asked to rename the session (`rename_session`), and the CLI appends the
     * `custom-title` line to the transcript itself. Otherwise Tandem appends the line the SDK's `renameSession` appends
     * ([TranscriptFiles.appendCustomTitle]); a session without a transcript yet keeps the title in memory only. Either way the index
     * then reads the transcript again. Throws the [IOException] of a line that cannot be appended.
     */
    suspend fun rename(id: SessionId, title: String) {
        val name = title.trim()
        if (name.isEmpty()) return
        val sent = handle(id)?.rename(name) ?: false
        try {
            if (!sent) {
                withContext(Dispatchers.IO) {
                    index.transcript(id)?.let { TranscriptFiles.appendCustomTitle(it, id, name, Instant.ofEpochMilli(clock())) }
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
        try {
            closeThen(id) { withContext(Dispatchers.IO) { index.transcript(id)?.let(TranscriptFiles::delete) } }
        } finally {
            index.invalidate(id)
            refreshIndex()
        }
        LOG.info("session $id deleted")
    }

    /** Lists the transcript folders again, on [Dispatchers.IO], and updates [summaries] and the titles. */
    suspend fun refreshIndex() {
        refreshLock.withLock { listing.value = withContext(Dispatchers.IO) { index.list() } }
    }

    /**
     * Kills every process this manager started that has not ended yet, and what it started, at once ([ClaudeSession.killNow]): those
     * of open sessions, and those still being ended, such as a closed or stopped session's. The last resort of a JVM shutdown hook,
     * which cannot wait for coroutines to end them. Never waits; safe to call from any thread, and more than once.
     */
    fun killAllNow() {
        processes.forEach { it.killNow() }
    }

    private fun handle(id: SessionId): SessionHandle? = mutableSessions.value.firstOrNull { it.id == id }

    /**
     * Takes session [id] out of [sessions] (selecting a neighbour if it was selected), stops it and runs [then], all while it is
     * marked closing, so that [open] waits for both. If it is closing already, waits for that, then runs [then]; if it is not
     * open, just runs [then]. True if it was open. Goes on to the end when the caller is cancelled: otherwise [open] could start
     * a second process while the first is still stopping.
     */
    private suspend fun closeThen(id: SessionId, then: suspend () -> Unit): Boolean = withContext(NonCancellable) {
        val removed = synchronized(lock) { removeLocked(id) }
        if (removed == null) {
            synchronized(lock) { closing[id] }?.join() // closing already
            then()
            return@withContext false
        }
        val (handle, done) = removed
        try {
            handle.close()
            then()
        } finally {
            synchronized(lock) { closing.remove(id) }
            done.complete()
        }
        true
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
     * The open handle for [id] and false, or a new one with [state], added to [sessions], and true. [forkOf] and [pendingTitle] are
     * a fork's, see [SessionHandle].
     */
    private fun registerLocked(
        id: SessionId,
        state: ChatState,
        forkOf: SessionId? = null,
        pendingTitle: String? = null,
    ): Pair<SessionHandle, Boolean> {
        handle(id)?.let { return it to false }
        val handle = SessionHandle(id, scope, state, listing, clock(), forkOf, pendingTitle)
        addLocked(handle)
        return handle to true
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

    /** The history in session [id]'s transcript, as [load] reads it: empty while there is none. */
    private suspend fun history(id: SessionId): ChatState {
        val file = withContext(Dispatchers.IO) { index.transcript(id) } ?: return ChatState()
        return load(id, file).first
    }

    /**
     * The history in [file], session [id]'s transcript, and its title. When it cannot be read, the history is only a warning
     * saying why, so that an empty chat is never a mystery, and there is no title.
     */
    private suspend fun load(id: SessionId, file: Path): Pair<ChatState, String?> =
        try {
            val loaded = readTranscript(file)
            loaded.state to loaded.meta.title
        } catch (e: IOException) {
            val reason = e.javaClass.simpleName
            LOG.warn("session $id: transcript not read ($reason)")
            reduce(ChatState(), SessionEvent.HostNotice(NoticeLevel.WARNING, "The session's history could not be read: $reason")) to null
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
     * notice instead. Every process, a respawn's and a fork's too, starts only once [makeRoom] has made room for it under the cap.
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
            admission.withLock {
                val overCap = !makeRoom(handle)
                val kind = when {
                    hasTranscript -> "resume"
                    handle.forkOf != null -> "fork"
                    else -> "new"
                }
                LOG.info("session ${handle.id}: starting claude ($kind${if (overCap) ", over the cap" else ""})")
                handle.attach(
                    create = { chat -> newSession(configFor(handle, path, hasTranscript, chat), chat, overCap) },
                    onFirstInit = { session, reported -> firstInit(handle, session, reported) },
                )
            }
        }
    }

    /**
     * Makes room for [starting]'s process under the cap: while [maxLive] other sessions run a process, the least recently used one
     * that is idle is stopped ([SessionHandle.stopIfIdle]), and keeps its row and its chat. A session in a turn or waiting for a
     * permission is never stopped. False if none was idle: the process then starts over the cap. Called with [admission] held,
     * so that two starts never count the same room.
     */
    private suspend fun makeRoom(starting: SessionHandle): Boolean {
        while (true) {
            val live = mutableSessions.value.filter { it !== starting && it.liveSession() != null }
            val max = maxLive()
            if (live.size < max) return true
            // sortedBy is stable: of two sessions used at the same time, the one opened first goes first.
            val stopped = live.filter { it.isIdle() }.sortedBy { it.lastUsed }.firstOrNull { it.stopIfIdle() } ?: return false
            LOG.info("session ${stopped.id} stopped: at most $max sessions run a process at once")
        }
    }

    /**
     * [handle]'s process reported its first `system/init`, with session id [reported]. If it is [handle]'s own, the CLI is told the
     * title it is still to be told (a fork's); if not, the process does not run [handle]'s session and moves to a row of its own.
     */
    private fun firstInit(handle: SessionHandle, session: ClaudeSession, reported: SessionId) {
        if (reported == handle.id) {
            handle.sendPendingTitle(session)
        } else {
            scope.launch { takeOver(handle, session, reported) }
        }
    }

    /**
     * The CLI reports session [id] for the process [from] started, as when a resume comes back as a new session. [from] is never
     * relabelled: it goes back to its chat from before that process and is STOPPED. A new row for [id] takes over the process
     * and its chat, with a warning, and is selected if [from] was. Should [id] be open already, the process is stopped instead,
     * since a session never has two, and [from]'s chat says so.
     */
    private suspend fun takeOver(from: SessionHandle, session: ClaudeSession, id: SessionId) {
        val process = from.handOver(session) ?: return // stopped or ended meanwhile
        session.notice(NoticeLevel.WARNING, "Resumed as a new session $id")
        val handle = SessionHandle(id, scope, session.state.value, listing, clock())
        // Nobody else knows the new handle yet, so it has no process of its own and takes this one.
        handle.adopt(process) { adopted, reported -> firstInit(handle, adopted, reported) }
        val added = synchronized(lock) {
            if (handle(id) != null) return@synchronized false
            addLocked(handle)
            if (mutableSelected.value == from.id) selectLocked(id)
            true
        }
        if (added) {
            LOG.warn("session ${from.id}: the CLI resumed it as session $id, which has a row of its own now")
            return
        }
        LOG.warn("session ${from.id}: the CLI resumed it as session $id, which is open already; that process is stopped")
        handle.close()
        from.notice(NoticeLevel.WARNING, "Resumed as session $id, which is open already, so that process was stopped.")
    }

    /**
     * How to start [handle]'s process, from its [chat] state.
     *
     * `--resume <id>` once the CLI has written the session's transcript ([hasTranscript]), `--session-id <id>` before that.
     * The CLI (measured with 2.1.301) writes `<id>.jsonl` with the session's first prompt, not when it starts:
     * - `--session-id <id>` with no prompt leaves no transcript;
     * - `--resume <id>` then exits with code 1, "No conversation found with session ID: <id>";
     * - `--session-id <id>` for an id whose transcript exists exits with code 1, "Error: Session ID <id> is already in use."
     *
     * Either mistake fails again on every retry, so the transcript itself decides.
     *
     * A fork ([SessionHandle.forkOf]) without a transcript of its own starts as `--resume <original> --fork-session --session-id <id>`,
     * after a stop too, so that it never goes on as an empty session; once its transcript exists it resumes as itself. Tandem gives
     * the fork its id because nothing tells it before the fork's first prompt. Measured with 2.1.301 (only `initialize` was sent,
     * never a prompt):
     * - `--resume <x>` and `--resume <x> --fork-session` answer `initialize` and send nothing else until the first prompt: no
     *   `system/init`, no line with a session id, and no transcript for the fork;
     * - `--resume <x> --fork-session --session-id <z>` exits with 0. It answers `rename_session` with `system/session_title_changed`
     *   under `session_id` z, writes nothing before a prompt, and leaves `<x>.jsonl` as it was; the `result` of such a start that
     *   fails ("No conversation found") carries z too;
     * - `--resume <x> --session-id <z>` without `--fork-session` exits with code 1: "Error: --session-id can only be used with
     *   --continue or --resume if --fork-session is also specified."
     *
     * That the fork's `system/init` then reports z takes a prompt to see, and was not measured; [takeOver] handles one that does not.
     *
     * The picks come from [chat]: `--model` is [ChatState.requestedModel] and `--effort` is [ChatState.effort]. `--permission-mode`
     * is [ChatState.permissionMode] when it is a mode Tandem offers (default, acceptEdits, plan). Any other mode a transcript
     * recorded (bypassPermissions, auto, dontAsk) starts as default; [newSession] says so in the chat.
     */
    private fun configFor(handle: SessionHandle, binary: String, hasTranscript: Boolean, chat: ChatState): SessionConfig {
        val mode = chat.permissionMode
        val forkOf = handle.forkOf.takeUnless { hasTranscript }
        return SessionConfig(
            binary = binary,
            workingDirectory = workingDirectory,
            sessionId = handle.id.takeUnless { hasTranscript },
            resumeSessionId = if (hasTranscript) handle.id else forkOf,
            model = chat.requestedModel,
            effort = chat.effort,
            permissionMode = if (mode == null || mode in OFFERED_MODES) mode else PermissionMode.DEFAULT,
            forkSession = forkOf != null,
        )
    }

    /**
     * The process with [config], starting from the settled [chat], plus a notice if [config] replaced its permission mode and one
     * if it starts [overCap]. A child of [scope], not of a session's scope, since it can move to another session ([takeOver]).
     */
    private fun newSession(config: SessionConfig, chat: ChatState, overCap: Boolean): ClaudeSession {
        var initial = chat.settled()
        val recorded = chat.permissionMode
        if (recorded != null && recorded != config.permissionMode) {
            val notice = "Permission mode ${recorded.wire} is not supported yet; using default."
            initial = reduce(initial, SessionEvent.HostNotice(NoticeLevel.INFO, notice))
        }
        if (overCap) initial = reduce(initial, SessionEvent.HostNotice(NoticeLevel.INFO, CAP_EXCEEDED_TEXT))
        val session = ClaudeSession(scope, config, launcher, initial)
        processes.add(session)
        session.job.invokeOnCompletion { processes.remove(session) }
        return session
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
