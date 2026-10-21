package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ContextUsage
import dev.daarkolabs.tandem.session.ModelOption
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

/** The `value` of the CLI's own entry for its default model in an `initialize` model list. */
internal const val DEFAULT_MODEL_ENTRY: String = "default"

/**
 * What the composer and its footer show (spec 4.15, polish spec 2.3), taken from the state so that it changes only when one of its own
 * fields does, not on every streamed delta. [cardOpen]: a request waits for its answer. [defaultModel] is the model the CLI's `default`
 * entry resolves to; [effortLevels] and [autoSupported] are those of the shown model ([shownModel]); [modelSwitchPending] mirrors
 * [ChatState.modelSwitchPending].
 */
data class ComposerModel(
    val busy: Boolean,
    val ended: Boolean,
    val cardOpen: Boolean,
    val models: ImmutableList<ModelOption>,
    val requestedModel: String?,
    val activeModel: String?,
    val modelWarning: String?,
    val permissionMode: PermissionMode?,
    val effort: String?,
    val claudeCodeVersion: String?,
    val defaultModel: String? = null,
    val effortLevels: ImmutableList<String> = persistentListOf(),
    val autoSupported: Boolean = false,
    val modelSwitchPending: Boolean = false,
)

/** The composer's model of [state]. */
fun composerModel(state: ChatState): ComposerModel {
    val shown = shownModel(state.models, state.requestedModel, state.activeModel, state.modelSwitchPending)
    return ComposerModel(
        busy = state.busy,
        ended = state.ended,
        cardOpen = state.pendingPermissions.isNotEmpty(),
        models = state.models.toImmutableList(),
        requestedModel = state.requestedModel,
        activeModel = state.activeModel,
        modelWarning = state.modelWarning,
        permissionMode = state.permissionMode,
        effort = state.effort,
        claudeCodeVersion = state.claudeCodeVersion,
        defaultModel = state.models.firstOrNull { it.value == DEFAULT_MODEL_ENTRY }?.resolvedModel,
        effortLevels = shown?.effortLevels.orEmpty().toImmutableList(),
        autoSupported = shown?.supportsAutoMode == true,
        modelSwitchPending = state.modelSwitchPending,
    )
}

/**
 * The entry of [models] that the footer shows, checks and takes the effort levels and `auto` support from (spec 2.3, `<Model>`):
 * 1. while a pick waits for its `system/init` ([switchPending]), the [requested] entry, by value; with none requested (the
 *    Default-badged pick, sent as `set_model null`), the entry the `default` entry resolves to;
 * 2. else, once the CLI has reported the [active] model, the entry other than `default` that resolves to it (its `resolvedModel`, or its
 *    value without one): of several, the requested one, else the first;
 * 3. before that, the requested entry, else the entry the `default` entry resolves to.
 * Null when no entry stands for it (4: the footer then shows the wire id). A [requested] `default` (the new-session popup lists the
 * CLI's own `default` entry) counts as no request: the entry it resolves to stands for it.
 */
internal fun shownModel(models: List<ModelOption>, requested: String?, active: String?, switchPending: Boolean): ModelOption? {
    val choices = models.filter { it.value != DEFAULT_MODEL_ENTRY }
    val picked = requested?.takeIf { it != DEFAULT_MODEL_ENTRY }
    fun requestedOrDefault(): ModelOption? = if (picked != null) {
        choices.firstOrNull { it.value == picked }
    } else {
        val resolved = models.firstOrNull { it.value == DEFAULT_MODEL_ENTRY }?.resolvedModel ?: return null
        choices.firstOrNull { it.resolvesTo() == resolved }
    }
    return when {
        switchPending || active == null -> requestedOrDefault()
        else -> {
            val running = choices.filter { it.resolvesTo() == active }
            running.firstOrNull { it.value == picked } ?: running.firstOrNull()
        }
    }
}

/** The model id this entry stands for. */
private fun ModelOption.resolvesTo(): String = resolvedModel ?: value

/**
 * The context ring's text and arc (polish spec 2.3): `<used> / <window> (<pct>%)`, as the tooltip and the popover show it, and the
 * share of the window in use. Both are null while the tokens or the window are unknown, and the ring is then not drawn.
 */
object ContextFormat {
    /** `75.6k / 1M (8%)`: the tokens by [formatTokens], the window by [formatWindow], the share rounded to a whole percent. */
    fun value(usage: ContextUsage): String? {
        val tokens = usage.tokens ?: return null
        val window = usage.window ?: return null
        val pct = if (window == 0L) 0L else Math.round(100.0 * tokens / window)
        return "${formatTokens(tokens)} / ${formatWindow(window)} ($pct%)"
    }

    /** The share of the window in use, from 0 to 1 (a count past the window fills the ring); null without both parts or with no window. */
    fun fraction(usage: ContextUsage): Float? {
        val tokens = usage.tokens ?: return null
        val window = usage.window?.takeIf { it != 0L } ?: return null
        return (tokens.toFloat() / window).coerceIn(0f, 1f)
    }

    /** `200k` under a million; `1M`, `1.5M` from one, with one decimal only when it is not zero (where [formatTokens] gives `1.0m`). */
    fun formatWindow(window: Long): String =
        if (window < 1_000_000) "${window / 1_000}k" else String.format(Locale.ROOT, "%.1f", window / 1_000_000.0).removeSuffix(".0") + "M"
}
