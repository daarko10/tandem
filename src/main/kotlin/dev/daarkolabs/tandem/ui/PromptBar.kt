package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.ide.TandemWorkspaceState
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionManager
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

/** The model picker's first entry: the CLI's default model, picked by passing none. */
private const val DEFAULT_MODEL_LABEL: String = "Default model"

/** How wide a picker's list is, unless the picker itself is wider: wide enough for an entry such as "Default (recommended)". */
private val PICKER_POPUP_WIDTH: Dp = 220.dp

/** An entry of a picker: the [label] it shows and the [value] it stands for. */
internal data class PickerEntry<out T>(val label: String, val value: T)

/** The permission modes the picker offers, in its order. The CLI has more, which Tandem does not offer yet. */
internal val PERMISSION_MODE_ENTRIES: List<PickerEntry<PermissionMode>> = listOf(
    PickerEntry("Default", PermissionMode.DEFAULT),
    PickerEntry("Accept edits", PermissionMode.ACCEPT_EDITS),
    PickerEntry("Plan", PermissionMode.PLAN),
)

/**
 * The model picker's entries: "Default model" (null: the CLI's default), then the models the CLI offers ([models], by display name),
 * or, while it has not listed them, the model it reports it runs ([active]). A [requested] model that none of these stands for is an
 * entry of its own, so that the picker always shows what was picked.
 */
internal fun modelEntries(models: List<ModelOption>, requested: String?, active: String?): List<PickerEntry<String?>> {
    val offered = if (models.isNotEmpty()) {
        models.map { PickerEntry(it.displayName, it.value) }
    } else {
        listOfNotNull(active?.let { PickerEntry(it, it) })
    }
    return (listOf(PickerEntry<String?>(DEFAULT_MODEL_LABEL, null)) + offered).withEntryFor(requested)
}

/** These entries, plus [value] as an entry of its own, labelled with itself, when it is not null and none of them stands for it. */
internal fun List<PickerEntry<String?>>.withEntryFor(value: String?): List<PickerEntry<String?>> =
    if (value == null || any { it.value == value }) this else this + PickerEntry(value, value)

/** The position of the entry that stands for [value]; the first entry, the default, when none does. */
internal fun <T> List<PickerEntry<T>>.positionOf(value: T?): Int = indexOfFirst { it.value == value }.coerceAtLeast(0)

/**
 * The bottom of a session's chat: the prompt with Send, or Stop while a turn runs, and below it the session's model and permission
 * mode pickers ([ModelPicker], [PermissionModePicker]) and, when the session has one, the effort it started with, read-only.
 *
 * A pick goes to [manager], which switches a running process at once and starts a stopped session with it; it becomes the project's
 * last pick in [workspace] too, which a new session starts from. The pickers show what the session's state says: the CLI confirms a
 * permission mode with its next status or `system/init`. A session whose process ended takes prompts too: the next one starts it
 * again. With [focus] the prompt takes the focus, and then [onFocused] is called.
 */
@Composable
internal fun PromptBar(
    handle: SessionHandle,
    manager: SessionManager,
    workspace: TandemWorkspaceState,
    focus: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val state by handle.state.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        PromptBox(handle, manager, state, focus, onFocused)
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The model picker takes what the others leave, and shortens its entry when that is too little: a tool window is narrow.
            ModelPicker(state.models, state.requestedModel, state.activeModel, Modifier.weight(1f, fill = false)) { model ->
                manager.setModel(handle.id, model)
                workspace.state.lastModel = model
            }
            PermissionModePicker(state.permissionMode) { mode ->
                manager.setPermissionMode(handle.id, mode)
                workspace.state.lastPermissionMode = mode.wire
            }
            state.effort?.let { EffortBadge(it) }
        }
    }
}

/**
 * The prompt and Send, or Stop while a turn runs. A session whose process ended takes prompts too: the next one starts it again.
 * With [focus] the prompt takes the focus, and then [onFocused] is called.
 */
@Composable
private fun PromptBox(handle: SessionHandle, manager: SessionManager, state: ChatState, focus: Boolean, onFocused: () -> Unit) {
    val input = rememberTextFieldState()
    val inputFocus = remember { FocusRequester() }
    LaunchedEffect(focus) {
        if (focus) {
            inputFocus.requestFocus()
            onFocused()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextArea(input, Modifier.weight(1f).heightIn(min = 56.dp, max = 160.dp).focusRequester(inputFocus))
        if (state.busy) {
            OutlinedButton(onClick = { manager.interrupt(handle.id) }) { Text("Stop") }
        } else {
            DefaultButton(
                onClick = {
                    val text = input.text.toString().trim()
                    if (text.isNotEmpty()) {
                        manager.send(handle.id, text)
                        input.clearText()
                    }
                },
            ) { Text("Send") }
        }
    }
}

/**
 * The model picker: "Default model", then the models the CLI offers, or the one it runs while it has not listed them
 * ([modelEntries]). It shows [model], the one picked (null: the CLI's default); picking another one calls [onPick] with its value,
 * null for "Default model".
 */
@Composable
internal fun ModelPicker(
    models: List<ModelOption>,
    model: String?,
    active: String?,
    modifier: Modifier = Modifier,
    onPick: (String?) -> Unit,
) {
    val entries = remember(models, model, active) { modelEntries(models, model, active) }
    Picker(entries, model, modifier, onPick)
}

/** The permission mode picker: Default, Accept edits and Plan. It shows [mode], and Default for none or for a mode it does not offer. */
@Composable
internal fun PermissionModePicker(mode: PermissionMode?, onPick: (PermissionMode) -> Unit) {
    Picker(PERMISSION_MODE_ENTRIES, mode, onPick = onPick)
}

/**
 * A drop-down list of [entries] that shows the one standing for [selected] (the first one when none does); picking another one calls
 * [onPick] with its value. Picking the one shown does nothing.
 */
@Composable
internal fun <T> Picker(entries: List<PickerEntry<T>>, selected: T?, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    val labels = remember(entries) { entries.map { it.label } }
    ListComboBox(
        items = labels,
        selectedIndex = entries.positionOf(selected),
        onSelectedItemChange = { index -> entries.getOrNull(index)?.let { onPick(it.value) } },
        modifier = modifier,
        maxPopupWidth = PICKER_POPUP_WIDTH,
        // By position, since two entries may show the same text.
        itemKeys = { index, _ -> index },
    )
}

/** The effort the session started with. It cannot change while the session runs, so it is shown and not picked. */
@Composable
private fun EffortBadge(effort: String) {
    WithTooltip("Effort is chosen when a session starts.") {
        Text("effort: $effort", color = JewelTheme.globalColors.text.info, maxLines = 1)
    }
}
