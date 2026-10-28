package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

// The `get_usage` answer (parity-usage:16, :17; docs/superpowers/specs/2026-10-22-desktop-parity-design.md 4.1, 16.3).
// Names and texts are data, never Markdown. A `pct` is accepted as a JSON number in 0..100 (rounded), else its entry is skipped.
// Not decoded: subscription_type, rate_limits_available, rate_limits, fix, total_lines_*, the model entries' other keys.

/** One model's entry of `session.model_usage`. */
data class ModelTokens(
    val input: Long?,
    val output: Long?,
    val cacheRead: Long?,
    val cacheWrite: Long?,
    val costUsd: Double?,
    val webSearches: Long? = null,
)

/** `session`: this session's cost and times; [models] in the CLI's order of `model_usage`. */
data class SessionUsage(val totalCostUsd: Double?, val apiDurationMs: Long?, val wallDurationMs: Long?, val models: List<Pair<String, ModelTokens>>)

/** An agent, skill, plugin or MCP server and its share of the window's usage, in percent. */
data class UsageShare(val name: String, val pct: Int)

data class UsageBehavior(val key: String, val pct: Int)

/** One entry of `recommendations`. */
data class UsageTip(val key: String, val title: String, val detail: String?)

/** `behaviors.day` or `behaviors.week`. */
data class UsageWindow(
    val sessionCount: Int?,
    val requestCount: Int?,
    val behaviors: List<UsageBehavior>,
    val tips: List<UsageTip>,
    val agents: List<UsageShare>,
    val skills: List<UsageShare>,
    val plugins: List<UsageShare>,
    val mcpServers: List<UsageShare>,
)

data class UsageHistory(val day: UsageWindow?, val week: UsageWindow?)

/** [session] is null without a `session` object, [history] null when `behaviors` is null, absent or not an object. */
data class UsageAnswer(val session: SessionUsage?, val history: UsageHistory?)

/** Null only for a null payload. */
internal fun usageAnswer(payload: JsonObject?): UsageAnswer? {
    if (payload == null) return null
    val behaviors = payload.obj("behaviors")
    return UsageAnswer(
        session = payload.obj("session")?.let(::sessionUsage),
        history = behaviors?.let { UsageHistory(it.obj("day")?.let(::usageWindow), it.obj("week")?.let(::usageWindow)) },
    )
}

private fun sessionUsage(o: JsonObject): SessionUsage = SessionUsage(
    totalCostUsd = o.double("total_cost_usd"),
    apiDurationMs = o.long("total_api_duration_ms"),
    wallDurationMs = o.long("total_duration_ms"),
    models = o.obj("model_usage").orEmpty().mapNotNull { (model, entry) ->
        (entry as? JsonObject)?.let { m ->
            model to ModelTokens(
                input = m.long("inputTokens"),
                output = m.long("outputTokens"),
                cacheRead = m.long("cacheReadInputTokens"),
                cacheWrite = m.long("cacheCreationInputTokens"),
                costUsd = m.double("costUSD"),
                webSearches = m.long("webSearchRequests"),
            )
        }
    },
)

private fun usageWindow(o: JsonObject): UsageWindow = UsageWindow(
    sessionCount = o.int("session_count"),
    requestCount = o.int("request_count"),
    behaviors = o.objects("behaviors").mapNotNull { b ->
        val key = b.string("key") ?: return@mapNotNull null
        b.pct()?.let { UsageBehavior(key, it) }
    },
    tips = o.objects("recommendations").mapNotNull { r ->
        val key = r.string("key") ?: return@mapNotNull null
        r.string("title")?.let { UsageTip(key, it, r.string("detail")) }
    },
    agents = o.shares("agents"),
    skills = o.shares("skills"),
    plugins = o.shares("plugins"),
    mcpServers = o.shares("mcp_servers"),
)

private fun JsonObject.shares(key: String): List<UsageShare> = objects(key).mapNotNull { s ->
    val name = s.string("name") ?: return@mapNotNull null
    s.pct()?.let { UsageShare(name, it) }
}

/** `pct` when it is a JSON number in 0..100, rounded; else null. */
private fun JsonObject.pct(): Int? = double("pct")?.takeIf { it in 0.0..100.0 }?.roundToInt()
