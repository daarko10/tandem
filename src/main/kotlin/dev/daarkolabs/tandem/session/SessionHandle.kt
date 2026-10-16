package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.session.transcript.SessionSummary
import dev.daarkolabs.tandem.session.transcript.promptTitle
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A session's id: the CLI's session UUID, which also names its transcript, `<id>.jsonl`. */
typealias SessionId = String

/** What a session is doing, as the session list shows it. */
enum class SessionStatus {
    /** A turn is in progress. */
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
 */
class SessionHandle internal constructor(
    val id: SessionId,
    parentScope: CoroutineScope,
    initialState: ChatState,
    /** The index's last listing, for the [title]. */
    listing: StateFlow<List<SessionSummary>>,
    createdAt: Long,
) {
    /** This session's coroutines and the parent of its processes: a child of the manager's scope, cancelled by [close]. */
    internal val scope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job) + CoroutineName("SessionHandle"))

    /** Held while a process is started or stopped, so that a session never has two. */
    internal val spawnLock: Mutex = Mutex()

    /**
     * Guards [session], [mirror], [queued] and [closed], and makes each change of the chat ([publishLocked]) one step with them: a
     * pick or a prompt is either in the state a process starts from, or goes to that process. Only held for steps that never wait.
     */
    private val lock = Any()

    /** The process's session, from [attach] until it is let go ([releaseLocked]). */
    private var session: ClaudeSession? = null

    /** Copies [session]'s state into the chat. */
    private var mirror: Job? = null

    /** Prompts sent while no process runs, in order: the next process gets them. */
    private val queued = ArrayList<String>()
    private var closed = false

    /** A chat and whether a process is attached to it ([session] is set), which together make the [SessionStatus]. */
    private data class Snapshot(val chat: ChatState, val attached: Boolean) {
        val status: SessionStatus
            get() = when {
                !attached || chat.ended -> SessionStatus.STOPPED
                chat.pendingPermissions.isNotEmpty() -> SessionStatus.WAITING
                chat.busy -> SessionStatus.RUNNING
                else -> SessionStatus.IDLE
            }
    }

    /**
     * The chat and the process as one value, written once per change, so that [status] never sees one changed without the
     * other: a stopped session's settled chat with its process still attached would read as IDLE between RUNNING and STOPPED.
     */
    private val snapshot = MutableStateFlow(Snapshot(initialState, attached = false))

    /** [Snapshot.chat], written in the same step as [snapshot], so that [state] follows it without a coroutine in between. */
    private val mutableState = MutableStateFlow(initialState)
    private val mutableAttention = MutableStateFlow(false)

    /** The chat: the running process's state, else what the last process left (settled once it was stopped), else the loaded history. */
    val state: StateFlow<ChatState> = mutableState.asStateFlow()

    /** STOPPED without a process or once it ended; else WAITING with a permission pending, RUNNING during a turn, IDLE otherwise. */
    val status: StateFlow<SessionStatus> =
        snapshot.map { it.status }.stateIn(scope, SharingStarted.Eagerly, snapshot.value.status)

    /** The index's title for this session, else its first prompt (the first line, cut to 80 characters), else the [id]. */
    val title: StateFlow<String> =
        combine(
            listing.map { it.titleOf(id) },
            // The first prompt rarely changes, so its title is worked out only when it does, not on every streamed word.
            mutableState.map { it.firstUser() }.distinctUntilChanged().map { it?.asTitle() },
        ) { indexed, prompt -> indexed ?: prompt ?: id }
            .stateIn(scope, SharingStarted.Eagerly, listing.value.titleOf(id) ?: initialState.firstPrompt() ?: id)

    /** Whether the user should look at this session: it asked for permission or finished a turn while another one was selected. */
    val attention: StateFlow<Boolean> = mutableAttention.asStateFlow()

    /** When the session was last selected, sent a prompt or changed its [status], by the manager's clock. */
    @Volatile
    var lastUsed: Long = createdAt
        internal set

    /** Whether [close] was called: no process starts any more. */
    internal val isClosed: Boolean
        get() = synchronized(lock) { closed }

    /** The process's session while it runs or starts: attached, and not ended. */
    internal fun liveSession(): ClaudeSession? = synchronized(lock) { liveLocked() }

    /**
     * Sends [text] to the running process; without one, queues it for the next, which [attach] hands it to. True when it was
     * queued, so that a process has to be started. Either way prompts keep their order. Dropped after [close].
     */
    internal fun sendOrQueue(text: String): Boolean = synchronized(lock) {
        if (closed) return false
        val live = liveLocked()
        if (live != null && queued.isEmpty()) {
            live.sendUserMessage(text)
            false
        } else {
            queued.add(text)
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
     * A previous session that ended is let go first. Nothing happens after [close].
     */
    internal fun attach(create: (ChatState) -> ClaudeSession) {
        synchronized(lock) {
            if (closed) return
            session?.let { releaseLocked(it) }
            val created = create(mutableState.value)
            session = created
            publishLocked(created.state.value, attached = true)
            mirror = scope.launch {
                created.state.collect { chat -> synchronized(lock) { if (session === created) publishLocked(chat, attached = true) } }
            }
            val running = created.start()
            flushLocked(created)
            // A process that ends by itself (it exits, crashes or cannot start) leaves its last state, error notice included.
            scope.launch {
                running.join()
                release(created)
                created.close()
            }
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
                session?.let { releaseLocked(it) } // one that ended: its state must not overwrite the pick
                publishLocked(reduce(mutableState.value, stopped), attached = false)
            }
        }
    }

    /**
     * Shows in the chat why no process could start ([SessionEvent.Ended] with [detail]) and drops the queued prompts, which
     * nothing can take now. Returns how many there were.
     */
    internal fun failToStart(detail: String): Int = synchronized(lock) {
        session?.let { releaseLocked(it) }
        val dropped = queued.size
        queued.clear()
        publishLocked(reduce(mutableState.value, SessionEvent.Ended(detail, isError = true)), attached = false)
        dropped
    }

    /** Ends the process, if one runs, and returns once it is gone. The chat stays, settled: no turn in progress, nothing pending. */
    internal suspend fun stop() {
        spawnLock.withLock {
            val current = synchronized(lock) { session } ?: return
            release(current, settle = true)
            current.close()
        }
    }

    /** Stops for good: queued prompts are dropped, no process starts again, and this session's coroutines end. */
    internal suspend fun close() {
        synchronized(lock) {
            closed = true
            queued.clear()
        }
        stop()
        scope.coroutineContext.job.cancelAndJoin()
    }

    internal fun raiseAttention() {
        mutableAttention.value = true
    }

    internal fun clearAttention() {
        mutableAttention.value = false
    }

    private fun liveLocked(): ClaudeSession? = session?.takeIf { !it.state.value.ended }

    private fun flushLocked(live: ClaudeSession) {
        queued.forEach(live::sendUserMessage)
        queued.clear()
    }

    private fun release(ended: ClaudeSession, settle: Boolean = false) {
        synchronized(lock) { releaseLocked(ended, settle) }
    }

    /** Lets [ended] go if it is still the attached session: its last state becomes the chat's, [settle]d if asked. */
    private fun releaseLocked(ended: ClaudeSession, settle: Boolean = false) {
        if (session !== ended) return
        session = null
        mirror?.cancel()
        mirror = null
        val last = ended.state.value
        publishLocked(if (settle) last.settled() else last, attached = false)
    }

    /** The chat, and whether a process is [attached], as one step: see [snapshot]. */
    private fun publishLocked(chat: ChatState, attached: Boolean) {
        snapshot.value = Snapshot(chat, attached)
        mutableState.value = chat
    }
}

/** The first prompt of this chat as a title: its first line, trimmed and cut to 80 characters; null without one. */
internal fun ChatState.firstPrompt(): String? = firstUser()?.asTitle()

private fun ChatState.firstUser(): ChatEntry.User? = entries.firstNotNullOfOrNull { it as? ChatEntry.User }

private fun ChatEntry.User.asTitle(): String? = promptTitle(text).ifEmpty { null }

private fun List<SessionSummary>.titleOf(id: SessionId): String? = firstOrNull { it.id == id }?.title
