package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.McpAction
import dev.daarkolabs.tandem.chat.McpPanelModel
import dev.daarkolabs.tandem.chat.McpRowModel
import dev.daarkolabs.tandem.chat.formatDuration
import dev.daarkolabs.tandem.chat.mcpPanel
import dev.daarkolabs.tandem.ide.BrowserOpener
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.McpStatus
import dev.daarkolabs.tandem.session.McpAuthOutcome
import dev.daarkolabs.tandem.session.McpAuthWait
import dev.daarkolabs.tandem.session.McpControls
import dev.daarkolabs.tandem.session.McpError
import dev.daarkolabs.tandem.session.McpResult
import dev.daarkolabs.tandem.session.McpState
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The MCP panel's controller (docs/superpowers/specs/2026-10-21-mcp-controls-design.md 3.2, 3.3, 4.4): its requests and their
// errors, off the EDT. The sheet only draws what it exposes.

/** One of the four requests running on row [name] ([McpPanelController.busy]): its [caption] replaces the row's buttons. */
internal data class McpBusy(val name: String, val action: McpAction, val caption: String, val since: Long)

/** What a row shows under it for its sign-in wait (spec 3.3): [text], in the error colour when [isError], with `Cancel` when [showCancel]. */
internal data class AuthCaption(val text: String, val isError: Boolean, val showCancel: Boolean)

/** A pending server is listed again at most this many times in a row (spec 3.2: transient). */
private const val MAX_REPOLLS: Int = 5

private const val DYNAMIC_SOURCE: String = "dynamic"

private const val SESSION_ENDED: String = "The session has ended."

private fun timedOut(seconds: Int): String = "The CLI did not answer within $seconds s."

/**
 * The caption of a sign-in [wait] at [nowMillis] (spec 3.3): the elapsed wait with `Cancel` while it runs, then how it ended.
 * [McpAuthOutcome.Changed] has none (the row's own status and error say it), nor [McpAuthOutcome.Removed] (the panel's title says
 * it, [McpPanelController.titleNote]). A poll whose last tick failed shows the bare error: the sign-in did start. No caption carries
 * a hint.
 */
internal fun authCaption(wait: McpAuthWait, nowMillis: Long): AuthCaption? = when (val outcome = wait.outcome) {
    null -> AuthCaption("Waiting for the browser… · ${formatDuration(nowMillis - wait.startedAtMillis)}", isError = false, showCancel = true)
    McpAuthOutcome.Connected -> AuthCaption("Signed in", isError = false, showCancel = false)
    McpAuthOutcome.StillNeedsAuth ->
        AuthCaption("Still needs auth after 3 minutes. Finish signing in, then press Refresh servers.", isError = false, showCancel = false)
    McpAuthOutcome.NoLink -> AuthCaption("Could not start sign-in: the CLI sent no sign-in link", isError = true, showCancel = false)
    is McpAuthOutcome.Failed -> AuthCaption(
        when (val error = outcome.error) {
            is McpError.Rejected -> error.message
            is McpError.TimedOut -> timedOut(error.seconds)
            McpError.Ended, McpError.NotRunning -> SESSION_ENDED
        },
        isError = true,
        showCancel = false,
    )
    is McpAuthOutcome.Changed, McpAuthOutcome.Removed -> null
}

/** What a row shows at its right (spec 3.2), from [mcpRowButtons]. */
internal sealed interface McpRowButtons {
    /** The session is stopped: no buttons. */
    data object None : McpRowButtons

    /** One of the four requests runs on this row: its [caption] in place of the buttons. */
    data class Busy(val caption: String) : McpRowButtons

    /** The row's sign-in wait runs: its one button, `Cancel`, disabled while a request runs on another row. */
    data class Cancel(val enabled: Boolean) : McpRowButtons

    /** The row's [actions] (none for a pending row or Tandem's own server), disabled while a request runs on another row. */
    data class Actions(val actions: List<McpAction>, val enabled: Boolean) : McpRowButtons
}

/**
 * The buttons of [row] (spec 3.2): none while the session is [stopped]; the caption of the request that runs on it ([busy]); its
 * sign-in [wait]'s `Cancel` while the wait runs, beside the other rows' own waits and buttons; else its actions. While a request
 * runs on another row, every button here is disabled.
 */
internal fun mcpRowButtons(row: McpRowModel, busy: McpBusy?, wait: McpAuthWait?, stopped: Boolean): McpRowButtons = when {
    stopped -> McpRowButtons.None
    busy?.name == row.name -> McpRowButtons.Busy(busy.caption)
    wait != null && wait.outcome == null -> McpRowButtons.Cancel(enabled = busy == null)
    else -> McpRowButtons.Actions(row.actions, enabled = busy == null)
}

/** Whether these buttons hold an enabled one: the first such row's first button takes the focus when the panel opens. */
internal fun McpRowButtons.hasEnabledButton(): Boolean = when (this) {
    McpRowButtons.None, is McpRowButtons.Busy -> false
    is McpRowButtons.Cancel -> enabled
    is McpRowButtons.Actions -> enabled && actions.isNotEmpty()
}

/**
 * One open MCP panel of session [id] (spec 4.4): [rows] from the session's [servers], the servers that showed the terminal hint
 * ([loginHinted]) and its [status]; the four requests through [controls], a sign-in's link opened by [opener].
 *
 * It lists the servers when it opens, on every new `system/init` (`initCount`), when the session's process starts (its [status]
 * leaves `STOPPED`) and, while an answer lists a `pending` server, again [pendingRepollMillis] later, at most 5 times in a row. After
 * a row action it lists them again, unless the session is not running or has ended. A refresh's error shows in the title area
 * ([message]); an action's in the row ([rowErrors], with [retryActions]), moved to the title area when the follow-up list no
 * longer has the server. Every request runs on [dispatcher], one coroutine at a time, never on the calling thread: the public
 * functions only launch, and are safe on the EDT. [close] cancels all of it; a sign-in already started goes on in the session.
 */
internal class McpPanelController(
    private val id: SessionId,
    private val servers: StateFlow<McpState>,
    /** The sign-in waits of the session, by server ([dev.daarkolabs.tandem.session.SessionHandle.mcpAuth]). */
    val auth: StateFlow<Map<String, McpAuthWait>>,
    loginHinted: StateFlow<Set<String>>,
    status: StateFlow<SessionStatus>,
    private val controls: McpControls,
    private val opener: BrowserOpener,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pendingRepollMillis: Long = 2_000,
) {
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])

    // One coroutine at a time: the fields below are read and written from coroutines of this scope only.
    private val work: CoroutineScope =
        CoroutineScope(scope.coroutineContext + job + dispatcher.limitedParallelism(1) + CoroutineName("McpPanelController"))

    private var refreshesRunning = 0
    private var repolls = 0
    private var repoll: Job? = null

    private val mutableBusy = MutableStateFlow<McpBusy?>(null)
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val mutableRefreshing = MutableStateFlow(false)
    private val mutableRowErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val mutableRetryActions = MutableStateFlow<Map<String, McpAction>>(emptyMap())

    /**
     * The panel's rows ([mcpPanel]): greyed, without actions, while the session is `STOPPED`. The first model is built where the
     * controller is made (a pure fold of the servers the session already lists), every later one on [dispatcher].
     */
    val rows: StateFlow<McpPanelModel> =
        combine(servers, loginHinted, status) { state, hinted, now -> panelOf(state, hinted, now) }
            .stateIn(work, SharingStarted.Eagerly, panelOf(servers.value, loginHinted.value, status.value))

    /** The row whose request runs ([McpBusy]); the sign-in wait is not one. */
    val busy: StateFlow<McpBusy?> = mutableBusy.asStateFlow()

    /** The title area's error: a refresh's, or an action's on a server the follow-up list no longer has. Shown with `Retry`. */
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    /** Whether a refresh runs: the title gets ` · Refreshing…`. */
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()

    /** A row's error by server name, shown under its buttons. */
    val rowErrors: StateFlow<Map<String, String>> = mutableRowErrors.asStateFlow()

    /** The action a row's `Retry` runs again, by server name; none for an error that a retry cannot help (`The session has ended.`). */
    val retryActions: StateFlow<Map<String, McpAction>> = mutableRetryActions.asStateFlow()

    /** ` · <name> is no longer configured` while a sign-in wait ended with the server gone (spec 3.3: 5 s, the wait's own life). */
    val titleNote: StateFlow<String?> = auth.map(::removedNote).stateIn(work, SharingStarted.Eagerly, removedNote(auth.value))

    init {
        work.launch { refreshNow() }
        val initCount = servers.value.initCount
        work.launch {
            var seen = initCount
            servers.collect { state ->
                if (state.initCount != seen) {
                    seen = state.initCount
                    launch { refreshNow() }
                }
            }
        }
        val first = status.value
        work.launch {
            var last = first
            status.collect { now ->
                if (last == SessionStatus.STOPPED && now != SessionStatus.STOPPED) launch { refreshNow() }
                last = now
            }
        }
    }

    /** Lists the servers again (`Refresh servers`, the title area's `Retry`). */
    fun refresh() {
        work.launch { refreshNow() }
    }

    /** Runs [action] on server [name]: one of the four requests, with its caption on the row while it runs. */
    fun run(action: McpAction, name: String) {
        work.launch {
            mutableRowErrors.update { it - name }
            mutableRetryActions.update { it - name }
            mutableBusy.value = McpBusy(name, action, action.caption, System.currentTimeMillis())
            if (action == McpAction.AUTHENTICATE) authenticate(name) else act(action, name)
        }
    }

    /** Stops waiting for [name]'s sign-in. Not a request to the CLI: the manager only cancels its poll, at once. */
    fun cancelAuth(name: String) {
        controls.cancelMcpAuth(id, name)
    }

    /** Cancels every request of the panel: no request goes out until a panel opens again. */
    fun close() {
        job.cancel()
    }

    private fun panelOf(state: McpState, hinted: Set<String>, now: SessionStatus): McpPanelModel =
        mcpPanel(state, loginHinted = hinted, live = now != SessionStatus.STOPPED)

    private suspend fun refreshNow(): McpResult<List<McpServerInfo>> {
        refreshesRunning++
        mutableRefreshing.value = true
        val result = try {
            controls.refreshMcp(id)
        } finally {
            if (--refreshesRunning == 0) mutableRefreshing.value = false
        }
        when (result) {
            is McpResult.Ok -> {
                // The plan's rule: every Ok clears the title area, a re-poll's included, so an action error moved there by
                // act lasts until the next listing (it was shown, hard rule 5).
                mutableMessage.value = null
                afterList(result.value)
            }
            is McpResult.Failed -> when (val error = result.error) {
                is McpError.Rejected -> mutableMessage.value = "Could not list servers: ${error.message}"
                is McpError.TimedOut -> mutableMessage.value = "Could not list servers: ${timedOut(error.seconds)}"
                // The session is not running: the stopped sheet says so itself.
                McpError.NotRunning -> Unit
                McpError.Ended -> mutableMessage.value = SESSION_ENDED
            }
        }
        return result
    }

    /**
     * Asks again [pendingRepollMillis] after an answer that lists a `pending` server, at most [MAX_REPOLLS] times in a row; an answer
     * without one resets the count. Driven by the answers, not by [rows]: an answer equal to the last one changes no flow.
     */
    private fun afterList(listed: List<McpServerInfo>) {
        if (listed.none { it.status == McpStatus.PENDING }) {
            repolls = 0
            return
        }
        if (repoll?.isActive == true || repolls >= MAX_REPOLLS) return
        repolls++
        repoll = work.launch {
            delay(pendingRepollMillis)
            repoll = null
            refreshNow()
        }
    }

    private suspend fun act(action: McpAction, name: String) {
        val result = try {
            if (action == McpAction.RECONNECT) controls.reconnectMcp(id, name) else controls.toggleMcp(id, name, enabled = action == McpAction.ENABLE)
        } finally {
            mutableBusy.value = null
        }
        val error = (result as? McpResult.Failed)?.error
        when (error) {
            null, McpError.NotRunning -> Unit
            is McpError.Rejected -> rowError(name, error.message, retry = action)
            is McpError.TimedOut -> rowError(name, timedOut(error.seconds), retry = action)
            McpError.Ended -> rowError(name, SESSION_ENDED, retry = null)
        }
        if (error == McpError.NotRunning || error == McpError.Ended) return
        val listed = refreshNow()
        val shown = mutableRowErrors.value[name]
        if (shown != null && listed is McpResult.Ok && listed.value.none { it.name == name }) {
            // The row is gone with the server: its error moves to the title area, never lost with the row (hard rule 5).
            mutableMessage.value = "$name: $shown · $name is no longer configured"
            mutableRowErrors.update { it - name }
            mutableRetryActions.update { it - name }
        }
    }

    /**
     * Starts [name]'s sign-in. The wait itself shows through [auth]; only a start that failed shows in the row, with the hint the
     * manager's [loginHinted] goes with (spec 3.3 step 4).
     */
    private suspend fun authenticate(name: String) {
        val result = try {
            controls.startMcpAuth(id, name, opener::open)
        } finally {
            mutableBusy.value = null
        }
        val error = (result as? McpResult.Failed)?.error ?: return
        val text = when (error) {
            McpError.NotRunning -> return
            McpError.Ended -> {
                rowError(name, SESSION_ENDED, retry = null)
                return
            }
            is McpError.Rejected -> "Could not start sign-in: ${error.message}"
            is McpError.TimedOut -> timedOut(error.seconds)
        }
        rowError(name, "$text\n${signInHint(name)}", retry = McpAction.AUTHENTICATE)
    }

    /** What to do after a sign-in that could not start, by the server's source: a `dynamic` one is unknown to a terminal login. */
    private fun signInHint(name: String): String =
        if (servers.value.servers.firstOrNull { it.name == name }?.source == DYNAMIC_SOURCE) {
            "Start a new session to try the sign-in again."
        } else {
            "Run claude mcp login \"$name\" in a terminal, then press Reconnect, or start a new session."
        }

    private fun rowError(name: String, text: String, retry: McpAction?) {
        mutableRowErrors.update { it + (name to text) }
        mutableRetryActions.update { if (retry != null) it + (name to retry) else it - name }
    }

    private fun removedNote(waits: Map<String, McpAuthWait>): String? =
        waits.values.filter { it.outcome == McpAuthOutcome.Removed }.joinToString("") { " · ${it.server} is no longer configured" }.ifEmpty { null }
}
