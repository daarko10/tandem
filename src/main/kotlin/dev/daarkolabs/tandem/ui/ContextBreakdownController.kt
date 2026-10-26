package dev.daarkolabs.tandem.ui

import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.chat.ContextPopoverModel
import dev.daarkolabs.tandem.chat.contextPopover
import dev.daarkolabs.tandem.protocol.ContextBreakdown
import dev.daarkolabs.tandem.session.ContextControls
import dev.daarkolabs.tandem.session.ContextUsage
import dev.daarkolabs.tandem.session.McpError
import dev.daarkolabs.tandem.session.McpResult
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// The context popover's controller (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 3.1, 4.4): its requests and their
// outcome, off the EDT. The popover only draws what it exposes.

/** Where the popover's request stands: none running ([Idle]), one running ([Loading]), the last one [Failed], or no process ([Stopped]). */
internal sealed interface FetchState {
    data object Idle : FetchState

    data object Loading : FetchState

    data class Failed(val text: String) : FetchState

    data object Stopped : FetchState
}

private val LOG = logger<ContextBreakdownController>()

/**
 * One open context popover's requests for session [id] (spec 3.1), as `McpPanelController` is one open MCP panel's: `get_context_usage`
 * through [controls] once on construction (unless [status] is `STOPPED`), again on every change of [turnsEnded] after its first value,
 * when [status] leaves `STOPPED`, and on [retry]; a request asked while one runs runs once after it (at most one waits). Every request
 * runs on [dispatcher] in a child of [scope] that [close] cancels, so a closed popover asks nothing more. [model] is the popover of
 * [breakdown] (the session's last answer, which a success replaces) and [ring], built on [dispatcher], with memory-file paths shown
 * relative to [projectRoot] or with [home] as `~`. [rowsOpen] is the category list's state, collapsed on open.
 */
internal class ContextBreakdownController(
    private val id: SessionId,
    breakdown: StateFlow<ContextBreakdown?>,
    ring: StateFlow<ContextUsage?>,
    turnsEnded: StateFlow<Int>,
    private val status: StateFlow<SessionStatus>,
    private val controls: ContextControls,
    scope: CoroutineScope,
    home: String?,
    projectRoot: String?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val own = CoroutineScope(scope.coroutineContext + job + dispatcher + CoroutineName("Tandem context popover"))

    /** The popover's contents; until the first build on [dispatcher], the ring's header alone (cheap: no categories). */
    val model: StateFlow<ContextPopoverModel> = combine(breakdown, ring) { b, r -> contextPopover(b, r, home, projectRoot) }
        .stateIn(own, SharingStarted.Eagerly, contextPopover(null, ring.value, home, projectRoot))

    private val mutableFetch = MutableStateFlow<FetchState>(if (status.value == SessionStatus.STOPPED) FetchState.Stopped else FetchState.Idle)

    /** The request's state, which the popover's status line follows (`popoverStatus`). */
    val fetch: StateFlow<FetchState> = mutableFetch.asStateFlow()

    private val mutableRowsOpen = MutableStateFlow(false)

    /** Whether the category rows and groups are shown: `false` on open (the popover opens compact). */
    val rowsOpen: StateFlow<Boolean> = mutableRowsOpen.asStateFlow()

    /** Asks for a request: one runs at a time, and any number asked meanwhile run as one after it. */
    private val triggers = Channel<Unit>(Channel.CONFLATED)

    init {
        own.launch {
            for (trigger in triggers) fetchOnce()
        }
        triggers.trySend(Unit)
        val firstTurns = turnsEnded.value
        own.launch {
            var last = firstTurns
            turnsEnded.collect { turns ->
                if (turns != last) {
                    last = turns
                    triggers.trySend(Unit)
                }
            }
        }
        val firstStopped = status.value == SessionStatus.STOPPED
        own.launch {
            // A change either way: leaving STOPPED asks again, entering it shows Stopped (the loop checks the status first).
            var stopped = firstStopped
            status.collect { now ->
                val isStopped = now == SessionStatus.STOPPED
                if (isStopped != stopped) {
                    stopped = isStopped
                    triggers.trySend(Unit)
                }
            }
        }
    }

    /** One request, unless the session has no process; [fetch] is `Loading` while it runs, then says how it came out. */
    private suspend fun fetchOnce() {
        if (status.value == SessionStatus.STOPPED) {
            mutableFetch.value = FetchState.Stopped
            return
        }
        mutableFetch.value = FetchState.Loading
        mutableFetch.value = try {
            when (val result = controls.contextBreakdown(id)) {
                is McpResult.Ok -> FetchState.Idle // the reducer already holds the breakdown
                is McpResult.Failed -> when (val error = result.error) {
                    is McpError.Rejected -> FetchState.Failed("Could not load the breakdown: ${error.message}")
                    is McpError.TimedOut -> FetchState.Failed("The CLI did not answer within ${error.seconds} s.")
                    McpError.NotRunning, McpError.Ended -> FetchState.Stopped
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never silent (hard rule 5): the popover says the request failed; the log names the class only.
            LOG.warn("get_context_usage failed for session $id: ${e.javaClass.name}")
            FetchState.Failed("Could not load the breakdown: ${e.javaClass.simpleName}")
        }
    }

    /** The category list shown or hidden (the header's toggle). */
    fun toggleRows() {
        mutableRowsOpen.value = !mutableRowsOpen.value
    }

    /** Asks again (the error line's `Retry`). */
    fun retry() {
        triggers.trySend(Unit)
    }

    /** The popover closed: no request runs or starts from here on. */
    fun close() {
        triggers.close()
        job.cancel()
    }
}
