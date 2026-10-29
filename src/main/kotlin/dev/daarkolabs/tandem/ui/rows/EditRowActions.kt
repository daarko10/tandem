package dev.daarkolabs.tandem.ui.rows

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import dev.daarkolabs.tandem.chat.RowAction
import dev.daarkolabs.tandem.chat.ToolCall

/**
 * What a Tandem edit row's `Show diff` calls with the edit's tool use id (IDE-edits spec 3.4): `ChatPane` provides the project's
 * `TandemEdits.showDiff`, which focuses the open diff of that call. Outside a chat pane (previews, tests) it does nothing.
 */
internal val LocalShowDiff: ProvidableCompositionLocal<(toolUseId: String) -> Unit> = staticCompositionLocalOf { {} }

/** The label of the row action that focuses the open diff. */
internal const val SHOW_DIFF_LABEL: String = "Show diff"

/** The test tag of a Tandem edit's row (spec 3.8). */
internal fun editRowTag(toolUseId: String): String = "edit-row-$toolUseId"

/** The test tag of a Tandem edit row's `Show diff` (spec 3.8). */
internal fun showDiffTag(toolUseId: String): String = "edit-show-diff-$toolUseId"

/** The click of [call]'s `Show diff`, which hands its tool use id to [showDiff]; null when the call offers none. */
internal fun showDiffClick(call: ToolCall, showDiff: (toolUseId: String) -> Unit): (() -> Unit)? =
    (call.action as? RowAction.ShowDiff)?.let { action -> { showDiff(action.toolUseId) } }
