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
import dev.daarkolabs.tandem.session.OFFERED_MODES
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.PopupContainer
import org.jetbrains.jewel.ui.component.Text

/** The effort picker's first entry: the CLI's default effort, picked by passing none. */
private const val DEFAULT_EFFORT_LABEL: String = "Default effort"

/** The efforts the popup offers after the default, as `--effort` takes them. */
private val EFFORT_LEVELS: List<String> = listOf("low", "medium", "high", "max")

/**
 * What [NewSessionPopup] offers and where its picks go: [models] lists the models the CLI offers, as an open session was told them
 * or, until one is, as a session was told them last ([TandemWorkspaceState.knownModels]), and [workspace] holds the project's last
 * picks, which prefill the popup and which "Create" replaces.
 */
internal class NewSessionChoices(val workspace: TandemWorkspaceState, val models: () -> List<ModelOption>)

/**
 * The popup that starts a new session, below the button that opened it: a model picker ("Default model", then the models the CLI
 * offers, from [choices]), an effort picker ("Default effort", low, medium, high, max) and a permission mode picker ("Default mode",
 * Auto, Manual, Accept edits, Plan), prefilled with the project's last picks, and "Create", focused.
 *
 * "Create" starts a session with what the pickers show ([onCreate]): no model, effort or permission mode for the defaults, so that
 * the CLI picks them, and these picks become the project's last ones. Escape or a click outside closes the popup ([onDismiss]) and
 * keeps the project's last picks as they were.
 */
@Composable
internal fun NewSessionPopup(choices: NewSessionChoices, onCreate: (NewSessionOptions) -> Unit, onDismiss: () -> Unit) {
    PopupContainer(onDismissRequest = onDismiss, horizontalAlignment = Alignment.Start) {
        val last = choices.workspace.state
        val models = remember { choices.models() }
        var picks by remember { mutableStateOf(lastPicks(last.lastModel, last.lastEffort, last.lastPermissionMode)) }
        val focus = remember { FocusRequester() }
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelPicker(models, picks.model) { model -> picks = picks.copy(model = model) }
            EffortPicker(picks.effort) { effort -> picks = picks.copy(effort = effort) }
            PermissionModePicker(picks.permissionMode) { mode -> picks = picks.copy(permissionMode = mode) }
            DefaultButton(
                onClick = {
                    last.lastModel = picks.model
                    last.lastEffort = picks.effort
                    last.lastPermissionMode = picks.permissionMode?.wire
                    onCreate(picks)
                },
                modifier = Modifier.focusRequester(focus),
            ) { Text("Create") }
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
    Picker(entries, effort, "Effort", onPick = onPick)
}

/** The effort picker's entries: "Default effort" (null), then [EFFORT_LEVELS]; an [effort] that is none of them is an entry of its own. */
internal fun effortEntries(effort: String?): List<PickerEntry<String?>> =
    (listOf(PickerEntry<String?>(DEFAULT_EFFORT_LABEL, null)) + EFFORT_LEVELS.map { PickerEntry(it, it) }).withEntryFor(effort) { it }

/**
 * The picks the popup starts with: the project's last ones, as the workspace state stores them (the permission mode by its wire
 * name). A permission mode the popup does not offer, or a name it does not know, is none ("Default mode": no flag); `default` is
 * Manual, passed as `--permission-mode default`.
 */
internal fun lastPicks(model: String?, effort: String?, permissionMode: String?): NewSessionOptions =
    NewSessionOptions(model, effort, PermissionMode.fromWire(permissionMode)?.takeIf { it in OFFERED_MODES })
