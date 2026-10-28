package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.util.Locale

// MCP servers, their control requests and the slash-command catalogue (docs/superpowers/specs/2026-10-21-mcp-controls-design.md 4.1).
// Every field is optional and validated: a mistyped field reads as null or empty, an entry without its name is skipped, nothing throws.

/** A server's state as `init.mcp_servers` and `mcp_status` report it. [OTHER] is any other value; the entry's `statusText` keeps it. */
enum class McpStatus(val wire: String) {
    CONNECTED("connected"),
    PENDING("pending"),
    NEEDS_AUTH("needs-auth"),
    FAILED("failed"),
    DISABLED("disabled"),
    OTHER(""),
    ;

    companion object {
        fun fromWire(s: String?): McpStatus = entries.firstOrNull { it != OTHER && it.wire == s } ?: OTHER
    }
}

/** One entry of `init.mcp_servers` (`mcp-status:6`). [statusText] is the wire text, `""` when missing or not a string. */
data class McpServerSummary(val name: String, val status: McpStatus, val statusText: String, val source: String?)

/** One entry of an `mcp_status` answer (`mcp-status:18`); [tools] is null when the entry has none (a failed or disabled server). */
data class McpServerInfo(
    val name: String,
    val status: McpStatus,
    val statusText: String,
    val error: String? = null,
    val source: String? = null,
    val scope: String? = null,
    val config: McpConfig? = null,
    val serverInfo: McpPeer? = null,
    val tools: List<McpTool>? = null,
)

/** A server's launch config: `stdio`, `http`, `sse` or `claudeai-proxy`. Never decodes `headers` or `env`, which can hold secrets. */
data class McpConfig(val type: String?, val command: String?, val args: List<String>, val url: String?)

/** The server's own name and version, from its MCP handshake. */
data class McpPeer(val name: String?, val version: String?)

data class McpTool(val name: String)

data class McpStatusResponse(val servers: List<McpServerInfo>)

/** An `mcp_authenticate` answer (`mcp-status:40`): the host opens [authUrl] itself; the CLI waits for the callback. */
data class McpAuthStart(
    val authUrl: String?,
    val requiresUserAction: Boolean? = null,
    val callbackExpected: Boolean? = null,
    val redirectScheme: String? = null,
    val state: String? = null,
    val callbackPort: Int? = null,
)

/** An `mcp_set_servers` answer (`mcp-status:42`, `:44`); the value type of [errors] is not recorded. */
data class McpSetServersResponse(val added: List<String>, val removed: List<String>, val errors: Map<String, JsonElement>)

/** The MCP server a `can_use_tool` request's tool belongs to (`mcp-tool-call:35`). */
data class McpServerRef(val name: String, val source: String?)

/** One entry of an assistant line's `tool_use_meta` (`mcp-tool-call:34`): how the CLI names an MCP tool call. */
data class ToolUseMeta(val id: String, val displayName: String?, val serverDisplayName: String?, val toolName: String?)

/**
 * The CLI answered a prompt itself, without the model (`mcp-slash:5`, `:17`): `local_command_run` gives [command] and [args],
 * `local_command_outcome` gives [outcomeKind], and [stdout] is `local_command_source` with one `<local-command-stdout>` or
 * `<local-command-stderr>` wrapper removed.
 */
data class LocalCommand(val command: String?, val args: String?, val outcomeKind: String?, val stdout: String?)

/** One entry of the initialize answer's `commands` (`mcp-slash:1`); project commands omit `builtin` and `aliases`. */
data class SlashCommand(
    val name: String,
    val description: String = "",
    val argumentHint: String = "",
    val builtin: Boolean = false,
    val aliases: List<String> = emptyList(),
)

data class InitializeResponse(val commands: List<SlashCommand>)

/** Typed views of [CliMessage.ControlResponse.payload]; each is null when the payload lacks its shape. */
object ControlPayloads {
    private val AUTH_KEYS: Set<String> = setOf("authUrl", "requiresUserAction", "callbackExpected", "redirectScheme", "state", "callbackPort")

    /** Non-null only when the payload has an `mcpServers` array. */
    fun mcpStatus(payload: JsonObject?): McpStatusResponse? {
        val entries = payload?.array("mcpServers") ?: return null
        return McpStatusResponse(entries.mapNotNull { (it as? JsonObject)?.let(::serverInfo) })
    }

    /** Null for a null payload or one with none of the answer's keys (a bare success). */
    fun mcpAuthenticate(payload: JsonObject?): McpAuthStart? {
        if (payload == null || payload.keys.none { it in AUTH_KEYS }) return null
        return McpAuthStart(
            authUrl = payload.string("authUrl"),
            requiresUserAction = payload.boolean("requiresUserAction"),
            callbackExpected = payload.boolean("callbackExpected"),
            redirectScheme = payload.string("redirectScheme"),
            state = payload.string("state"),
            callbackPort = payload.int("callbackPort"),
        )
    }

    /** Null unless the payload has an `added` or a `removed` array; a missing one reads as empty. */
    fun mcpSetServers(payload: JsonObject?): McpSetServersResponse? {
        if (payload == null || (payload.array("added") == null && payload.array("removed") == null)) return null
        return McpSetServersResponse(payload.strings("added"), payload.strings("removed"), payload.obj("errors").orEmpty())
    }

    /** Non-null only when the payload has a `commands` array. */
    fun initialize(payload: JsonObject?): InitializeResponse? {
        val entries = payload?.array("commands") ?: return null
        return InitializeResponse(entries.mapNotNull { (it as? JsonObject)?.let(::slashCommand) })
    }

    /** The context window by category (`get_context_usage`); non-null only when the payload has a `categories` array. */
    fun contextUsage(payload: JsonObject?): ContextBreakdown? = contextBreakdown(payload)

    /** This session's cost and the machine's usage history (`get_usage`); non-null for any object payload. */
    fun usage(payload: JsonObject?): UsageAnswer? = usageAnswer(payload)

    /** [url] when it parses as an absolute `http` or `https` URL (any case) with a host, else null: never a `file:` or custom scheme. */
    fun browsableUrl(url: String?): String? {
        val uri = url?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        return url.takeIf { (scheme == "http" || scheme == "https") && uri.host != null }
    }

    private fun serverInfo(o: JsonObject): McpServerInfo? {
        val name = o.string("name") ?: return null
        val status = o.string("status")
        return McpServerInfo(
            name = name,
            status = McpStatus.fromWire(status),
            statusText = status.orEmpty(),
            error = o.string("error"),
            source = o.string("source"),
            scope = o.string("scope"),
            config = o.obj("config")?.let { McpConfig(it.string("type"), it.string("command"), it.strings("args"), it.string("url")) },
            serverInfo = o.obj("serverInfo")?.let { McpPeer(it.string("name"), it.string("version")) },
            tools = o.array("tools")?.mapNotNull { tool -> (tool as? JsonObject)?.string("name")?.let(::McpTool) },
        )
    }

    private fun slashCommand(o: JsonObject): SlashCommand? {
        val name = o.string("name") ?: return null
        return SlashCommand(
            name = name,
            description = o.string("description").orEmpty(),
            argumentHint = o.string("argumentHint").orEmpty(),
            builtin = o.boolean("builtin") == true,
            aliases = o.strings("aliases"),
        )
    }
}

private val LOCAL_COMMAND_WRAPPERS: List<Pair<String, String>> =
    listOf("stdout", "stderr").map { "<local-command-$it>" to "</local-command-$it>" }

/** One entry of `init.mcp_servers`; null without a string `name`. */
internal fun mcpServerSummary(o: JsonObject): McpServerSummary? {
    val name = o.string("name") ?: return null
    val status = o.string("status")
    return McpServerSummary(name, McpStatus.fromWire(status), status.orEmpty(), o.string("source"))
}

/** An assistant line's [LocalCommand]: non-null iff `local_command_run`, `local_command_outcome` or `local_command_source` is there. */
internal fun localCommand(o: JsonObject): LocalCommand? {
    val keys = listOf("local_command_run", "local_command_outcome", "local_command_source")
    if (keys.none { o[it] != null && o[it] !is JsonNull }) return null
    val run = o.obj("local_command_run")
    return LocalCommand(
        command = run?.string("command"),
        args = run?.string("args"),
        outcomeKind = o.obj("local_command_outcome")?.string("kind"),
        stdout = o.string("local_command_source")?.let(::unwrapLocalCommandOutput),
    )
}

/** [source] without one `<local-command-stdout>…</local-command-stdout>` or `<local-command-stderr>…</local-command-stderr>` wrapper. */
internal fun unwrapLocalCommandOutput(source: String): String {
    for ((open, close) in LOCAL_COMMAND_WRAPPERS) {
        if (source.startsWith(open) && source.endsWith(close) && source.length >= open.length + close.length) {
            return source.substring(open.length, source.length - close.length)
        }
    }
    return source
}

/** An assistant line's `tool_use_meta`; an entry without a string `id` is skipped. */
internal fun toolUseMeta(o: JsonObject): List<ToolUseMeta> = o.objects("tool_use_meta").mapNotNull { m ->
    m.string("id")?.let { ToolUseMeta(it, m.string("display_name"), m.string("server_display_name"), m.string("tool_name")) }
}

/** A `can_use_tool` request's `mcp_server`; null without a string `name`. */
internal fun mcpServerRef(r: JsonObject): McpServerRef? =
    r.obj("mcp_server")?.let { s -> s.string("name")?.let { McpServerRef(it, s.string("source")) } }
