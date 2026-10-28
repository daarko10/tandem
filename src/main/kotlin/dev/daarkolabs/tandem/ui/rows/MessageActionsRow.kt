package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import com.intellij.openapi.ide.CopyPasteManager
import dev.daarkolabs.tandem.chat.ForkState
import dev.daarkolabs.tandem.chat.MessageActions
import dev.daarkolabs.tandem.chat.absoluteTime
import dev.daarkolabs.tandem.chat.forkState
import dev.daarkolabs.tandem.chat.relativeTime
import dev.daarkolabs.tandem.ui.WithTooltip
import dev.daarkolabs.tandem.ui.chatPalette
import dev.daarkolabs.tandem.ui.smallTextStyle
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.datatransfer.StringSelection
import java.time.ZoneId
import java.util.Locale

/** How often the hover row reads the clock again for its relative time. */
private const val CLOCK_TICK_MS: Long = 60_000L

/**
 * What the hover rows under the replies of one session need (polish spec 2.6): [onCopy] puts a reply's Markdown on the clipboard and
 * [onFork] forks the session at a reply's fork point (desktop-parity spec 13.1). The fork state comes from the row alone, so a turn's
 * start or end recomposes no hover row.
 */
internal class ReplyHost(val onCopy: (String) -> Unit, val onFork: (forkAt: String) -> Unit)

/** Puts [text] on the clipboard: the default [ReplyHost.onCopy]. */
internal fun copyToClipboard(text: String) {
    CopyPasteManager.getInstance().setContents(StringSelection(text))
}

/** The hover row's alpha: shown while the pointer is over the reply or the row, or one of its buttons has focus, else hidden. */
internal fun actionRowAlpha(hovered: Boolean, focused: Boolean): Float = if (hovered || focused) 1f else 0f

/**
 * The hover row under a reply (polish spec 2.6): Copy and Fork from here, 24 dp buttons edge to edge with 16 dp icons in the dim
 * tier, then the relative time 12 dp after them (absent without [MessageActions.atMillis]), its absolute time in its tooltip. Always
 * laid out at 24 dp; shown while [hovered] (the pointer is over the reply), while the pointer is over the row itself, or while one of
 * its buttons has focus, so Tab reaches it.
 */
@Composable
internal fun MessageActionsRow(actions: MessageActions, reply: ReplyHost, hovered: Boolean) {
    val palette = chatPalette()
    val source = remember { MutableInteractionSource() }
    val ownHover by source.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val fork = forkState(actions.forkAt)
    val tint = remember(palette.dim) { ColorFilter.tint(palette.dim) }
    Row(
        Modifier.height(24.dp)
            .onFocusChanged { focused = it.hasFocus }
            .hoverable(source)
            .alpha(actionRowAlpha(hovered || ownHover, focused)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WithTooltip("Copy") {
            IconActionButton(
                AllIconsKeys.Actions.Copy,
                contentDescription = "Copy",
                onClick = { reply.onCopy(actions.markdown) },
                modifier = Modifier.size(24.dp),
                iconModifier = Modifier.size(16.dp),
                colorFilter = tint,
            )
        }
        WithTooltip(tooltip = { ForkTooltip(fork) }) {
            IconActionButton(
                AllIconsKeys.Vcs.Branch,
                contentDescription = "Fork from here",
                onClick = { actions.forkAt?.takeIf { fork == ForkState.ENABLED }?.let(reply.onFork) },
                modifier = Modifier.size(24.dp),
                iconModifier = Modifier.size(16.dp),
                enabled = fork == ForkState.ENABLED,
                colorFilter = tint,
            )
        }
        actions.atMillis?.let { at -> RelativeTime(at) }
    }
}

/** The fork button's tooltip by its state (desktop-parity spec 13.1); the button's accessible name stays `Fork from here`. */
@Composable
private fun ForkTooltip(fork: ForkState) {
    when (fork) {
        ForkState.ENABLED -> Column {
            Text("Fork from here")
            Text("Starts a new session from this point. This one stays as it is.", color = chatPalette().dim)
        }
        ForkState.UNAVAILABLE -> Text("This reply cannot be forked: the CLI sent no id for it")
    }
}

/** `6 days ago` in body − 1 dim, 12 dp after the buttons, re-read every minute; the absolute time in its tooltip. */
@Composable
private fun RelativeTime(atMillis: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val absolute = remember(atMillis) { absoluteTime(atMillis, ZoneId.systemDefault(), Locale.getDefault()) }
    WithTooltip(absolute) {
        Text(relativeTime(atMillis, now), style = smallTextStyle(), color = chatPalette().dim, modifier = Modifier.padding(start = 12.dp))
    }
}
