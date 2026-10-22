package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.McpAuthStart
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.McpStatus

// MCP controls of a session (docs/superpowers/specs/2026-10-21-mcp-controls-design.md 4.2): what an MCP request answers.

/**
 * What the MCP panel asks of a session's MCP servers, by session id. [SessionManager] implements it; the panel's controller depends
 * on this interface only, so its tests pass a recording fake. Every call answers [McpError.NotRunning] when the session has no live
 * process. Safe to call from any thread; the suspend functions never block one.
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
    /** The CLI answered with an error, or with an answer Tandem cannot use; [message] says which, as the CLI wrote it when it did. */
    data class Rejected(val message: String) : McpError

    /** The CLI did not answer within [seconds]. */
    data class TimedOut(val seconds: Int) : McpError

    /** The session has no live process to ask. */
    data object NotRunning : McpError

    /** The process ended (it exited, failed to start, or was closed or stopped) before it answered. */
    data object Ended : McpError
}

/** The MCP server Tandem itself gives the CLI (`mcp__tandem__edit`); Tandem's own only with `source == "dynamic"`. */
const val TANDEM_MCP_SERVER: String = "tandem"
