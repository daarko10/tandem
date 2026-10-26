package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.McpStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

// The `+` menu's Connectors page (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 17.2, 17.5): pure, built off the EDT.

/** The MCP request that runs, as `chat/` sees the panel controller's busy state: the server's [name], the [action] and its [caption]. */
data class McpBusyView(val name: String, val action: McpAction, val caption: String)

/**
 * One claude.ai connector's row: [key] and [name] the panel row's, [label] its display name, [on] its switch's position, [status] a
 * line for a status other than connected or disabled, [caption] while its request runs, [error] the last toggle's or the entry's, and
 * whether its switch can be used now ([switchable]).
 */
data class ConnectorRow(
    val key: String,
    val name: String,
    val label: String,
    val on: Boolean,
    val status: String?,
    val caption: String?,
    val error: String?,
    val switchable: Boolean,
)

/** The page's [rows] and the [note] under them, if any. */
data class ConnectorMenuModel(val rows: ImmutableList<ConnectorRow>, val note: String?)

const val NO_CONNECTORS: String = "No claude.ai connectors in this session."
const val CONNECTORS_LAST_KNOWN: String = "Last known: the session is not running."

/**
 * The Connectors page of [panel] (spec 17.2): the rows of its claude.ai connectors group, in its order. A switch is on for every status
 * but disabled, and shows the position [busy]'s request moves it to while that runs; no switch can be used while any request runs, on a
 * stopped panel, or for a row that offers neither Enable nor Disable (pending). [rowErrors] are the last toggles' errors by server
 * name; [notice] (the controller's) comes before the page's own notes.
 */
fun connectorMenu(panel: McpPanelModel, busy: McpBusyView?, rowErrors: Map<String, String>, notice: String?): ConnectorMenuModel {
    val group = panel.groups.firstOrNull { it.source == McpSource.CLAUDEAI }?.rows.orEmpty()
    val rows = group.map { row ->
        val running = busy?.takeIf { it.name == row.name }
        ConnectorRow(
            key = row.key,
            name = row.name,
            label = row.displayName,
            on = when (running?.action) {
                McpAction.ENABLE -> true
                McpAction.DISABLE -> false
                else -> row.status != McpStatus.DISABLED
            },
            status = row.label.takeUnless { row.status == McpStatus.CONNECTED || row.status == McpStatus.DISABLED },
            caption = running?.caption,
            error = rowErrors[row.name] ?: row.error,
            switchable = busy == null && !panel.stopped && (McpAction.ENABLE in row.actions || McpAction.DISABLE in row.actions),
        )
    }
    val note = notice ?: when {
        rows.isEmpty() -> NO_CONNECTORS
        panel.stopped -> CONNECTORS_LAST_KNOWN
        else -> null
    }
    return ConnectorMenuModel(rows.toImmutableList(), note)
}

/** What [row]'s switch runs: nothing unless it is switchable; Disable when it is on, else Enable. */
fun toggleAction(row: ConnectorRow): McpAction? = when {
    !row.switchable -> null
    row.on -> McpAction.DISABLE
    else -> McpAction.ENABLE
}
