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
import dev.daarkolabs.tandem.session.McpStart
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/** How long the first live list of a session the panel started may take to reach the session's state, after its answer came. */
private const val LIVE_LIST_WAIT_MILLIS: Long = 2_000

/**
 * The panel's own start of its session: a session with no process when the panel opens (a resumed one before its first prompt) is
 * started, and until its process lists its servers the panel shows the project's last known ones, without actions.
 */
internal sealed interface McpPanelStart {
    /** No start of the panel's own: the session had a process when the panel opened, or the one it started has listed its servers. */
    data object None : McpPanelStart

    /** The session's process is starting, or has started and not listed its servers yet. */
    data object Starting : McpPanelStart

    /** Not started: the [maxLive] sessions that may run a process all run one, and none of them is idle. */
    data class NoRoom(val maxLive: Int) : McpPanelStart

    /** The start failed, or the process ended before it listed its servers: [message] says why (hard rule 5). */
    data class Failed(val message: String) : McpPanelStart
}

/**
 * The line under the sheet's title that says how the session stands: [text], in the error colour when [isError], with `Start session`
 * when [canStart].
 */
internal data class McpPanelNotice(val text: String, val isError: Boolean = false, val canStart: Boolean = false)

/**
 * The sheet's notice line for [model] while the panel's own start stands at [start]: while it starts, that it does; when the cap left
 * no room or the start failed, why, with `Start session`; a session that stopped while the panel was open says so, with `Start
 * session`; a live session without servers says it has none configured, unless the title area shows why its list is missing ([message]).
 */
internal fun mcpPanelNotice(model: McpPanelModel, start: McpPanelStart, message: String?): McpPanelNotice? = when (start) {
    McpPanelStart.Starting -> McpPanelNotice("Starting the session…")
    is McpPanelStart.NoRoom -> McpPanelNotice(noRoomText(start.maxLive), canStart = true)
    is McpPanelStart.Failed -> McpPanelNotice(start.message, isError = true, canStart = true)
    McpPanelStart.None -> when {
        model.stopped -> McpPanelNotice("The session is not running. Start it to manage its servers.", canStart = true)
        model.total == 0 && message == null -> McpPanelNotice("No MCP servers are configured for this session.")
        else -> null
    }
}

/** Why the panel did not start its session: every session the cap lets run a process is busy, none idle to stop. */
private fun noRoomText(maxLive: Int): String {
    val busy = if (maxLive == 1) "the 1 session that may run is busy" else "the $maxLive sessions that may run are all busy"
    return "Not started: $busy (Max live sessions, in Settings | Tools | Tandem). Start this one anyway to manage its servers."
}

/**
 * Why the process the panel started ended before it listed its servers: the chat's notice of its end, [reason], when there is one.
 */
internal fun startEndedText(reason: String?): String =
    if (reason != null) "The session could not start. $reason" else "The session could not start. Its chat says why."

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
 * A session without a process when a user action opened the panel ([startOnOpen]) is started ([McpControls.startForMcp], within the
 * cap): until its process has listed its servers, [rows] are the project's last known ones ([McpControls.knownMcpServers]), without
 * actions, and [start] says how the start stands; a start the cap stopped, or that failed, says why, and [startSession] starts it over
 * the cap. A panel shown again without one (its session selected again, the panel left open) starts nothing: a stopped session says
 * so, with `Start session`, as one that stops while the panel is open. Why a process the panel started ended comes from [endReason],
 * the chat's notice of its end.
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
    private val status: StateFlow<SessionStatus>,
    private val controls: McpControls,
    private val opener: BrowserOpener,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pendingRepollMillis: Long = 2_000,
    private val endReason: () -> String? = { null },
    /** Whether a user action opened the panel (the header's button, `/mcp`, a notice's link): only then is a stopped session started. */
    startOnOpen: Boolean = true,
) {
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])

    // One coroutine at a time: the fields below are read and written from coroutines of this scope only.
    private val work: CoroutineScope =
        CoroutineScope(scope.coroutineContext + job + dispatcher.limitedParallelism(1) + CoroutineName("McpPanelController"))

    private var refreshesRunning = 0
    private var repolls = 0
    private var repoll: Job? = null

    /** The status last seen, by the status watcher or by a start that saw its process attach: a start lists the servers only once. */
    private var lastStatus: SessionStatus = status.value

    private val mutableBusy = MutableStateFlow<McpBusy?>(null)
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val mutableMessageRetry = MutableStateFlow(false)
    private val mutableRefreshing = MutableStateFlow(false)
    private val mutableRowErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val mutableRetryActions = MutableStateFlow<Map<String, McpAction>>(emptyMap())

    // A session with no process when a user action opens the panel is started at once: its first rows are already the last known ones.
    private val mutableStart =
        MutableStateFlow(if (startOnOpen && status.value == SessionStatus.STOPPED) McpPanelStart.Starting else McpPanelStart.None)

    /** How the panel's own start of the session stands ([McpPanelStart]). */
    val start: StateFlow<McpPanelStart> = mutableStart.asStateFlow()

    /**
     * The panel's rows ([mcpPanel]): greyed, without actions, while the session is `STOPPED`; the project's last known ones, as greyed,
     * while the panel's own start has not brought the live list. The first model is built where the controller is made (a pure fold of
     * the servers already known), every later one on [dispatcher].
     */
    val rows: StateFlow<McpPanelModel> =
        combine(servers, controls.knownMcpServers, loginHinted, status, mutableStart) { state, known, hinted, now, start ->
            panelOf(state, known, hinted, now, start)
        }.stateIn(
            work,
            SharingStarted.Eagerly,
            panelOf(servers.value, controls.knownMcpServers.value, loginHinted.value, status.value, mutableStart.value),
        )

    /** The row whose request runs ([McpBusy]); the sign-in wait is not one. */
    val busy: StateFlow<McpBusy?> = mutableBusy.asStateFlow()

    /** The title area's error: a refresh's, or an action's on a server the follow-up list no longer has. */
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    /**
     * Whether [message] gets `Retry`, which lists the servers again: only a listing that failed or timed out (spec 3.2), never
     * `The session has ended.`, which a retry cannot help, nor an action's error moved there.
     */
    val messageRetry: StateFlow<Boolean> = mutableMessageRetry.asStateFlow()

    /** Whether a refresh runs: the title gets ` · Refreshing…`. */
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()

    /** A row's error by server name, shown under its buttons. */
    val rowErrors: StateFlow<Map<String, String>> = mutableRowErrors.asStateFlow()

    /** The action a row's `Retry` runs again, by server name; none for an error that a retry cannot help (`The session has ended.`). */
    val retryActions: StateFlow<Map<String, McpAction>> = mutableRetryActions.asStateFlow()

    /** ` · <name> is no longer configured` while a sign-in wait ended with the server gone (spec 3.3: 5 s, the wait's own life). */
    val titleNote: StateFlow<String?> = auth.map(::removedNote).stateIn(work, SharingStarted.Eagerly, removedNote(auth.value))

    init {
        val startAtOnce = mutableStart.value == McpPanelStart.Starting
        work.launch { if (startAtOnce) startNow(overCap = false) else refreshNow() }
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
        work.launch {
            status.collect { now ->
                val last = lastStatus
                lastStatus = now
                if (last == SessionStatus.STOPPED && now != SessionStatus.STOPPED) {
                    when (mutableStart.value) {
                        McpPanelStart.None -> launch { refreshNow() }
                        McpPanelStart.Starting -> Unit // the start lists them itself
                        // Started meanwhile from elsewhere (a prompt): its list replaces the last known one, as after the panel's start.
                        is McpPanelStart.NoRoom, is McpPanelStart.Failed -> launch {
                            mutableStart.value = McpPanelStart.Starting
                            listStarted()
                        }
                    }
                }
            }
        }
    }

    /** `Start session`: starts the session over the cap if it must, as a prompt would; nothing while a start runs. */
    fun startSession() {
        work.launch { if (mutableStart.value != McpPanelStart.Starting) startNow(overCap = true) }
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

    private fun panelOf(
        state: McpState,
        known: List<McpServerInfo>,
        hinted: Set<String>,
        now: SessionStatus,
        start: McpPanelStart,
    ): McpPanelModel =
        if (start == McpPanelStart.None) {
            mcpPanel(state, loginHinted = hinted, live = now != SessionStatus.STOPPED)
        } else {
            mcpPanel(McpState(known), live = false)
        }

    /** Starts the session ([McpControls.startForMcp]), then lists its servers ([listStarted]); or says why it did not start. */
    private suspend fun startNow(overCap: Boolean) {
        mutableStart.value = McpPanelStart.Starting
        showMessage(null, retry = false)
        when (val started = controls.startForMcp(id, overCap)) {
            McpStart.Running -> {
                lastStatus = status.value // its process attached: the status watcher has nothing more to list for it
                listStarted()
            }
            is McpStart.NoRoom -> mutableStart.value = McpPanelStart.NoRoom(started.maxLive)
            is McpStart.Failed -> mutableStart.value = McpPanelStart.Failed(started.reason)
        }
    }

    /**
     * The first listing of a process the panel started, or that started while the panel waited: once the session's state has the
     * list, it replaces the last known one. A process that ended first fails the start with why it ended; a listing the CLI refused
     * or did not answer leaves the running session's rows, and the title area says why the list is missing.
     */
    private suspend fun listStarted() {
        val listed = refreshNow()
        mutableStart.value = when (listed) {
            is McpResult.Ok -> {
                // The session's state takes the answer a step after the answer itself.
                withTimeoutOrNull(LIVE_LIST_WAIT_MILLIS) { servers.first { it.detailed && it.servers == listed.value } }
                McpPanelStart.None
            }
            is McpResult.Failed -> when (listed.error) {
                McpError.NotRunning, McpError.Ended -> {
                    showMessage(null, retry = false) // the start's own failure says it
                    McpPanelStart.Failed(startEndedText(endReason()))
                }
                is McpError.Rejected, is McpError.TimedOut -> McpPanelStart.None
            }
        }
    }

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
                showMessage(null, retry = false)
                afterList(result.value)
            }
            is McpResult.Failed -> when (val error = result.error) {
                is McpError.Rejected -> showMessage("Could not list servers: ${error.message}", retry = true)
                is McpError.TimedOut -> showMessage("Could not list servers: ${timedOut(error.seconds)}", retry = true)
                // The session is not running: the stopped sheet says so itself.
                McpError.NotRunning -> Unit
                McpError.Ended -> showMessage(SESSION_ENDED, retry = false)
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
            showMessage("$name: $shown · $name is no longer configured", retry = false)
            mutableRowErrors.update { it - name }
            mutableRetryActions.update { it - name }
        }
    }

    /**
     * Starts [name]'s sign-in. The wait itself shows through [auth]; only a start that failed shows in the row, with the hint the
     * manager's [loginHinted] goes with (spec 3.3 step 4), except for a link that could not be used (step 2), which gets none.
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
        val linkFailed = (error as? McpError.Rejected)?.linkFailed == true
        rowError(name, if (linkFailed) text else "$text\n${signInHint(name)}", retry = McpAction.AUTHENTICATE)
    }

    /** What to do after a sign-in that could not start, by the server's source: a `dynamic` one is unknown to a terminal login. */
    private fun signInHint(name: String): String =
        if (servers.value.servers.firstOrNull { it.name == name }?.source == DYNAMIC_SOURCE) {
            "Start a new session to try the sign-in again."
        } else {
            "Run claude mcp login \"$name\" in a terminal, then press Reconnect, or start a new session."
        }

    private fun showMessage(text: String?, retry: Boolean) {
        mutableMessageRetry.value = retry && text != null
        mutableMessage.value = text
    }

    private fun rowError(name: String, text: String, retry: McpAction?) {
        mutableRowErrors.update { it + (name to text) }
        mutableRetryActions.update { if (retry != null) it + (name to retry) else it - name }
    }

    private fun removedNote(waits: Map<String, McpAuthWait>): String? =
        waits.values.filter { it.outcome == McpAuthOutcome.Removed }.joinToString("") { " · ${it.server} is no longer configured" }.ifEmpty { null }
}
