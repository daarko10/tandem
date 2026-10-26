package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.McpAuthStart
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.McpStatus
import kotlinx.coroutines.flow.StateFlow
import java.net.URI

// MCP controls of a session (docs/superpowers/specs/2026-10-21-mcp-controls-design.md 4.2): what an MCP request answers.

/**
 * What the MCP panel asks of a session's MCP servers, by session id. [SessionManager] implements it; the panel's controller depends
 * on this interface only, so its tests pass a recording fake. Every request answers [McpError.NotRunning] when the session has no
 * live process; [startForMcp] starts one. Safe to call from any thread; the suspend functions never block one.
 */
interface McpControls {
    /**
     * Lists the session's servers (`mcp_status`, 15 s); a success also replaces [SessionHandle.mcpServers]. One `mcp_status` is in
     * flight per session: a call made while one runs waits for, and shares, one follow-up run.
     */
    suspend fun refreshMcp(id: SessionId): McpResult<List<McpServerInfo>>

    /** Disables or enables [server] (`mcp_toggle`, 15 s). The action only: the caller refreshes after it. */
    suspend fun toggleMcp(id: SessionId, server: String, enabled: Boolean): McpResult<Unit>

    /** Reconnects [server] (`mcp_reconnect`, 30 s). The action only: the caller refreshes after it. */
    suspend fun reconnectMcp(id: SessionId, server: String): McpResult<Unit>

    /**
     * Starts [server]'s sign-in (`mcp_authenticate`, 30 s) and, given a link, calls [open] with it and waits in the session for the
     * server to connect ([SessionHandle.mcpAuth]). Runs whole in the session's scope: a caller cancelled before or after the answer
     * cuts off neither the browser nor the wait. Posts its outcome in the chat itself.
     */
    suspend fun startMcpAuth(id: SessionId, server: String, open: suspend (url: String) -> Unit): McpResult<McpAuthStart>

    /** Stops waiting for [server]'s sign-in: its wait leaves [SessionHandle.mcpAuth], without a notice. */
    fun cancelMcpAuth(id: SessionId, server: String)

    /**
     * The project's last known servers: the last `mcp_status` answer any process of the project gave, each entry cut to what the
     * panel shows ([lastKnown]). Kept in memory only; empty until a process has answered. What the panel of a session without a
     * live list shows, marked as last known.
     */
    val knownMcpServers: StateFlow<List<McpServerInfo>>

    /**
     * Starts session [id]'s process for its MCP panel, unless one runs: resumed with its usual arguments, no prompt sent. Within the
     * live-session cap, an idle session may be stopped to make room and one in a turn never is; when that is not enough nothing
     * starts ([McpStart.NoRoom]), unless [overCap] (the user pressed Start session), which starts it over the cap with the chat's
     * notice, as a prompt would. Runs whole in the session's scope.
     */
    suspend fun startForMcp(id: SessionId, overCap: Boolean): McpStart
}

/** What [McpControls.startForMcp] came to. */
sealed interface McpStart {
    /** A process runs for the session: it ran already, or started now (it may still end before it answers anything). */
    data object Running : McpStart

    /** Nothing started: [maxLive] sessions run a process and none of them is idle. */
    data class NoRoom(val maxLive: Int) : McpStart

    /** No process could start; [reason] says why, as the chat does. */
    data class Failed(val reason: String) : McpStart
}

/**
 * [this] as [McpControls.knownMcpServers] keeps it: what the panel shows and no more, so the launch config keeps its type, its command
 * and its url's origin ([mcpUrlOrigin]), and drops its args and the rest of its url, which can carry tokens.
 */
internal fun McpServerInfo.lastKnown(): McpServerInfo =
    copy(config = config?.let { it.copy(args = emptyList(), url = it.url?.let(::mcpUrlOrigin)) })

/**
 * `scheme://host[:port]` of [url], its user info dropped, or null when it does not parse as one with a scheme and an authority: all of
 * a server's url the MCP panel shows (spec 3.2). The path (some hosted servers put their key in it) and the query never show.
 */
fun mcpUrlOrigin(url: String): String? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme ?: return null
    // The raw authority keeps an `@` inside user info escaped, so the host and port follow the last one.
    val authority = uri.rawAuthority?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: return null
    return "$scheme://$authority"
}

/** A sign-in wait of [server], since [startedAtMillis]; [outcome] once it ended (the wait then leaves the map 5 s later). */
data class McpAuthWait(val server: String, val startedAtMillis: Long, val outcome: McpAuthOutcome? = null)

/** How a sign-in wait ended. */
sealed interface McpAuthOutcome {
    /** The server connected. */
    data object Connected : McpAuthOutcome

    /** The server still needed auth when the wait ran out, the last check answered. */
    data object StillNeedsAuth : McpAuthOutcome

    /** The server went to another state than `connected` or `needs-auth`, with its [error] if it has one. */
    data class Changed(val status: McpStatus, val error: String?) : McpAuthOutcome

    /** The server is no longer listed. */
    data object Removed : McpAuthOutcome

    /** The CLI's answer had no sign-in link to open, and the server still needs auth. */
    data object NoLink : McpAuthOutcome

    /** The wait failed: the session ended ([McpError.Ended]), or the last check before the deadline failed with [error]. */
    data class Failed(val error: McpError) : McpAuthOutcome
}

/** What an MCP control request came to: [Ok] with its answer, or [Failed] with why. */
sealed interface McpResult<out T> {
    data class Ok<T>(val value: T) : McpResult<T>

    data class Failed(val error: McpError) : McpResult<Nothing>
}

/** Why an MCP control request failed. */
sealed interface McpError {
    /**
     * The CLI answered with an error, or with an answer Tandem cannot use; [message] says which, as the CLI wrote it when it did.
     * [linkFailed]: `mcp_authenticate` itself succeeded, but its sign-in link was not an `http(s)` URL or did not open, so no
     * terminal sign-in is hinted (MCP spec 3.3 step 2; step 4's hint is for an error of the request itself).
     */
    data class Rejected(val message: String, val linkFailed: Boolean = false) : McpError

    /** The CLI did not answer within [seconds]. */
    data class TimedOut(val seconds: Int) : McpError

    /** The session has no live process to ask. */
    data object NotRunning : McpError

    /** The process ended (it exited, failed to start, or was closed or stopped) before it answered. */
    data object Ended : McpError
}

/** The MCP server Tandem itself gives the CLI (`mcp__tandem__edit`); Tandem's own only with `source == "dynamic"`. */
const val TANDEM_MCP_SERVER: String = "tandem"
