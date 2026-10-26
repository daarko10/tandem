package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.JsonObject

// The `get_context_usage` answer (parity-context:2, :17, :18; docs/superpowers/specs/2026-10-22-desktop-parity-design.md 4.1).
// Every field is optional and validated: an entry without its name, or whose tokens are not a non-negative JSON number, is skipped.
// Not decoded: gridRows, color, slashCommands, messageBreakdown, apiUsage, autoCompactThreshold, isAutoCompactEnabled,
// autocompactSource, systemTools, systemPromptSections, deferredBuiltinTools.

/** One row of `categories`. [kind] as sent (`used`, `buffer`, `free`, `deferred`, other text kept); [deferred] also from `isDeferred`. */
data class ContextCategory(val id: String?, val name: String, val tokens: Long, val kind: String?, val deferred: Boolean)

/** One entry of `memoryFiles`: a CLAUDE.md or similar file and its [type] (`Project`, `User`, ...). */
data class MemoryFileUsage(val path: String, val type: String?, val tokens: Long)

/** One entry of `mcpTools`; [loaded] is `isLoaded` (false for a deferred tool). */
data class McpToolUsage(val name: String, val serverName: String?, val tokens: Long, val loaded: Boolean?)

/** One entry of `mcpInstructions`, in the SDK's documented shape (no recording has one). */
data class McpInstructionsUsage(val serverName: String, val tokens: Long)

/** One entry of `agents`, in the SDK's documented shape (no recording has one). */
data class AgentUsage(val agentType: String, val source: String?, val tokens: Long)

/** One entry of `skills.skillFrontmatter`. */
data class SkillUsage(val name: String, val source: String?, val pluginName: String?, val tokens: Long)

data class ContextBreakdown(
    val totalTokens: Long?,
    val maxTokens: Long?,
    val rawMaxTokens: Long?,
    val percentage: Int?,
    val model: String?,
    val categories: List<ContextCategory>,
    val memoryFiles: List<MemoryFileUsage>,
    val mcpTools: List<McpToolUsage>,
    val mcpInstructions: List<McpInstructionsUsage>,
    val agents: List<AgentUsage>,
    val skills: List<SkillUsage>,
)

/** Null unless [payload] has a `categories` array. */
internal fun contextBreakdown(payload: JsonObject?): ContextBreakdown? {
    val categories = payload?.array("categories") ?: return null
    return ContextBreakdown(
        totalTokens = payload.long("totalTokens"),
        maxTokens = payload.long("maxTokens"),
        rawMaxTokens = payload.long("rawMaxTokens"),
        percentage = payload.int("percentage"),
        model = payload.string("model"),
        categories = categories.filterIsInstance<JsonObject>().mapNotNull(::contextCategory),
        memoryFiles = payload.objects("memoryFiles").mapNotNull { o ->
            val path = o.string("path") ?: return@mapNotNull null
            o.tokens()?.let { MemoryFileUsage(path, o.string("type"), it) }
        },
        mcpTools = payload.objects("mcpTools").mapNotNull { o ->
            val name = o.string("name") ?: return@mapNotNull null
            o.tokens()?.let { McpToolUsage(name, o.string("serverName"), it, o.boolean("isLoaded")) }
        },
        mcpInstructions = payload.objects("mcpInstructions").mapNotNull { o ->
            val server = o.string("serverName") ?: return@mapNotNull null
            o.tokens()?.let { McpInstructionsUsage(server, it) }
        },
        agents = payload.objects("agents").mapNotNull { o ->
            val agentType = o.string("agentType") ?: return@mapNotNull null
            o.tokens()?.let { AgentUsage(agentType, o.string("source"), it) }
        },
        skills = payload.obj("skills")?.objects("skillFrontmatter").orEmpty().mapNotNull { o ->
            val name = o.string("name") ?: return@mapNotNull null
            o.tokens()?.let { SkillUsage(name, o.string("source"), o.string("pluginName"), it) }
        },
    )
}

private fun contextCategory(o: JsonObject): ContextCategory? {
    val name = o.string("name") ?: return null
    val tokens = o.tokens() ?: return null
    val kind = o.string("kind")
    return ContextCategory(o.string("id"), name, tokens, kind, deferred = o.boolean("isDeferred") == true || kind == "deferred")
}

/** `tokens` when it is a non-negative whole JSON number, else null. */
private fun JsonObject.tokens(): Long? = long("tokens")?.takeIf { it >= 0 }
