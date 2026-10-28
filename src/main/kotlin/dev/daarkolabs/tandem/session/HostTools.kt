package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.ToolReply
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The prefix the CLI gives Tandem's own tools (`mcp__tandem__edit_file`, `mcp__tandem__write_file`). */
const val TANDEM_TOOL_PREFIX: String = "mcp__tandem__"

/** The tool whose absence from a non-empty `init.tools` raises [EDITS_OFF_NOTICE] (the version gate, spec 4.2). */
internal const val TANDEM_EDIT_TOOL: String = "mcp__tandem__edit_file"

/** The version gate's notice (spec 4.2, hard rule 5); `<version>` is the init's `claude_code_version`. */
const val EDITS_OFF_NOTICE: String =
    "Edits are off in this session: claude <version> did not take Tandem's edit tools. Update claude or use the terminal for edits."

/** [EDITS_OFF_NOTICE] for the CLI [version] (`unknown` when the init named none). */
internal fun editsOffNotice(version: String?): String = EDITS_OFF_NOTICE.replace("<version>", version ?: "unknown")

/**
 * One `tools/call` of Tandem's server: the tool's [name] (`edit_file`, `write_file`), its validated [arguments], the model's
 * [toolUseId] (`_meta["claudecode/toolUseId"]`, null when the CLI sent none), the JSON-RPC [requestId] exactly as the CLI sent it
 * (a number or a string) and the [session] it came from.
 */
data class ToolCall(
    val name: String,
    val arguments: JsonObject,
    val toolUseId: String?,
    val requestId: JsonElement,
    val session: SessionId,
)

/**
 * What serves Tandem's tools in the IDE (the project's `TandemEdits`). [ClaudeSession] runs [call] in a child job of its own, off the
 * reader, and sends what it returns; [cancel] says the CLI withdrew the call [requestId] of [session] (`notifications/cancelled`),
 * whose job is cancelled too and whose reply is never sent; [cancelAll] says [session]'s process is over (stopped, closed or ended).
 */
interface HostTools {
    suspend fun call(call: ToolCall): ToolReply

    fun cancel(session: SessionId, requestId: JsonElement)

    fun cancelAll(session: SessionId)
}
