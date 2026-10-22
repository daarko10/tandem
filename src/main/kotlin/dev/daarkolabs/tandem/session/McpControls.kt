package dev.daarkolabs.tandem.session

// MCP controls of a session (docs/superpowers/specs/2026-10-21-mcp-controls-design.md 4.2): what an MCP request answers.

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
