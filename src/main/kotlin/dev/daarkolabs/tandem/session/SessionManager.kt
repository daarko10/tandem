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
import kotlinx.coroutines.flow.distinctUntilChanged
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

private val LOG = logger<SessionManager>()

/** How often the session list is read again where the transcript folders cannot be watched. */
private const val INDEX_POLL_MILLIS: Long = 30_000

/** The chat's warning, before the reason, when a session's transcript cannot be read, so that its history is empty. */
private const val NOT_READ_TEXT: String = "The session's history could not be read: "

/** The chat's warning, before the reason, when reading a session's transcript failed part way, after the history it shows. */
private const val PARTLY_READ_TEXT: String = "The session's history was read only partly: "

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

    /**
     * How often each session was marked closing ([markClosing]), so that an [open] whose history read overlapped a whole close or
     * delete, one that came and went meanwhile, reads it again. Guarded by [lock].
     */
    private val closings = HashMap<SessionId, Long>()

    /** Starts the sessions' processes. */
    private val spawner = SessionSpawner(
        scope, index, launcher, binary, workingDirectory, maxLive, clock, listing,
        sessions = { mutableSessions.value },
        addTakenOver = ::addTakenOver,
    )

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
     * Forks session [id]: a new session, with a new id, that goes on from [id]'s history while [id] stays as it is. The fork is
     * added to [sessions], selected and returned at once, and its process starts in the background, as for [create]. Null if [id]
     * has no transcript yet, since there is nothing to fork then.
     *
     * The fork starts from [id]'s settled history: the open session's chat, else its transcript's. Its title is
     * "<title of [id]> (fork)" at once, and the CLI is told so (`rename_session`) as soon as the fork's process reports its first
     * `system/init`, which comes with its first prompt: the CLI does not title a fork itself. [SessionSpawner.configFor] says how a fork starts.
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
        spawner.startInBackground(fork, forPrompts = false)
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
        if (handle.sendOrQueue(text)) spawner.startInBackground(handle, forPrompts = true)
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

    /**
     * Kills every process this manager started that has not ended yet, and what it started, at once ([ClaudeSession.killNow]): those
     * of open sessions, and those still being ended, such as a closed or stopped session's. The last resort of a JVM shutdown hook,
     * which cannot wait for coroutines to end them. Never waits; safe to call from any thread, and more than once.
     */
    fun killAllNow() {
        spawner.killAllNow()
    }

    private fun handle(id: SessionId): SessionHandle? = mutableSessions.value.firstOrNull { it.id == id }

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
     * hides, and which a permission answered outside a turn or a Stop leaves alone).
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
            var ended = handle.state.value.turnsEnded
            handle.state.map { it.turnsEnded }.distinctUntilChanged().collect { count ->
                if (count > ended) raiseAttentionUnlessSelected(handle)
                ended = count
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

        /**
         * The chat's notice when a fork without a transcript of its own is to start, and the transcript of the session it forks is
         * not found: deleted, or the lookup failed ([SessionIndex.transcript] gives null for both, and logs a failure).
         */
        internal const val FORK_ORIGINAL_GONE_TEXT: String =
            "The transcript of the session this fork was made from was not found (it was deleted, or could not be looked up), " +
                "so the fork cannot start. Close it and fork again from another session."
    }
}

/** Each session with its title, again whenever a title changes. */
private fun titles(handles: List<SessionHandle>): Flow<List<Pair<SessionHandle, String>>> =
    if (handles.isEmpty()) flowOf(emptyList()) else combine(handles.map { handle -> handle.title.map { handle to it } }) { it.toList() }
