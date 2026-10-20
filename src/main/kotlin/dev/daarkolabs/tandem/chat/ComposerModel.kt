package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ModelOption
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * What the composer and its pickers show (spec 4.15), taken from the state so that it changes only when one of its own fields does,
 * not on every streamed delta. [cardOpen]: a request waits for its answer.
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
)

/** The composer's model of [state]. */
fun composerModel(state: ChatState): ComposerModel = ComposerModel(
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
)
