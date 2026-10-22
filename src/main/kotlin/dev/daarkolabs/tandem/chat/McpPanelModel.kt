package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.McpStatus
import dev.daarkolabs.tandem.session.McpState
import dev.daarkolabs.tandem.session.TANDEM_MCP_SERVER
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.net.URI

// The MCP panel's rows (docs/superpowers/specs/2026-10-21-mcp-controls-design.md 3.2, 4.3): pure, built off the EDT.

/** What a row's button does, its label and the caption that replaces the row's buttons while its request runs. */
enum class McpAction(val label: String, val caption: String) {
    ENABLE("Enable", "Enabling…"),
    DISABLE("Disable", "Disabling…"),
    RECONNECT("Reconnect", "Reconnecting…"),
    AUTHENTICATE("Authenticate", "Starting sign-in…"),
}

/** The panel's groups, in the order they are drawn, each with its title. */
enum class McpSource(val title: String) {
    CLAUDEAI("claude.ai connectors"),
    PROJECT("Project"),
    LOCAL("Local"),
    USER("User"),
    PLUGIN("Plugins"),
    DYNAMIC("Session"),
    MANAGED("Managed"),
    OTHER("Other"),
}

/**
 * One server as the panel draws it. [key] is unique in the panel (two entries may share a [name]); [name] is what every request
 * sends, [displayName] what the row shows. [detail] is line 2 (the url's scheme, host and port only, else the command without its
 * args); [error] the entry's error verbatim, whatever the status. [readOnly]: Tandem's own server, which has no action. [rawSource]
 * is the wire source of an [McpSource.OTHER] row, for its pill.
 */
data class McpRowModel(
    val key: String,
    val name: String,
    val displayName: String,
    val status: McpStatus,
    val label: String,
    val detail: String?,
    val error: String?,
    val tools: String?,
    val version: String?,
    val actions: ImmutableList<McpAction>,
    val readOnly: Boolean,
    val rawSource: String?,
)

data class McpGroupModel(val source: McpSource, val rows: ImmutableList<McpRowModel>)

/**
 * The whole panel: [connected] of [total] servers connected, the non-empty [groups] in [McpSource] order, whether a server is still
 * [hasPending] (the panel refreshes again), and whether the session is [stopped] (every row without actions).
 */
data class McpPanelModel(
    val connected: Int,
    val total: Int,
    val groups: ImmutableList<McpGroupModel>,
    val hasPending: Boolean,
    val stopped: Boolean,
)

private const val CONNECTOR_PREFIX: String = "claude.ai "
private const val DYNAMIC_SOURCE: String = "dynamic"

/**
 * The panel of [state]. [ownServer] with source `dynamic` is Tandem's own server, read-only; a `needs-auth` row whose name is in
 * [loginHinted] (it showed the terminal hint, spec 3.3 step 4) and whose source is not `dynamic` also offers Reconnect. When the
 * session is not [live], the rows stay as last known with no action.
 */
fun mcpPanel(
    state: McpState,
    ownServer: String = TANDEM_MCP_SERVER,
    loginHinted: Set<String> = emptySet(),
    live: Boolean = true,
): McpPanelModel {
    val keys = HashSet<String>()
    val bySource = state.servers.map { server -> sourceOf(server) to server }
    val groups = McpSource.entries.mapNotNull { source ->
        val rows = bySource.filter { it.first == source }.map { (_, server) -> row(server, source, ownServer, loginHinted, live, keys) }
        if (rows.isEmpty()) null else McpGroupModel(source, rows.toImmutableList())
    }
    return McpPanelModel(
        connected = state.servers.count { it.status == McpStatus.CONNECTED },
        total = state.servers.size,
        groups = groups.toImmutableList(),
        hasPending = state.servers.any { it.status == McpStatus.PENDING },
        stopped = !live,
    )
}

/** The header badge, `connected/total`; null when the session reports no server (`0/0` is never drawn). */
fun mcpBadge(state: McpState): String? {
    if (state.servers.isEmpty()) return null
    return "${state.servers.count { it.status == McpStatus.CONNECTED }}/${state.servers.size}"
}

private fun sourceOf(server: McpServerInfo): McpSource = when {
    server.source == "claudeai" || server.config?.type == "claudeai-proxy" -> McpSource.CLAUDEAI
    server.source == "project" -> McpSource.PROJECT
    server.source == "local" -> McpSource.LOCAL
    server.source == "user" -> McpSource.USER
    server.source == "plugin" -> McpSource.PLUGIN
    server.source == DYNAMIC_SOURCE -> McpSource.DYNAMIC
    server.source == "managed" -> McpSource.MANAGED
    else -> McpSource.OTHER
}

private fun row(
    server: McpServerInfo,
    source: McpSource,
    ownServer: String,
    loginHinted: Set<String>,
    live: Boolean,
    keys: MutableSet<String>,
): McpRowModel {
    val own = server.name == ownServer && server.source == DYNAMIC_SOURCE
    val actions = if (own || !live) persistentListOf() else actionsOf(server, loginHinted)
    val count = server.tools?.size
    return McpRowModel(
        key = uniqueKey("${source.name}/${server.name}", keys),
        name = server.name,
        displayName = if (source == McpSource.CLAUDEAI) server.name.removePrefix(CONNECTOR_PREFIX).ifBlank { server.name } else server.name,
        status = server.status,
        label = labelOf(server),
        detail = detailOf(server),
        error = server.error,
        tools = count?.let { if (it == 1) "1 tool" else "$it tools" },
        version = server.serverInfo?.version?.takeIf { it.isNotBlank() }?.let { "v$it" },
        actions = actions,
        readOnly = own,
        rawSource = if (source == McpSource.OTHER) server.source else null,
    )
}

/** [base], or `base#2`, `base#3`… when an earlier row of the panel took it. */
private fun uniqueKey(base: String, keys: MutableSet<String>): String {
    var key = base
    var n = 1
    while (!keys.add(key)) key = "$base#${++n}"
    return key
}

/** The spec 3.2 table's label. */
private fun labelOf(server: McpServerInfo): String = when (server.status) {
    McpStatus.CONNECTED -> "Connected"
    McpStatus.PENDING -> "Pending"
    McpStatus.NEEDS_AUTH -> "Needs auth"
    McpStatus.FAILED -> "Failed"
    McpStatus.DISABLED -> "Disabled"
    McpStatus.OTHER -> server.statusText.ifBlank { "Unknown status" }
}

/** The spec 3.2 table's actions. */
private fun actionsOf(server: McpServerInfo, loginHinted: Set<String>): ImmutableList<McpAction> = when (server.status) {
    McpStatus.CONNECTED -> persistentListOf(McpAction.DISABLE)
    McpStatus.PENDING -> persistentListOf()
    McpStatus.NEEDS_AUTH -> if (server.name in loginHinted && server.source != DYNAMIC_SOURCE) {
        persistentListOf(McpAction.AUTHENTICATE, McpAction.RECONNECT, McpAction.DISABLE)
    } else {
        persistentListOf(McpAction.AUTHENTICATE, McpAction.DISABLE)
    }
    McpStatus.FAILED -> persistentListOf(McpAction.RECONNECT, McpAction.DISABLE)
    McpStatus.DISABLED -> persistentListOf(McpAction.ENABLE)
    McpStatus.OTHER -> persistentListOf(McpAction.DISABLE)
}

/**
 * Line 2: the url's scheme, host and port, else the command alone. Args, user info, the path (some hosted servers put their key in
 * it) and the query can carry tokens, and never show (spec 3.2). A url that does not parse, or has no host, falls back to the command.
 */
private fun detailOf(server: McpServerInfo): String? {
    val config = server.config ?: return null
    return config.url?.let(::origin) ?: config.command?.takeIf { it.isNotBlank() }
}

/** `scheme://host[:port]` of [url], its user info dropped, or null when it does not parse as one with a scheme and an authority. */
private fun origin(url: String): String? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme ?: return null
    // The raw authority keeps an `@` inside user info escaped, so the host and port follow the last one.
    val authority = uri.rawAuthority?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: return null
    return "$scheme://$authority"
}
