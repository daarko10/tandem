package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ModelOption
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** The `value` of the CLI's own entry for its default model in an `initialize` model list. */
private const val DEFAULT_ENTRY: String = "default"

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
        defaultModel = state.models.firstOrNull { it.value == DEFAULT_ENTRY }?.resolvedModel,
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
    val choices = models.filter { it.value != DEFAULT_ENTRY }
    val picked = requested?.takeIf { it != DEFAULT_ENTRY }
    fun requestedOrDefault(): ModelOption? = if (picked != null) {
        choices.firstOrNull { it.value == picked }
    } else {
        val resolved = models.firstOrNull { it.value == DEFAULT_ENTRY }?.resolvedModel ?: return null
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
