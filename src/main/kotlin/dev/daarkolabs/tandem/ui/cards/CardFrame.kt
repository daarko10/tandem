package dev.daarkolabs.tandem.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.ui.chatColumn
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * The chrome every card shares (spec 4.12–4.14, 6): the chat column's width, a hairline border (the focus colour while the card
 * holds the focus) round 16 dp of padding on the page colour, known to assistive technology as [description] and to tests as [tag].
 * When a request ([requestId]) shows, the focus goes to the card's default button ([defaultFocus]); Tab cycles inside the card.
 * Keys go through [cardKey] before the focused control sees them: Enter is [onEnter] (unless a text field has the focus,
 * [textFieldFocused]), Cmd/Ctrl+Enter is [onCmdEnter] when the card offers one, Esc is [onEscape].
 */
@Composable
internal fun CardFrame(
    requestId: String,
    tag: String,
    description: String,
    textFieldFocused: Boolean,
    defaultFocus: FocusRequester,
    onEnter: () -> Unit,
    onCmdEnter: (() -> Unit)?,
    onEscape: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    LaunchedEffect(requestId) { defaultFocus.requestFocus() }
    var focused by remember { mutableStateOf(false) }
    val colors = JewelTheme.globalColors
    val shape = RoundedCornerShape(8.dp)
    Column(
        // 8 dp above the card; 4 dp below it, which the composer box's own 4 dp top padding makes 8 (spec 4.1: card → composer 8 dp).
        Modifier.chatColumn().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .onFocusChanged { focused = it.hasFocus }
            .border(1.dp, if (focused) colors.borders.focused else colors.borders.normal, shape)
            .background(colors.panelBackground, shape)
            .padding(16.dp)
            .focusGroup()
            .testTag(tag)
            .semantics { contentDescription = description }
            .onPreviewKeyEvent { event ->
                val key = cardKey(event, hasCmdEnter = onCmdEnter != null, textFieldFocused = textFieldFocused) ?: return@onPreviewKeyEvent false
                when (key) {
                    CardKey.ENTER -> onEnter()
                    CardKey.CMD_ENTER -> onCmdEnter?.invoke()
                    CardKey.ESCAPE -> onEscape()
                }
                true
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * A card's button: [label], then its key [cap] in the secondary colour, a [primary] one filled ([DefaultButton]), else outlined.
 * It is known to assistive technology, and to the IDE tests, by [label] alone (spec 4.19).
 */
@Composable
internal fun CardButton(label: String, cap: String?, primary: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val described = modifier.semantics { contentDescription = label }
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label)
            if (cap != null) {
                Spacer(Modifier.width(6.dp))
                Text(cap, color = JewelTheme.globalColors.text.info)
            }
        }
    }
    if (primary) {
        DefaultButton(onClick = onClick, modifier = described, enabled = enabled, content = content)
    } else {
        OutlinedButton(onClick = onClick, modifier = described, enabled = enabled, content = content)
    }
}

/** `N more waiting` under a card's buttons when [count] more requests wait behind it (spec 4.12 item 6); nothing when none does. */
@Composable
internal fun MoreWaiting(count: Int) {
    moreWaitingText(count)?.let { Text(it, color = JewelTheme.globalColors.text.info) }
}

/** `1 more waiting` / `N more waiting`, or null when [count] is 0. */
internal fun moreWaitingText(count: Int): String? = if (count <= 0) null else "$count more waiting"
