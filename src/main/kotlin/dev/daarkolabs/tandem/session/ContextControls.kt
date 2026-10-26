package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.ContextBreakdown

/**
 * What the context popover asks of a session (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 4.2), by session id.
 * [SessionManager] implements it; the popover's controller depends on this interface only. Safe to call from any thread.
 */
interface ContextControls {
    /**
     * The session's context window by category (`get_context_usage`, 30 s); a success also replaces [ChatState.contextBreakdown].
     * [McpError.NotRunning] without a live process. One request is in flight per session: a call made while one runs waits for,
     * and shares, one follow-up run.
     */
    suspend fun contextBreakdown(id: SessionId): ControlResult<ContextBreakdown>
}
