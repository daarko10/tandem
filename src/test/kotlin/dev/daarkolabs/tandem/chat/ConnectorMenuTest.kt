package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ControlPayloads
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.McpStatus
import dev.daarkolabs.tandem.session.McpState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `+` menu's Connectors page (spec 17.2, 17.5). The `claudeai` servers are synthetic, docs-only entries: no fixture has a
 * connector (spec 11 item 20); they sit beside `mcp-status:2`'s three dynamic servers.
 */
class ConnectorMenuTest {
    private val dynamic: List<McpServerInfo> =
        ControlPayloads.mcpStatus((fixtureLine("mcp-status", 2) as CliMessage.ControlResponse).payload)!!.servers

    private fun connector(label: String, status: McpStatus, statusText: String = status.wire, error: String? = null): McpServerInfo =
        McpServerInfo("claude.ai $label", status, statusText, error = error, source = "claudeai")

    private val connectors: List<McpServerInfo> = listOf(
        connector("Alpha", McpStatus.CONNECTED),
        connector("Beta", McpStatus.NEEDS_AUTH),
        connector("Gamma", McpStatus.FAILED, error = "Connection closed"),
        connector("Delta", McpStatus.PENDING),
        connector("Epsilon", McpStatus.DISABLED),
        connector("Zeta", McpStatus.OTHER, statusText = "rate-limited"),
    )

    private fun panel(servers: List<McpServerInfo> = dynamic + connectors, live: Boolean = true): McpPanelModel =
        mcpPanel(McpState(servers, detailed = true), live = live)

    private fun menu(
        busy: McpBusyView? = null,
        rowErrors: Map<String, String> = emptyMap(),
        notice: String? = null,
        panel: McpPanelModel = panel(),
    ): ConnectorMenuModel = connectorMenu(panel, busy, rowErrors, notice)

    private fun ConnectorMenuModel.row(label: String): ConnectorRow = rows.single { it.label == label }

    @Test
    fun `only the connectors group, in the CLI's order`() {
        val rows = menu().rows
        assertEquals(listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta"), rows.map { it.label })
        assertTrue(rows.all { it.name.startsWith("claude.ai ") })
        assertTrue(rows.none { it.name in setOf("echo", "broken", "authy") })
        assertEquals(listOf("echo", "broken", "authy"), dynamic.map { it.name })
        assertNull(menu().note)
    }

    @Test
    fun `on for every status but disabled`() {
        assertEquals(listOf(true, true, true, true, false, true), menu().rows.map { it.on })
    }

    @Test
    fun `a status line for pending, needs-auth, failed and an unknown status, none for connected and disabled`() {
        assertEquals(listOf(null, "Needs auth", "Failed", "Pending", null, "rate-limited"), menu().rows.map { it.status })
    }

    @Test
    fun `a pending row cannot switch`() {
        assertEquals(listOf(true, true, true, false, true, true), menu().rows.map { it.switchable })
    }

    @Test
    fun `a busy row shows its caption and its new position, and no row is switchable`() {
        val disabling = menu(busy = McpBusyView("claude.ai Alpha", McpAction.DISABLE, "Disabling…"))
        assertEquals("Disabling…", disabling.row("Alpha").caption)
        assertEquals(false, disabling.row("Alpha").on)
        assertTrue(disabling.rows.none { it.switchable })
        assertTrue(disabling.rows.filter { it.label != "Alpha" }.all { it.caption == null })
        val enabling = menu(busy = McpBusyView("claude.ai Epsilon", McpAction.ENABLE, "Enabling…"))
        assertEquals(true, enabling.row("Epsilon").on)
        assertEquals("Enabling…", enabling.row("Epsilon").caption)
    }

    @Test
    fun `a row error shows, the last toggle's before the entry's`() {
        val model = menu(rowErrors = mapOf("claude.ai Beta" to "boom", "claude.ai Gamma" to "later"))
        assertEquals("boom", model.row("Beta").error)
        assertEquals("later", model.row("Gamma").error)
        assertEquals("Connection closed", menu().row("Gamma").error)
        assertNull(menu().row("Alpha").error)
    }

    @Test
    fun `a stopped panel's rows are last known and cannot switch`() {
        val stopped = menu(panel = panel(live = false))
        assertEquals("Last known: the session is not running.", stopped.note)
        assertEquals(CONNECTORS_LAST_KNOWN, stopped.note)
        assertTrue(stopped.rows.isNotEmpty())
        assertTrue(stopped.rows.none { it.switchable })
    }

    @Test
    fun `no connector says so`() {
        val none = menu(panel = panel(dynamic))
        assertTrue(none.rows.isEmpty())
        assertEquals("No claude.ai connectors in this session.", none.note)
        assertEquals(NO_CONNECTORS, menu(panel = panel(dynamic, live = false)).note)
    }

    @Test
    fun `the controller's notice comes before either note`() {
        val notice = "Could not list servers: x"
        assertEquals(notice, menu(notice = notice).note)
        assertEquals(notice, menu(notice = notice, panel = panel(dynamic)).note)
        assertEquals(notice, menu(notice = notice, panel = panel(live = false)).note)
        assertEquals(notice, menu(notice = notice, panel = panel(dynamic, live = false)).note)
    }

    @Test
    fun `toggleAction disables an on row and enables an off one, and runs nothing for a row that cannot switch`() {
        val model = menu()
        assertEquals(McpAction.DISABLE, toggleAction(model.row("Alpha")))
        assertEquals(McpAction.ENABLE, toggleAction(model.row("Epsilon")))
        assertNull(toggleAction(model.row("Delta")))
        val busy = menu(busy = McpBusyView("claude.ai Beta", McpAction.DISABLE, "Disabling…"))
        assertTrue(busy.rows.all { toggleAction(it) == null })
    }
}
