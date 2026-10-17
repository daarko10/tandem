package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.NewSessionOptions
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.PopupContainer
import org.jetbrains.jewel.ui.component.Text

/** The effort picker's first entry: the CLI's default effort, picked by passing none. */
private const val DEFAULT_EFFORT_LABEL: String = "Default effort"

/** The efforts the popup offers after the default, as `--effort` takes them. */
private val EFFORT_LEVELS: List<String> = listOf("low", "medium", "high", "max")

/**
 * What [NewSessionPopup] offers and where its picks go: [models] lists the models the CLI offers, as far as an open session has been
 * told them (none until one has), and [workspace] holds the project's last picks, which prefill the popup and which every pick
 * replaces.
 */
internal class NewSessionChoices(val workspace: TandemWorkspaceState, val models: () -> List<ModelOption>)

/**
 * The popup that starts a new session, below the button that opened it: a model picker ("Default model", then the models the CLI
 * offers, from [choices]), an effort picker ("Default effort", low, medium, high, max) and a permission mode picker (Default, Accept
 * edits, Plan), prefilled with the project's last picks, and "Create", focused, which starts a session with what they show
 * ([onCreate]): no model for "Default model", no effort for "Default effort", and no permission mode until one has been picked for
 * the project. Every pick becomes the project's last one at once. Escape or a click outside closes it ([onDismiss]).
 */
@Composable
internal fun NewSessionPopup(choices: NewSessionChoices, onCreate: (NewSessionOptions) -> Unit, onDismiss: () -> Unit) {
    PopupContainer(onDismissRequest = onDismiss, horizontalAlignment = Alignment.Start) {
        val last = choices.workspace.state
        val models = remember { choices.models() }
        var picks by remember { mutableStateOf(lastPicks(last.lastModel, last.lastEffort, last.lastPermissionMode)) }
        val focus = remember { FocusRequester() }
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelPicker(models, picks.model, active = null) { model ->
                picks = picks.copy(model = model)
                last.lastModel = model
            }
            EffortPicker(picks.effort) { effort ->
                picks = picks.copy(effort = effort)
                last.lastEffort = effort
            }
            PermissionModePicker(picks.permissionMode) { mode ->
                picks = picks.copy(permissionMode = mode)
                last.lastPermissionMode = mode.wire
            }
            DefaultButton(onClick = { onCreate(picks) }, modifier = Modifier.focusRequester(focus)) { Text("Create") }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

/**
 * A button that opens [NewSessionPopup] below itself: [button] is given the function that opens it. "Create" closes the popup and
 * passes the picks to [onCreate]. The sidebar's "New session" button and the header's "+" are such buttons.
 */
@Composable
internal fun NewSessionLauncher(
    choices: NewSessionChoices,
    onCreate: (NewSessionOptions) -> Unit,
    button: @Composable (open: () -> Unit) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    Box {
        button { shown = true }
        if (shown) {
            NewSessionPopup(
                choices,
                onCreate = { options ->
                    shown = false
                    onCreate(options)
                },
                onDismiss = { shown = false },
            )
        }
    }
}

/** The effort picker: "Default effort", then low, medium, high and max ([effortEntries]). It shows [effort], the one picked. */
@Composable
private fun EffortPicker(effort: String?, onPick: (String?) -> Unit) {
    val entries = remember(effort) { effortEntries(effort) }
    Picker(entries, effort, onPick = onPick)
}

/** The effort picker's entries: "Default effort" (null), then [EFFORT_LEVELS]; an [effort] that is none of them is an entry of its own. */
internal fun effortEntries(effort: String?): List<PickerEntry<String?>> =
    (listOf(PickerEntry<String?>(DEFAULT_EFFORT_LABEL, null)) + EFFORT_LEVELS.map { PickerEntry(it, it) }).withEntryFor(effort)

/**
 * The picks the popup starts with: the project's last ones, as the workspace state stores them (the permission mode by its wire
 * name). A permission mode the picker does not offer, or a name it does not know, is none, which the picker shows as Default.
 */
internal fun lastPicks(model: String?, effort: String?, permissionMode: String?): NewSessionOptions {
    val mode = PermissionMode.fromWire(permissionMode)?.takeIf { offered -> PERMISSION_MODE_ENTRIES.any { it.value == offered } }
    return NewSessionOptions(model, effort, mode)
}
