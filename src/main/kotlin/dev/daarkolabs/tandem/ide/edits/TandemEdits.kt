package dev.daarkolabs.tandem.ide.edits

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.ide.TandemSessions
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.ToolReply
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.EditOutcome
import dev.daarkolabs.tandem.session.EditPhase
import dev.daarkolabs.tandem.session.EditReview
import dev.daarkolabs.tandem.session.HostTools
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.ToolCall
import dev.daarkolabs.tandem.session.edits.AcceptMode
import dev.daarkolabs.tandem.session.edits.EditMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.annotations.TestOnly
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val LOG = logger<TandemEdits>()

/** What shows one proposal to the user and returns their decision: the editable diff ([EditDiff]); tests answer in its place. */
interface DiffPresenter {
    /** Shows [proposal] and returns the user's decision; cancelled, it closes what it showed. Throws when it cannot show it. */
    suspend fun show(proposal: EditProposal): Decision

    /** Brings what [show] shows to the front. */
    fun focus()

    /** Closes what [show] shows, if anything. */
    fun close()
}

/** What [TandemEdits] needs of the project's sessions: read from `TandemSessions`' manager; tests give their own. */
internal interface EditSessions {
    /** Session [id]'s chat as it stands, null for an unknown session. */
    fun state(id: SessionId): ChatState?

    /** Session [id]'s added folders, the roots its edits may reach besides the project's base path. */
    fun roots(id: SessionId): List<Path>

    /** Shows [event] in session [id]'s chat ([dev.daarkolabs.tandem.session.SessionManager.editEvent]). */
    fun event(id: SessionId, event: SessionEvent.EditEvent)

    /** Shows the error notice [text] in session [id]'s chat (hard rule 5). */
    fun notice(id: SessionId, text: String)
}

/**
 * The project's host tools (spec 4.4): serves every session's `edit_file` and `write_file` calls with [IdeEditTools], shows one diff
 * at a time ([EditQueue], [EditDiff]) and reports each call's review to its session as [SessionEvent.EditPhase] events. A call
 * applies without a diff when its session's mode is `acceptEdits` or `bypassPermissions`, or when `Accept all` was pressed in that
 * session during the turn ([acceptAll], which the session's chat confirms: its turn's `result` ends it); `plan` refuses every edit.
 * A call the CLI withdraws (Stop) or whose session's process ends is decided `Stopped`, which closes its diff and sends no reply.
 */
@Service(Service.Level.PROJECT)
class TandemEdits(private val project: Project, private val scope: CoroutineScope) : HostTools, Disposable {
    /** The sessions whose `Accept all` was pressed in the current turn; an entry holds while the session's chat says so too. */
    val acceptAll: MutableMap<SessionId, Boolean> = ConcurrentHashMap()

    /** The proposals waiting for a decision, one open at a time across the project's sessions. */
    internal val queue: EditQueue = EditQueue()

    /** The project's sessions. */
    @Volatile
    @set:TestOnly
    internal var sessions: EditSessions = ManagerSessions(project)

    /** Shows the open proposal: the diff. */
    @Volatile
    @set:TestOnly
    internal var presenter: DiffPresenter = EditDiff(project)

    /** Orders the phase events of the calls with the queue's moves, so that a late `QUEUED` never follows an `OPEN` or `DONE`. */
    private val phaseLock = Any()

    init {
        scope.launch(CoroutineName("TandemEdits.diffs")) {
            queue.open.collect { proposal -> if (proposal != null) presentOrFail(proposal) }
        }
    }

    override suspend fun call(call: ToolCall): ToolReply {
        val state = sessions.state(call.session)
        val mode = state?.permissionMode
        val auto = when {
            mode == PermissionMode.ACCEPT_EDITS || mode == PermissionMode.BYPASS_PERMISSIONS -> AcceptMode.AUTO_MODE
            acceptAllOn(call.session, state) -> AcceptMode.ACCEPT_ALL
            else -> null
        }
        val inReview = AtomicBoolean(false)
        val reviewed = AtomicReference<Pair<EditProposal, Decision>?>()
        val tools = IdeEditTools(
            project,
            { sessions.roots(it) },
            { proposal ->
                inReview.set(true)
                review(proposal).also { reviewed.set(proposal to it) }
            },
            { session, text -> sessions.notice(session, text) },
        )
        val reply = try {
            tools.call(call, auto, planMode = mode == PermissionMode.PLAN)
        } catch (e: CancellationException) {
            if (inReview.get()) done(call, EditOutcome.STOPPED)
            throw e
        }
        val decided = reviewed.get()
        when {
            decided != null -> done(call, outcomeOf(decided.first, decided.second, reply))
            // The review was entered but ended without a decision (the diff could not be opened): the row leaves OPEN.
            inReview.get() -> done(call, null)
            auto != null && !reply.isError -> done(call, EditOutcome.AUTO)
        }
        return reply
    }

    override fun cancel(session: SessionId, requestId: JsonElement) {
        if (queue.cancel(session, requestId)) LOG.info("tools/call id=$requestId withdrawn: its review is stopped")
    }

    override fun cancelAll(session: SessionId) {
        acceptAll.remove(session)
        val stopped = queue.cancelAll(session)
        if (stopped > 0) LOG.info("session $session ended: $stopped reviews stopped")
    }

    /** Focuses the open diff when it is the one of tool use [toolUseId] (the row's `Show diff`). */
    fun showDiff(toolUseId: String) {
        if (queue.open.value?.call?.toolUseId == toolUseId) presenter.focus()
    }

    override fun dispose() {
        queue.cancelAll(null)
        presenter.close()
    }

    /** Waits for the user's decision on [proposal], behind any open diff; `Accept all` starts here, before the reply goes out. */
    private suspend fun review(proposal: EditProposal): Decision {
        val decision = queue.enqueue(proposal)
        publishPhase(proposal)
        val decided = try {
            decision.await()
        } catch (e: CancellationException) {
            queue.resolve(proposal, Decision.Stopped)
            throw e
        }
        if (decided is Decision.Accepted && decided.all) {
            val session = proposal.call.session
            acceptAll[session] = true
            sessions.event(session, SessionEvent.AcceptAll(true))
        }
        return decided
    }

    /**
     * [present], and when it fails for any reason, [proposal] fails with [DiffNotOpened] and the session hears it, instead of ending
     * the collector: a call that waits forever, and every later one behind it, would be a silent failure (hard rule 5).
     */
    private suspend fun presentOrFail(proposal: EditProposal) {
        try {
            present(proposal)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The class only: the message can name the file (the plan: never log a file path).
            LOG.warn("tools/call request ${proposal.call.requestId}: showing the diff failed: ${e.javaClass.name}")
            failNotOpened(proposal, reasonOf(e))
        }
    }

    /**
     * Fails [proposal] with [DiffNotOpened], the session's notice first: the refusal it explains goes out once the call fails
     * ([DiffNotOpened]: "the notice is shown already"). A notice that throws is logged by its class only and the call still fails.
     */
    private fun failNotOpened(proposal: EditProposal, reason: String) {
        try {
            sessions.notice(proposal.call.session, "The diff could not be opened: $reason")
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            LOG.warn("tools/call request ${proposal.call.requestId}: the notice failed too: ${e.javaClass.name}")
        }
        queue.fail(proposal, DiffNotOpened(reason))
    }

    /** Shows [proposal], the open one, until its decision completes: from the diff, or from a cancel, which closes the diff. */
    private suspend fun present(proposal: EditProposal) {
        val decision = queue.decisionOf(proposal) ?: return
        publishPhase(proposal)
        coroutineScope {
            val showing = launch {
                val decided = try {
                    presenter.show(proposal)
                } catch (e: Throwable) {
                    // Our own cancellation (the decision completed, or a Stop) goes on; any other throwable, a cancellation the
                    // platform raised inside the presenter included, is a diff that could not be opened: the call is refused, the
                    // session hears it, and the next proposal opens (hard rule 5).
                    if (e is CancellationException) ensureActive()
                    LOG.warn("tools/call request ${proposal.call.requestId}: the diff could not be opened: ${e.javaClass.name}")
                    failNotOpened(proposal, reasonOf(e))
                    return@launch
                }
                queue.resolve(proposal, decided)
            }
            try {
                decision.join()
            } finally {
                showing.cancel()
            }
        }
    }

    /** Whether `Accept all` holds for [session]: pressed in this turn, and its chat not past the turn's `result` since. */
    private fun acceptAllOn(session: SessionId, state: ChatState?): Boolean {
        if (acceptAll[session] != true) return false
        if (state?.acceptAll == true) return true
        acceptAll.remove(session)
        return false
    }

    /** Sends [proposal]'s phase as the queue has it now: `OPEN` at the head, `QUEUED` behind it, nothing once it left. */
    private fun publishPhase(proposal: EditProposal) {
        val toolUseId = proposal.call.toolUseId ?: return
        synchronized(phaseLock) {
            val phase = when {
                queue.open.value === proposal -> EditPhase.OPEN
                queue.queued.value.any { it === proposal } -> EditPhase.QUEUED
                else -> return
            }
            sessions.event(proposal.call.session, SessionEvent.EditPhase(toolUseId, EditReview(fileNameOf(proposal.call), phase)))
        }
    }

    private fun done(call: ToolCall, outcome: EditOutcome?) {
        val toolUseId = call.toolUseId ?: return
        synchronized(phaseLock) {
            sessions.event(call.session, SessionEvent.EditPhase(toolUseId, EditReview(fileNameOf(call), EditPhase.DONE, outcome)))
        }
    }

    private fun outcomeOf(proposal: EditProposal, decision: Decision, reply: ToolReply): EditOutcome? = when (decision) {
        is Decision.Rejected -> EditOutcome.REJECTED
        Decision.Stopped -> EditOutcome.STOPPED
        is Decision.Accepted -> when {
            reply.isError -> null
            EditMatcher.normalizeNewlines(decision.finalText) != proposal.proposed -> EditOutcome.ACCEPTED_CHANGED
            else -> EditOutcome.ACCEPTED
        }
    }

    companion object {
        /** The project's edits. */
        fun get(project: Project): TandemEdits = project.service()
    }
}

/** The diff of a proposal could not be opened ([reason]): the call is refused, and the notice is shown already. */
class DiffNotOpened(val reason: String) : RuntimeException(reason)

private fun reasonOf(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

/**
 * The bare file name a call's `file_path` names ([EditReview.file]), the whole value when it has no separator. The rows build their
 * own labels from the call's input through `ToolLabels.fileName(input, cwd)`, relative to the session's cwd as every file row is;
 * this name is for the review's identity, logs and tests, never for a label (A4 reads the input, not [EditReview.file]).
 */
private fun fileNameOf(call: ToolCall): String {
    val given = (call.arguments["file_path"] as? JsonPrimitive)?.content.orEmpty()
    return try {
        Path.of(given).fileName?.toString() ?: given
    } catch (_: InvalidPathException) {
        given.substringAfterLast('/').substringAfterLast('\\')
    }
}

/** [EditSessions] from the project's `TandemSessions`. */
private class ManagerSessions(private val project: Project) : EditSessions {
    private val manager get() = TandemSessions.get(project).manager

    private fun handle(id: SessionId): SessionHandle? = manager.sessions.value.firstOrNull { it.id == id }

    override fun state(id: SessionId): ChatState? = handle(id)?.state?.value

    /**
     * The folders the live process has, as its `system/init` lists them (spec 7 item 4: "as the CLI's own tools have"), not the
     * folders added in the UI that the process has not been spawned with yet. A tool call comes after an init, so the list is there.
     */
    override fun roots(id: SessionId): List<Path> {
        val handle = handle(id) ?: return emptyList()
        return handle.state.value.additionalDirectories.distinct().mapNotNull {
            try {
                Path.of(it)
            } catch (_: InvalidPathException) {
                null
            }
        }
    }

    override fun event(id: SessionId, event: SessionEvent.EditEvent) = manager.editEvent(id, event)

    override fun notice(id: SessionId, text: String) {
        handle(id)?.notice(NoticeLevel.ERROR, text)
    }
}
