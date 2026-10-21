package dev.daarkolabs.tandem.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.Modes
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.session.ModelOption
import dev.daarkolabs.tandem.session.OFFERED_MODES
import org.jetbrains.jewel.foundation.lazy.rememberSelectableLazyListState
import org.jetbrains.jewel.ui.component.ListComboBox

/** The model picker's first entry: the CLI's default model, picked by passing none. */
private const val DEFAULT_MODEL_LABEL: String = "Default model"

/** How wide a picker's list is, unless the picker itself is wider: wide enough for an entry such as "Default (recommended)". */
private val PICKER_POPUP_WIDTH: Dp = 220.dp

/** An entry of a picker: the [label] it shows and the [value] it stands for. */
internal data class PickerEntry<out T>(val label: String, val value: T)

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
    return (listOf(PickerEntry<String?>(DEFAULT_MODEL_LABEL, null)) + offered).withEntryFor(requested) { it }
}

/** The new-session popup's first permission mode entry: no `--permission-mode`, the mode the CLI's own settings give. */
private const val DEFAULT_MODE_LABEL: String = "Default mode"

/**
 * The permission mode picker's entries: "Default mode" (null: no flag), then the modes Tandem offers ([OFFERED_MODES]), in that
 * order, by the footer's labels ([Modes.label]). A [mode] Tandem does not offer (bypassPermissions, dontAsk) is an entry of its own,
 * so that the picker never shows another mode than the one picked.
 */
internal fun permissionModeEntries(mode: PermissionMode?): List<PickerEntry<PermissionMode?>> =
    (listOf(PickerEntry<PermissionMode?>(DEFAULT_MODE_LABEL, null)) + OFFERED_MODES.map { PickerEntry(permissionModeLabel(it), it) })
        .withEntryFor(mode, ::permissionModeLabel)

/** What the permission mode picker calls [mode]: the footer's label ([Modes.label]). */
internal fun permissionModeLabel(mode: PermissionMode): String = Modes.label(mode)

/** These entries, plus [value] as an entry of its own, labelled by [label], when it is not null and none of them stands for it. */
internal fun <T> List<PickerEntry<T>>.withEntryFor(value: T?, label: (T & Any) -> String): List<PickerEntry<T>> =
    if (value == null || any { it.value == value }) this else this + PickerEntry(label(value), value)

/** The position of the entry that stands for [value]; the first entry, the default, when none does. */
internal fun <T> List<PickerEntry<T>>.positionOf(value: T?): Int = indexOfFirst { it.value == value }.coerceAtLeast(0)

/** A picker's list key for an entry: its [Value], or its [Position] where the value cannot tell it apart. Never one for the other. */
internal sealed interface PickerKey {
    data class Value(val value: Any) : PickerKey

    data class Position(val index: Int) : PickerKey
}

/**
 * A key per entry, for a picker's list: its value where that is not null and no other entry has it, else its position. With these
 * the list keeps its highlight on the same entry when the entries change while it is open: the models arrive, or the CLI reports
 * another mode.
 */
internal fun <T> List<PickerEntry<T>>.pickerKeys(): List<PickerKey> {
    val counts = groupingBy { it.value }.eachCount()
    return mapIndexed { index, entry -> entry.value?.takeIf { counts[it] == 1 }?.let { PickerKey.Value(it) } ?: PickerKey.Position(index) }
}

/**
 * A picker's list, and the entry the user moved its highlight to, by key ([held]).
 *
 * While the list is [open], moving the highlight (the arrow keys, Home, End) only holds that entry, which the picker shows. It is
 * picked once, when the list closes (Enter, a click on it, or the focus going elsewhere), unless Escape dropped it first. After the
 * pick the picker goes on showing it until what it is given to show changes ([settled]), so that the entry it showed before does
 * not flash back while the pick is on its way to the session.
 */
internal data class PickerList(val open: Boolean = false, val held: PickerKey? = null) {
    /** The list opened, with nothing held. */
    fun opened(): PickerList = PickerList(open = true)

    /** Escape while the list is open: the held entry is dropped, so that closing the list picks nothing. */
    fun escaped(): PickerList = if (open) copy(held = null) else this

    /** What the picker is given to show changed: a pick made as the list closed has arrived, or something else replaced it. */
    fun settled(): PickerList = if (open) this else PickerList()
}

/** What a picker does on an event of its list: its next [list], and the position of the entry to pick, if any. */
internal data class PickerStep(val list: PickerList, val pick: Int?)

/** The position of the entry the picker shows: the held one while it is still listed, else [shown]. */
internal fun PickerList.position(keys: List<PickerKey>, shown: Int): Int = held?.let { keys.indexOf(it) }?.takeIf { it >= 0 } ?: shown

/** The key of the entry an open list is to highlight ([position]); null when there is none. */
internal fun PickerList.highlighted(keys: List<PickerKey>, shown: Int): PickerKey? = keys.getOrNull(position(keys, shown))

/**
 * The list selected the entry at [index]. While it is open, that entry is held. While it is closed, which Jewel's list does not do
 * today, the entry is picked at once unless it is the one [shown], or the one just picked that is still on its way.
 */
internal fun PickerList.selected(index: Int, keys: List<PickerKey>, shown: Int): PickerStep {
    val key = keys.getOrNull(index) ?: return PickerStep(this, null)
    return when {
        open -> PickerStep(copy(held = key), null)
        index == shown -> PickerStep(PickerList(), null)
        key == held -> PickerStep(this, null)
        else -> PickerStep(PickerList(held = key), index)
    }
}

/**
 * The list closed: the held entry is picked if it is still listed and is not the one [shown], and the picker goes on showing it until
 * [PickerList.settled]. Without one, nothing is picked; nor when the list was not open, as when Jewel reports the close twice.
 */
internal fun PickerList.closed(keys: List<PickerKey>, shown: Int): PickerStep {
    if (!open) return PickerStep(this, null)
    val pick = held?.let { keys.indexOf(it) }?.takeIf { it >= 0 && it != shown }
    return PickerStep(if (pick == null) PickerList() else PickerList(held = held), pick)
}

/**
 * A drop-down list of [entries] that shows the one standing for [selected] (the first one when none does). [description] names it
 * for assistive technology, since what it shows ("Default") does not say what it picks. A click on an entry picks it ([onPick]);
 * from the keyboard the arrow keys, Home and End only move the highlight, Enter picks the entry it is on and Escape picks nothing
 * ([PickerList]). Picking the entry shown does nothing.
 */
@Composable
internal fun <T> Picker(
    entries: List<PickerEntry<T>>,
    selected: T?,
    description: String,
    modifier: Modifier = Modifier,
    onPick: (T) -> Unit,
) {
    val labels = remember(entries) { entries.map { it.label } }
    val keys = remember(entries) { entries.pickerKeys() }
    val shown = entries.positionOf(selected)
    var list by remember { mutableStateOf(PickerList()) }
    // Jewel's list keeps the first visibility callback it is given, so the callbacks read the current entries from here.
    val currentEntries by rememberUpdatedState(entries)
    val currentKeys by rememberUpdatedState(keys)
    val currentShown by rememberUpdatedState(shown)
    val currentOnPick by rememberUpdatedState(onPick)

    fun take(step: PickerStep) {
        list = step.list
        step.pick?.let { currentOnPick(currentEntries[it].value) }
    }

    LaunchedEffect(shown) { list = list.settled() }
    // Jewel's open list keeps its highlight where it was when what the picker shows changes under it (the CLI reports another mode,
    // the models arrive): move it to the entry the picker shows now. The argument is the list's first visible item, not its
    // selection: the entry shown, at the top, as ListComboBox's own default state has it (rememberSelectableLazyListState(
    // selectedIndex.takeIfInBoundsOrZero(items.indices)) in Jewel 261). It is read once, when the state is made.
    val listState = rememberSelectableLazyListState(shown.coerceIn(0, (keys.size - 1).coerceAtLeast(0)))
    LaunchedEffect(keys, shown) {
        if (!list.open) return@LaunchedEffect
        val key = list.highlighted(keys, shown) ?: return@LaunchedEffect
        listState.selectedKeys = setOf(key)
        listState.lastActiveItemIndex = keys.indexOf(key)
    }
    ListComboBox(
        items = labels,
        selectedIndex = list.position(keys, shown),
        onSelectedItemChange = { index -> take(list.selected(index, currentKeys, currentShown)) },
        modifier = modifier
            .semantics { contentDescription = description }
            // Ahead of the list's own key handling, which closes it on Escape.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) list = list.escaped()
                false
            },
        maxPopupWidth = PICKER_POPUP_WIDTH,
        onPopupVisibleChange = { visible -> if (visible) list = list.opened() else take(list.closed(currentKeys, currentShown)) },
        itemKeys = remember(keys) { { index: Int, _: String -> keys.getOrElse(index) { PickerKey.Position(index) } } },
        listState = listState,
    )
}

/**
 * The new-session popup's model picker: "Default model", then the models the CLI offers ([modelEntries]; no session runs yet, so
 * there is no running model to list while it has not listed them). It shows [model], the one picked (null: the CLI's default); a
 * pick calls [onPick] with its value, null for "Default model".
 */
@Composable
internal fun ModelPicker(models: List<ModelOption>, model: String?, onPick: (String?) -> Unit) {
    val entries = remember(models, model) { modelEntries(models, model, active = null) }
    Picker(entries, model, "Model", onPick = onPick)
}

/**
 * The new-session popup's permission mode picker: "Default mode", then Auto, Manual, Accept edits and Plan, plus a mode Tandem does
 * not offer when it is the one given ([permissionModeEntries]). It shows [mode], and "Default mode" for none; a pick calls [onPick]
 * with the mode, null for "Default mode".
 */
@Composable
internal fun PermissionModePicker(mode: PermissionMode?, onPick: (PermissionMode?) -> Unit) {
    val entries = remember(mode) { permissionModeEntries(mode) }
    Picker(entries, mode, "Permission mode", onPick = onPick)
}
