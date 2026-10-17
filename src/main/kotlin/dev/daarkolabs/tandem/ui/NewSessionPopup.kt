package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.session.NewSessionOptions
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.PopupContainer
import org.jetbrains.jewel.ui.component.Text

/**
 * The popup the sidebar's "New session" button opens, below the button: "Create", focused, starts a session with [defaults]
 * ([onCreate]). The model, effort and permission mode pickers are still to come; until then a session starts with the last ones
 * picked for the project. Escape or a click outside closes it ([onDismiss]).
 */
@Composable
internal fun NewSessionPopup(defaults: NewSessionOptions, onCreate: (NewSessionOptions) -> Unit, onDismiss: () -> Unit) {
    PopupContainer(onDismissRequest = onDismiss, horizontalAlignment = Alignment.Start) {
        val focus = remember { FocusRequester() }
        DefaultButton(onClick = { onCreate(defaults) }, modifier = Modifier.padding(8.dp).focusRequester(focus)) { Text("Create") }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
