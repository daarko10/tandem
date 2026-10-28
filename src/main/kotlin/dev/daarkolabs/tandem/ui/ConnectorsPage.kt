package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.openapi.diagnostic.logger
import dev.daarkolabs.tandem.chat.ConnectorMenuModel
import dev.daarkolabs.tandem.chat.ConnectorRow
import dev.daarkolabs.tandem.chat.McpAction
import dev.daarkolabs.tandem.chat.McpBusyView
import dev.daarkolabs.tandem.chat.McpPanelModel
import dev.daarkolabs.tandem.chat.connectorMenu
import dev.daarkolabs.tandem.chat.toggleAction
import dev.daarkolabs.tandem.ide.BrowserOpener
import dev.daarkolabs.tandem.session.McpAuthWait
import dev.daarkolabs.tandem.session.McpControls
import dev.daarkolabs.tandem.session.McpState
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionStatus
import dev.daarkolabs.tandem.ui.rows.ConnectorSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.yield
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// The `+` menu's Connectors page (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 17.2, 17.5): one switch per claude.ai
// connector of the session, run by the MCP panel's own controller.

private val LOG = logger<ConnectorsPage>()

/**
 * What the open Connectors page draws and runs: its [model], whether its note is an error ([noteError]: the controller's message or
 * a failed start, in the error colour), and [run], the page's controller's request path ([McpPanelController.run]).
 */
internal class ConnectorsPage(val model: ConnectorMenuModel, val noteError: Boolean = false, val run: (McpAction, String) -> Unit)

/**
 * The page's own note (spec 17.2): the controller's [message]; else, while the page's start of the session runs or after the cap or a
 * failure stopped it, the MCP panel's notice for that [start] ([mcpPanelNotice]); else null, and [connectorMenu]'s own notes apply.
 */
internal fun connectorPageNotice(model: McpPanelModel, start: McpPanelStart, message: String?): String? = message ?: when (start) {
    McpPanelStart.Starting, is McpPanelStart.NoRoom, is McpPanelStart.Failed -> mcpPanelNotice(model, start, message)!!.text
    McpPanelStart.None -> null
}

/** A connector row's switch semantics: what assistive technology reads ([description]), its [state] and whether it can be used. */
internal data class RowSemantics(val description: String, val state: String, val enabled: Boolean)

/**
 * [row]'s semantics (spec 17.2, 3.7): `<label> connector`, then its caption (while its request runs) or its status line, then its
 * error, each when shown; `On` or `Off`; enabled while it can switch.
 */
internal fun connectorRowSemantics(row: ConnectorRow): RowSemantics = RowSemantics(
    listOfNotNull("${row.label} connector", row.caption ?: row.status, row.error).joinToString(", "),
    if (row.on) "On" else "Off",
    row.switchable,
)

/**
 * A click or Enter on [row] (spec 17.2): [menu] stays open on its page through the close request that follows ([PagedMenuState.hold]),
 * and the switch's request runs through [run] ([toggleAction]); a row that cannot switch runs nothing.
 */
internal fun onConnectorRow(row: ConnectorRow, menu: PagedMenuState, run: (McpAction, String) -> Unit) {
    menu.hold()
    toggleAction(row)?.let { run(it, row.name) }
}

/**
 * The open Connectors page's controller (spec 17.2): the MCP panel's ([McpPanelController]), built with the arguments `McpPanelHost`
 * gives it, but with `startOnOpen = true`: opening the page is the user action, so a session without a process starts within the cap.
 * The page never reads [ChatUiState.takeMcpStartOnOpen], the panel's one-shot flag.
 */
internal fun connectorPageController(
    id: SessionId,
    servers: StateFlow<McpState>,
    auth: StateFlow<Map<String, McpAuthWait>>,
    loginHinted: StateFlow<Set<String>>,
    status: StateFlow<SessionStatus>,
    controls: McpControls,
    opener: BrowserOpener,
    scope: CoroutineScope,
    endReason: () -> String?,
): McpPanelController =
    McpPanelController(id, servers, auth, loginHinted, status, controls, opener, scope, endReason = endReason, startOnOpen = true)

/**
 * The Connectors page while it is open: one controller from [build], in this composition's scope, keyed on [build] (one per session
 * and opening), closed when the page leaves or the menu closes; its model from the controller's flows ([connectorMenu]). Logs the
 * opening and each toggle's outcome, never a connector's name.
 */
@Composable
internal fun rememberConnectorsPage(build: (CoroutineScope) -> McpPanelController): ConnectorsPage {
    val scope = rememberCoroutineScope()
    val controller = remember(build) {
        LOG.info("connectors page opened")
        build(scope)
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) {
        var running: McpBusy? = null
        controller.busy.collect { now ->
            val ended = running
            running = now
            if (ended != null && now == null) {
                // The controller sets a failed toggle's row error right after it clears busy.
                yield()
                val failed = controller.rowErrors.value.containsKey(ended.name)
                LOG.info("connector toggle ${ended.action.name}: ${if (failed) "failed" else "done"}")
            }
        }
    }
    val rows by controller.rows.collectAsState()
    val busy by controller.busy.collectAsState()
    val rowErrors by controller.rowErrors.collectAsState()
    val message by controller.message.collectAsState()
    val start by controller.start.collectAsState()
    val model = remember(rows, busy, rowErrors, message, start) {
        connectorMenu(rows, busy?.let { McpBusyView(it.name, it.action, it.caption) }, rowErrors, connectorPageNotice(rows, start, message))
    }
    return ConnectorsPage(model, noteError = message != null || start is McpPanelStart.Failed, run = controller::run)
}

/**
 * The Connectors page (spec 17.2) at most [cap] high: `‹ Connectors` ([onBack]), a separator, `Browse connectors` ([onBrowse]) and
 * `Manage connectors` ([onManage]), which stay put; a separator, then one switch row per connector of [model], which scroll, and the
 * page's note, dim (in the error colour with [noteError]). A row's click or Enter goes to [onToggle]. Names are config text: plain [Text].
 */
internal fun MenuScope.connectorsPage(
    model: ConnectorMenuModel,
    cap: Dp?,
    noteError: Boolean,
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onManage: () -> Unit,
    onToggle: (ConnectorRow) -> Unit,
) {
    pagedRows(
        PLUS_CONNECTORS,
        cap,
        header = {
            BackRow("Connectors", chatPalette(), onBack)
            PageSeparator()
            PageRow(iconKey = AllIconsKeys.General.Web, onClick = onBrowse) { MenuLabel("Browse connectors") }
            PageRow(iconKey = AllIconsKeys.General.Settings, onClick = onManage) { MenuLabel("Manage connectors") }
        },
    ) {
        for (row in model.rows) ConnectorRowItem(row) { onToggle(row) }
        model.note?.let { note ->
            val palette = chatPalette()
            Text(
                note,
                style = smallTextStyle(),
                color = if (noteError) JewelTheme.globalColors.text.error else palette.dim,
                modifier = PASSIVE_INSET,
            )
        }
    }
}

/**
 * One connector's row (spec 17.2): the server icon; its label, one line, ellipsised, the full name in its tooltip; under it, dim, its
 * caption or status; under that, in the error colour, one line of its error, the whole text in its tooltip; the switch at the end.
 * One merged node with the switch's role and state ([connectorRowSemantics]), disabled while it cannot switch.
 */
@Composable
private fun ConnectorRowItem(row: ConnectorRow, onClick: () -> Unit) {
    val palette = chatPalette()
    val small = smallTextStyle()
    val s = connectorRowSemantics(row)
    PageRow(
        onClick = onClick,
        iconKey = AllIconsKeys.Webreferences.Server,
        enabled = s.enabled,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = s.description
            role = Role.Switch
            stateDescription = s.state
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                WithTooltip(row.name) { Text(row.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                (row.caption ?: row.status)?.let { Text(it, style = small, color = palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                row.error?.let { error ->
                    WithTooltip(error) {
                        Text(error, style = small, color = JewelTheme.globalColors.text.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            ConnectorSwitch(row.on, row.switchable)
        }
    }
}
