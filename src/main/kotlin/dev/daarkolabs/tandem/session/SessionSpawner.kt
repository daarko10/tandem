package dev.daarkolabs.tandem.session

import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.transcript.SessionIndex
import dev.daarkolabs.tandem.session.transcript.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

// The manager's log category: the spawn pipeline was part of it, and debug settings name it.
private val LOG = logger<SessionManager>()

/**
 * The permission modes Tandem offers, in the order its picker lists them. A process never starts in another one (see
 * [SessionSpawner.configFor]).
 */
internal val OFFERED_MODES: Set<PermissionMode> = setOf(PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS, PermissionMode.PLAN)

/** The chat's notice when a process starts although [SessionManager]'s cap is reached and no session is idle. */
private const val CAP_EXCEEDED_TEXT: String = "Max live sessions exceeded"

/**
 * How [SessionManager] starts a session's process: under the session's spawn lock, after making room under the cap, with the
 * arguments its transcript calls for, and what happens when the process reports its first `system/init`. Every process started
 * is a child of [scope] (see [SessionManager]) and is known here until it has ended ([killAllNow]).
 */
internal class SessionSpawner(
    private val scope: CoroutineScope,
    private val index: SessionIndex,
    private val launcher: ProcessLauncher,
    private val binary: () -> String?,
    private val workingDirectory: Path,
    private val maxLive: () -> Int,
    private val clock: () -> Long,
    /** The index's last listing, for the handle of a session that takes over a process ([takeOver]). */
    private val listing: StateFlow<List<SessionSummary>>,
    /** The open sessions, for [makeRoom]. */
    private val sessions: () -> List<SessionHandle>,
    /**
     * Adds the row of a session that took over a process another one started ([takeOver]), and selects it if that one was
     * selected; false, and nothing changes, if a session with its id is open already.
     */
    private val addTakenOver: (from: SessionHandle, handle: SessionHandle) -> Boolean,
) {
    /** One start at a time makes room under the cap and attaches its process, so that two starts never count the same room. */
    private val admission = Mutex()

    /**
     * Every process started and not yet ended, whether a session runs it, is stopping it or hands it over: a session lets go of its
     * process before ending it, which takes up to a few seconds ([ClaudeSession.close]). What [killAllNow] kills.
     */
    private val processes: MutableSet<ClaudeSession> = ConcurrentHashMap.newKeySet()

    /** Kills every process started here that has not ended yet, at once: see [SessionManager.killAllNow]. */
    fun killAllNow() {
        processes.forEach { it.killNow() }
    }

    /**
     * [start] in the session's scope, undispatched: it takes its place in line for the spawn lock before this returns, so that
     * what is asked later ([SessionManager.stop], another prompt) comes after it. What the user asked for
     * ([SessionManager.create], [SessionManager.fork], a prompt) starts over the cap if it has to.
     */
    fun startInBackground(handle: SessionHandle, forPrompts: Boolean) {
        handle.scope.launch(start = CoroutineStart.UNDISPATCHED) { start(handle, forPrompts, overCapAllowed = true) }
    }

    /**
     * Starts a process for the session unless one runs, and hands it the queued prompts. A start [forPrompts] (one that
     * [SessionManager.send] asked for) that finds none left does nothing: an earlier start sent them, or dropped them for want of
     * a binary. Holds the session's spawn lock throughout, so that a session never gets two processes. Without a binary the chat
     * shows the install notice instead, and a fork that has no transcript of its own, and whose original has none any more, says
     * so in its chat ([SessionManager.FORK_ORIGINAL_GONE_TEXT]) rather than start a `--resume` the CLI would fail. Every process,
     * a respawn's and a fork's too, starts only once [makeRoom] has made room for it under the cap; if it could not, the process
     * starts over the cap when [overCapAllowed], with a notice in the chat, and else not at all.
     */
    suspend fun start(handle: SessionHandle, forPrompts: Boolean, overCapAllowed: Boolean) {
        handle.spawnLock.withLock {
            if (handle.isClosed || handle.deliverQueued()) return
            if (forPrompts && !handle.hasQueued()) return
            val (path, hasTranscript, originalGone) = withContext(Dispatchers.IO) {
                val path = binary()
                val hasTranscript = path != null && index.transcript(handle.id) != null
                // A fork without a transcript of its own starts from its original's (configFor), which must still be there.
                val originalGone = path != null && !hasTranscript && handle.forkOf?.let { index.transcript(it) == null } == true
                Triple(path, hasTranscript, originalGone)
            }
            if (path == null) {
                val dropped = handle.failToStart(SessionManager.NO_CLI_TEXT)
                LOG.info("session ${handle.id}: claude not found, $dropped prompt(s) not sent")
                return
            }
            if (originalGone) {
                val dropped = handle.failToStart(SessionManager.FORK_ORIGINAL_GONE_TEXT)
                LOG.warn("session ${handle.id}: not started, the session it forks has no transcript any more; $dropped prompt(s) not sent")
                return
            }
            admission.withLock {
                val overCap = !makeRoom(handle)
                if (overCap && !overCapAllowed) {
                    LOG.info("session ${handle.id}: not started, the cap of ${maxLive()} live session(s) is reached and none is idle")
                    return
                }
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
     * permission is never stopped. False if none was idle: the process then starts over the cap, or not at all ([start]). Called
     * with [admission] held, so that two starts never count the same room.
     */
    private suspend fun makeRoom(starting: SessionHandle): Boolean {
        while (true) {
            val live = sessions().filter { it !== starting && it.liveSession() != null }
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
        if (!handle.adopt(process) { adopted, reported -> firstInit(handle, adopted, reported) }) {
            // Cannot happen while the handle is new; should it, the process must not run on where no row shows it.
            LOG.warn("session ${from.id}: the CLI resumed it as session $id, whose row could not take the process; it is stopped")
            handle.close()
            session.close()
            from.notice(NoticeLevel.WARNING, "Resumed as session $id, which could not be shown, so that process was stopped.")
            return
        }
        if (addTakenOver(from, handle)) {
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
     * the fork its id because no `system/init` tells it before the fork's first prompt. Measured with 2.1.301, sending only
     * `initialize` (docs/protocol.md lists the conditions under Sources):
     * - `--resume <x>` and `--resume <x> --fork-session` answer `initialize` and send no `system/init` until the first prompt, and
     *   the fork has no transcript before it. A signed-in CLI names the id earlier only in lines the SDK does not document
     *   (`system/ui_invalidate`, and `system/session_title_changed` when the original has a title); Tandem does not rely on them;
     * - `--resume <x> --fork-session --session-id <z>` exits with 0. It answers `rename_session` with `system/session_title_changed`
     *   under `session_id` z, writes nothing before a prompt, and leaves `<x>.jsonl` as it was; the `result` of such a start that
     *   fails ("No conversation found") carries z too;
     * - `--resume <x> --session-id <z>` without `--fork-session` exits with code 1: "Error: --session-id can only be used with
     *   --continue or --resume if --fork-session is also specified."
     *
     * With a prompt, the fork's `system/init` then reports z (measured with 2.1.301, docs/protocol.md 8); [takeOver] handles a CLI
     * that would not.
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
}
