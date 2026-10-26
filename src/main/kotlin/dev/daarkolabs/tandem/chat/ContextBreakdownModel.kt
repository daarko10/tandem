package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.ContextBreakdown
import dev.daarkolabs.tandem.protocol.ContextCategory
import dev.daarkolabs.tandem.session.ContextUsage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

// The context ring's popover (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 3.1, 4.3): pure, built off the EDT.

/** A category's colour, by its id; `ui/` maps it to Tandem's colours. [FREE] is the track. */
enum class ContextTone { MESSAGES, MCP_TOOLS, MEMORY_FILES, SYSTEM_PROMPT, NEUTRAL, FREE }

/** One category: the CLI's [name], its [tokens], its [share] of the window (`—` for a deferred one) and its accessibility [description]. */
data class CategoryRow(val name: String, val tokens: String, val share: String, val tone: ContextTone, val description: String)

/** One segment of the bar: a used category's share of the window, from 0 to 1. */
data class BarSegment(val tone: ContextTone, val fraction: Float)

/** One item of a group; [dim] for an MCP tool that is not loaded. */
data class GroupItem(val label: String, val tokens: String, val dim: Boolean, val tooltip: String?)

/** A group (`mcp_tools`, `mcp_instructions`, `memory_files`, `skills`, `agents`): [count] items in all, the first ones in [items], [more] not drawn. */
data class BreakdownGroup(val key: String, val title: String, val total: String, val count: Int, val items: ImmutableList<GroupItem>, val more: Int)

/** The popover: the header's [value], the bar's [segments], the category [rows] and the [groups] with items. */
data class ContextPopoverModel(
    val value: String?,
    val segments: ImmutableList<BarSegment>,
    val rows: ImmutableList<CategoryRow>,
    val groups: ImmutableList<BreakdownGroup>,
)

/** At most this many items are drawn per group, then `and N more`. */
const val GROUP_ITEM_CAP: Int = 100

/**
 * The popover's status: a [headerSuffix] (` · Updating…`), a [line] under the header (loading, an error, a stopped session), whether
 * that line is an [error] (the view puts `Retry` beside it), and whether the rows of an earlier answer are drawn dimmed ([dimRows]).
 */
data class PopoverStatus(val headerSuffix: String?, val line: String?, val error: Boolean, val dimRows: Boolean)

const val BREAKDOWN_LOADING: String = "Loading the breakdown…"
const val BREAKDOWN_UPDATING: String = " · Updating…"
const val BREAKDOWN_STOPPED: String = "The session is not running. The breakdown updates with the next prompt."

/**
 * The status line of spec 3.1: a [stopped] session's line first, then a failure's text ([failedText], with `Retry`), then, while a
 * request runs ([loading]), ` · Updating…` beside the header when rows are shown ([hasRows]), else `Loading the breakdown…`.
 */
fun popoverStatus(loading: Boolean, failedText: String?, stopped: Boolean, hasRows: Boolean): PopoverStatus = when {
    stopped -> PopoverStatus(null, BREAKDOWN_STOPPED, error = false, dimRows = hasRows)
    failedText != null -> PopoverStatus(null, failedText, error = true, dimRows = hasRows)
    loading && hasRows -> PopoverStatus(BREAKDOWN_UPDATING, null, error = false, dimRows = false)
    loading -> PopoverStatus(null, BREAKDOWN_LOADING, error = false, dimRows = false)
    else -> PopoverStatus(null, null, error = false, dimRows = false)
}

private const val NO_SHARE: String = "—"
private const val MCP_TOOL_PREFIX: String = "mcp__"

/**
 * The popover of [breakdown] (spec 3.1), else of the ring alone ([ring]): categories with tokens, used ones by tokens (largest first),
 * then the buffer, free space, deferred ones by tokens, any other kind and a missing kind in the CLI's order; tones by the category's
 * id, never its name. Memory-file paths are shown relative to [projectRoot] or with [home] as `~` ([displayPath]).
 */
fun contextPopover(breakdown: ContextBreakdown?, ring: ContextUsage?, home: String?, projectRoot: String?): ContextPopoverModel {
    val max = breakdown?.maxTokens
    val window = max?.takeIf { it > 0 }
    val categories = breakdown?.categories.orEmpty().filter { it.tokens > 0 }
    val buckets = categories.groupBy(::bucket)
    val ordered = (0..BUCKET_MISSING).flatMap { b ->
        val rows = buckets[b].orEmpty()
        if (b == BUCKET_USED || b == BUCKET_DEFERRED) rows.sortedByDescending { it.tokens } else rows
    }
    val rows = ordered.map { category ->
        val deferred = bucket(category) == BUCKET_DEFERRED
        val tokens = formatTokens(category.tokens)
        val share = if (deferred) NO_SHARE else ContextFormat.percentOf(category.tokens, max ?: 0)
        val description = if (deferred) "${category.name}, $tokens tokens, deferred" else "${category.name}, $tokens tokens, $share"
        CategoryRow(category.name, tokens, share, toneOf(category.id), description)
    }
    val segments = if (window == null) {
        emptyList()
    } else {
        ordered.filter { bucket(it) == BUCKET_USED }.map { BarSegment(toneOf(it.id), (it.tokens.toDouble() / window).toFloat()) }
    }
    return ContextPopoverModel(
        value = headerValue(breakdown, ring),
        segments = segments.toImmutableList(),
        rows = rows.toImmutableList(),
        groups = breakdown?.let { groups(it, home, projectRoot) }.orEmpty().toImmutableList(),
    )
}

/**
 * [path] as the popover shows it (spec 3.1): relative to [projectRoot] when it is under it, else with [home] as `~`; anything else as it
 * is. String prefixes only, a separator being `/` or `\`, so a path that only shares a prefix with either stays absolute.
 */
fun displayPath(path: String, home: String?, projectRoot: String?): String {
    projectRoot?.let { root -> under(path, root)?.let { return it } }
    if (home != null) {
        if (path == home) return "~"
        under(path, home)?.let { return "~/$it" }
    }
    return path
}

/** The rest of [path] after [base] and one separator; null unless [path] is under [base]. */
private fun under(path: String, base: String): String? {
    if (base.isEmpty() || path.length <= base.length + 1 || !path.startsWith(base)) return null
    val separator = path[base.length]
    return if (separator == '/' || separator == '\\') path.substring(base.length + 1) else null
}

private const val BUCKET_USED = 0
private const val BUCKET_BUFFER = 1
private const val BUCKET_FREE = 2
private const val BUCKET_DEFERRED = 3
private const val BUCKET_OTHER = 4
private const val BUCKET_MISSING = 5

private fun bucket(category: ContextCategory): Int = when {
    category.deferred -> BUCKET_DEFERRED
    category.kind == "used" -> BUCKET_USED
    category.kind == "buffer" -> BUCKET_BUFFER
    category.kind == "free" -> BUCKET_FREE
    category.kind == null -> BUCKET_MISSING
    else -> BUCKET_OTHER
}

private fun toneOf(id: String?): ContextTone = when (id) {
    "messages" -> ContextTone.MESSAGES
    "mcp_tools" -> ContextTone.MCP_TOOLS
    "memory_files" -> ContextTone.MEMORY_FILES
    "system_prompt" -> ContextTone.SYSTEM_PROMPT
    "free_space" -> ContextTone.FREE
    else -> ContextTone.NEUTRAL
}

/** `38.0k / 1M (4%)` from the breakdown (the CLI's own percentage, else computed), else the ring's value, else null. */
private fun headerValue(breakdown: ContextBreakdown?, ring: ContextUsage?): String? {
    val total = breakdown?.totalTokens
    val max = breakdown?.maxTokens
    if (total != null && max != null && max > 0) {
        val pct = breakdown.percentage ?: Math.round(100.0 * total / max).toInt()
        return "${formatTokens(total)} / ${ContextFormat.formatWindow(max)} ($pct%)"
    }
    return ring?.let(ContextFormat::value)
}

private fun groups(breakdown: ContextBreakdown, home: String?, projectRoot: String?): List<BreakdownGroup> = listOfNotNull(
    group("mcp_tools", "MCP tools", breakdown.mcpTools.map { tool ->
        val label = tool.serverName?.let { server -> "$server · ${tool.name.removePrefix("$MCP_TOOL_PREFIX${server}__")}" } ?: tool.name
        tool.tokens to GroupItem(label, formatTokens(tool.tokens), dim = tool.loaded == false, tooltip = null)
    }),
    group("mcp_instructions", "MCP server instructions", breakdown.mcpInstructions.map {
        it.tokens to GroupItem(it.serverName, formatTokens(it.tokens), dim = false, tooltip = null)
    }),
    group("memory_files", "Memory files", breakdown.memoryFiles.map { file ->
        val tooltip = file.type?.let { "${file.path} · $it" } ?: file.path
        file.tokens to GroupItem(displayPath(file.path, home, projectRoot), formatTokens(file.tokens), dim = false, tooltip = tooltip)
    }),
    group("skills", "Skills", breakdown.skills.map { it.tokens to GroupItem(it.name, formatTokens(it.tokens), dim = false, tooltip = null) }),
    group("agents", "Custom agents", breakdown.agents.map {
        it.tokens to GroupItem(it.agentType, formatTokens(it.tokens), dim = false, tooltip = it.source)
    }),
)

/** A group of [items] (each with its tokens), the first [GROUP_ITEM_CAP] drawn; null without items. */
private fun group(key: String, title: String, items: List<Pair<Long, GroupItem>>): BreakdownGroup? {
    if (items.isEmpty()) return null
    val shown = items.take(GROUP_ITEM_CAP).map { it.second }
    return BreakdownGroup(key, title, formatTokens(items.sumOf { it.first }), items.size, shown.toImmutableList(), items.size - shown.size)
}
