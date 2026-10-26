package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.ModelTokens
import dev.daarkolabs.tandem.protocol.SessionUsage
import dev.daarkolabs.tandem.protocol.UsageHistory
import dev.daarkolabs.tandem.protocol.UsageShare
import dev.daarkolabs.tandem.protocol.UsageTip
import dev.daarkolabs.tandem.protocol.UsageWindow
import dev.daarkolabs.tandem.session.McpError
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.UsageCard
import dev.daarkolabs.tandem.session.UsagePartState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

// The /usage card (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 3.2, 4.3, 16): pure, built off the EDT. Every number is
// the CLI's own (`get_usage`, the notice's text); names and texts are data, never Markdown.

/** A label and its value, such as `Est. cost` `$0.15`. */
data class UsageFact(val label: String, val value: String)

/** One model of the session: its [label], [cost], token [rows] and its share of the session's cost ([costShare], [costShareText]). */
data class ModelBlock(
    val label: String,
    val cost: String?,
    val rows: ImmutableList<UsageFact>,
    val costShare: Float? = null,
    val costShareText: String? = null,
)

/** One behaviour line: [pct] and its [label]; [known]: Tandem's wording, else the CLI's recommendation title. */
data class BehaviorLine(val pct: String, val label: String, val known: Boolean)

/** An agent, skill, plugin or MCP server ([kind]) and its share of the window. */
data class ShareRow(val name: String, val kind: String, val fraction: Float, val pct: String)

/** One window of `What's using your limits?`: its [caveat], [behaviors], [shares] and every tip of the CLI, in its order. */
data class UsageWindowModel(
    val caveat: String,
    val behaviors: ImmutableList<BehaviorLine>,
    val shares: ImmutableList<ShareRow>,
    val tips: ImmutableList<UsageTip>,
)

/** A section of the card: loading, shown, failed (with `Retry` when [Error.retry]) or a [Note] in its place. */
sealed interface UsageSection<out T> {
    data object Loading : UsageSection<Nothing>

    data class Shown<T>(val value: T) : UsageSection<T>

    data class Error(val text: String, val retry: Boolean) : UsageSection<Nothing>

    data class Note(val text: String) : UsageSection<Nothing>
}

/**
 * The card: this session's [facts], the CLI's [promptCache] line, the [models] (empty: no `Breakdown`), the two windows of the
 * [history] (a null window: [WINDOW_EMPTY]), and the notice's own text ([cliText]), open at first when [cliTextOpen].
 */
data class UsageCardModel(
    val facts: UsageSection<ImmutableList<UsageFact>>,
    val promptCache: String?,
    val models: ImmutableList<ModelBlock>,
    val history: UsageSection<Pair<UsageWindowModel?, UsageWindowModel?>>,
    val cliText: String,
    val cliTextOpen: Boolean,
)

const val USAGE_LOADING: String = "Loading usage…"
const val HISTORY_LOADING: String = "Reading this machine's sessions…"
const val WINDOW_EMPTY: String = "Nothing recorded for this window."

/** The tips shown before `Show <n> more` (spec 16). */
const val TIPS_SHOWN: Int = 3

private const val ENDED: String = "The session has ended."
private const val NO_HISTORY: String = "The CLI did not report what is using your limits."
private const val CAVEAT_TAIL: String = "approximate, overlapping measures · this machine only, excludes claude.ai"
private const val PROMPT_CACHE: String = "Prompt cache (main):"
private const val USAGE_BY_MODEL: String = "Usage by model:"

/** The CLI's own lines of its `/usage` text that the card draws or leaves out on purpose (spec 3.2), by their start. */
private val DRAWN_LINES: List<String> = listOf(
    "Total cost:",
    "Total duration (API):",
    "Total duration (wall):",
    "Total code changes:",
    USAGE_BY_MODEL,
    PROMPT_CACHE,
)

/** Tandem's words for the behaviours it knows (spec 3.2); any other key takes the CLI's recommendation title, else is hidden. */
private val BEHAVIOR_LABELS: Map<String, String> = mapOf(
    "subagent_heavy" to "came from subagent-heavy sessions",
    "long_context" to "came from sessions with a long context",
    "large_tool_results" to "came from sessions with large tool results",
    "max_effort" to "ran at max effort",
)

/**
 * The card of [card] for the usage notice whose text is [noticeText] (spec 3.2, 16): the session's facts and models (the [activeModel]
 * first, labelled as the footer labels [models]' entries), the two windows of the history, and the CLI's own text.
 */
fun usageCard(card: UsageCard, noticeText: String, models: List<ModelOption>, activeModel: String?): UsageCardModel {
    val session = card.session
    val usage = (session as? UsagePartState.Loaded)?.value
    return UsageCardModel(
        facts = when (session) {
            UsagePartState.Loading -> UsageSection.Loading
            is UsagePartState.Loaded -> UsageSection.Shown(facts(session.value))
            is UsagePartState.Failed -> failure(session.error, "Could not load usage: ")
        },
        promptCache = promptCacheLine(noticeText),
        models = usage?.let { modelBlocks(it, models, activeModel) }.orEmpty().toImmutableList(),
        history = when (val history = card.history) {
            UsagePartState.Loading -> UsageSection.Loading
            is UsagePartState.Loaded -> history.value?.let(::windows) ?: UsageSection.Note(NO_HISTORY)
            is UsagePartState.Failed -> failure(history.error, "Could not read the usage history: ")
        },
        cliText = noticeText,
        cliTextOpen = session is UsagePartState.Failed || undrawnCliLines(noticeText),
    )
}

/** `$0.15`: two decimals, `<$0.01` above zero and under half a cent, `$0.00` at zero, below it or for no number. */
fun formatCost(usd: Double): String = when {
    usd.isNaN() || usd <= 0.0 -> "$0.00"
    usd < 0.005 -> "<$0.01"
    else -> String.format(Locale.ROOT, "$%.2f", usd)
}

/** The rest of the first line of the CLI's [text] that starts with `Prompt cache (main):`, trimmed; null without one. */
fun promptCacheLine(text: String): String? =
    text.lineSequence().firstOrNull { it.startsWith(PROMPT_CACHE) }?.removePrefix(PROMPT_CACHE)?.trim()

/**
 * Whether the CLI's [text] has a non-blank line the card neither draws nor leaves out on purpose (spec 3.2): one that does not start,
 * after its leading spaces, with one of the CLI's own labels, and is not an indented model line in the block `Usage by model:` opens.
 */
fun undrawnCliLines(text: String): Boolean {
    var inModels = false
    for (line in text.lineSequence()) {
        if (line.isBlank()) continue
        val indented = line.first().isWhitespace()
        if (indented && inModels) continue
        val trimmed = line.trimStart()
        if (!indented) inModels = trimmed.startsWith(USAGE_BY_MODEL)
        if (DRAWN_LINES.none { trimmed.startsWith(it) }) return true
    }
    return false
}

/**
 * A model's share of the session's cost (spec 16) as a fraction and its text: `<1% of cost` above 0 and under 0.5 %, `>99% of cost`
 * from 99.5 %, else the whole percent; null without both costs or with a total of 0.
 */
fun costShare(costUsd: Double?, totalUsd: Double?): Pair<Float, String>? {
    if (costUsd == null || totalUsd == null || costUsd.isNaN() || totalUsd.isNaN() || totalUsd <= 0.0) return null
    val share = (costUsd / totalUsd).coerceIn(0.0, 1.0)
    val text = when {
        share > 0.0 && share < 0.005 -> "<1% of cost"
        share >= 0.995 -> ">99% of cost"
        else -> "${Math.round(100 * share)}% of cost"
    }
    return share.toFloat() to text
}

/** `Tip` for one, else `Tips`. */
fun tipsTitle(count: Int): String = if (count == 1) "Tip" else "Tips"

/** The toggle under the tips: `Show <n> more` while closed and `Show fewer` while open, past [TIPS_SHOWN]; else none. */
fun tipsToggle(count: Int, open: Boolean): String? = when {
    count <= TIPS_SHOWN -> null
    open -> "Show fewer"
    else -> "Show ${count - TIPS_SHOWN} more"
}

private fun <T> failure(error: McpError, rejected: String): UsageSection<T> = when (error) {
    is McpError.Rejected -> UsageSection.Error(rejected + error.message, retry = true)
    is McpError.TimedOut -> UsageSection.Error("The CLI did not answer within ${error.seconds} s.", retry = true)
    McpError.Ended, McpError.NotRunning -> UsageSection.Error(ENDED, retry = false)
}

private fun facts(session: SessionUsage): ImmutableList<UsageFact> = listOfNotNull(
    session.totalCostUsd?.let { UsageFact("Est. cost", formatCost(it)) },
    session.apiDurationMs?.let { UsageFact("API", formatElapsed(it)) },
    session.wallDurationMs?.let { UsageFact("Wall", formatElapsed(it)) },
).toImmutableList()

private fun modelBlocks(session: SessionUsage, models: List<ModelOption>, activeModel: String?): List<ModelBlock> {
    val ordered = session.models.filter { it.first == activeModel } + session.models.filter { it.first != activeModel }
    return ordered.map { (id, tokens) ->
        val share = costShare(tokens.costUsd, session.totalCostUsd)
        ModelBlock(modelDisplayName(id, models), tokens.costUsd?.let(::formatCost), tokenRows(tokens), share?.first, share?.second)
    }
}

private fun tokenRows(tokens: ModelTokens): ImmutableList<UsageFact> = listOfNotNull(
    tokens.input?.let { UsageFact("Input", formatTokens(it)) },
    tokens.output?.let { UsageFact("Output", formatTokens(it)) },
    tokens.cacheRead?.let { UsageFact("Cache read", formatTokens(it)) },
    tokens.cacheWrite?.let { UsageFact("Cache write", formatTokens(it)) },
    tokens.webSearches?.takeIf { it > 0 }?.let { UsageFact("Web searches", it.toString()) },
).toImmutableList()

private fun windows(history: UsageHistory): UsageSection<Pair<UsageWindowModel?, UsageWindowModel?>> =
    UsageSection.Shown(history.day?.let { window(it, "Last 24 hours") } to history.week?.let { window(it, "Last 7 days") })

/** The model of [window] under [label]; null when it has no behaviour line, no share and no tip. */
private fun window(window: UsageWindow, label: String): UsageWindowModel? {
    val behaviors = window.behaviors.sortedByDescending { it.pct }.mapNotNull { behavior ->
        BEHAVIOR_LABELS[behavior.key]?.let { return@mapNotNull BehaviorLine("${behavior.pct}%", it, known = true) }
        window.tips.firstOrNull { it.key == behavior.key }?.let { BehaviorLine("${behavior.pct}%", it.title, known = false) }
    }
    val shares = (
        window.agents.map { it to "Subagent" } + window.skills.map { it to "Skill" } +
            window.plugins.map { it to "Plugin" } + window.mcpServers.map { it to "MCP" }
        ).sortedByDescending { it.first.pct }.map { (share: UsageShare, kind) -> ShareRow(share.name, kind, share.pct / 100f, "${share.pct}%") }
    if (behaviors.isEmpty() && shares.isEmpty() && window.tips.isEmpty()) return null
    val caveat = listOfNotNull(
        label,
        window.sessionCount?.let { "$it sessions" },
        window.requestCount?.let { "${formatTokens(it.toLong())} requests" },
        CAVEAT_TAIL,
    ).joinToString(" · ")
    return UsageWindowModel(caveat, behaviors.toImmutableList(), shares.toImmutableList(), window.tips.toImmutableList())
}
