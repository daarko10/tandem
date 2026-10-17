package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.Box
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
import dev.daarkolabs.tandem.session.NewSessionOptions
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.PopupContainer
import org.jetbrains.jewel.ui.component.Text

/**
 * The popup that starts a new session, below the button that opened it: "Create", focused, starts one with [defaults]
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

/**
 * A button that opens [NewSessionPopup] below itself: [button] is given the function that opens it. "Create" closes the popup and
 * passes the options, [defaults] for now, to [onCreate]. The sidebar's "New session" button and the header's "+" are such buttons.
 */
@Composable
internal fun NewSessionLauncher(
    defaults: () -> NewSessionOptions,
    onCreate: (NewSessionOptions) -> Unit,
    button: @Composable (open: () -> Unit) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    Box {
        button { shown = true }
        if (shown) {
            NewSessionPopup(
                defaults(),
                onCreate = { options ->
                    shown = false
                    onCreate(options)
                },
                onDismiss = { shown = false },
            )
        }
    }
}
