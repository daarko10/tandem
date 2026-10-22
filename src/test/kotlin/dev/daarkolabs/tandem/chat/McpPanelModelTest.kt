package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ControlPayloads
import dev.daarkolabs.tandem.protocol.McpConfig
import dev.daarkolabs.tandem.protocol.McpPeer
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.McpStatus
import dev.daarkolabs.tandem.protocol.McpTool
import dev.daarkolabs.tandem.session.McpState
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MCP panel's rows (spec 3.2, 4.3). The `claudeai`, `project`, `local`, `user`, `plugin` and `managed` entries and the
 * `claudeai-proxy` fallback are synthetic, docs-only data: no fixture has them.
 */
class McpPanelModelTest {
    private fun server(
        name: String,
        status: McpStatus = McpStatus.CONNECTED,
        source: String? = "dynamic",
        statusText: String = status.wire,
        error: String? = null,
        config: McpConfig? = null,
        version: String? = null,
        tools: List<McpTool>? = null,
    ): McpServerInfo = McpServerInfo(
        name = name,
        status = status,
        statusText = statusText,
        error = error,
        source = source,
        config = config,
        serverInfo = version?.let { McpPeer(name, it) },
        tools = tools,
    )

    private fun panel(vararg servers: McpServerInfo, loginHinted: Set<String> = emptySet(), live: Boolean = true): McpPanelModel =
        mcpPanel(McpState(servers.toList(), detailed = true), loginHinted = loginHinted, live = live)

    private fun McpPanelModel.rows(): List<McpRowModel> = groups.flatMap { it.rows }

    private fun row(server: McpServerInfo, loginHinted: Set<String> = emptySet()): McpRowModel = panel(server, loginHinted = loginHinted).rows().single()

    /** The `mcp_status` answer on line [line] of `mcp-status`. */
    private fun listed(line: Int): McpState {
        val answer = fixtureLine("mcp-status", line) as CliMessage.ControlResponse
        return McpState(ControlPayloads.mcpStatus(answer.payload)!!.servers, detailed = true)
    }

    @Test
    fun `groups follow the fixed order and skip empty ones`() {
        val model = panel(
            server("weird", source = "enterprise"),
            server("mine", source = "managed"),
            server("echo", source = "dynamic"),
            server("p", source = "plugin"),
            server("u", source = "user"),
            server("l", source = "local"),
            server("proj", source = "project"),
            server("claude.ai Drive", source = "claudeai"),
            server("proxy", source = null, config = McpConfig("claudeai-proxy", null, emptyList(), "https://h/x")),
            server("second", source = "dynamic"),
        )
        assertEquals(
            listOf(
                McpSource.CLAUDEAI,
                McpSource.PROJECT,
                McpSource.LOCAL,
                McpSource.USER,
                McpSource.PLUGIN,
                McpSource.DYNAMIC,
                McpSource.MANAGED,
                McpSource.OTHER,
            ),
            model.groups.map { it.source },
        )
        assertEquals(listOf("claude.ai connectors", "Project", "Local", "User", "Plugins", "Session", "Managed", "Other"), model.groups.map { it.source.title })
        // Rows in the CLI's order inside a group.
        assertEquals(listOf("claude.ai Drive", "proxy"), model.groups.first().rows.map { it.name })
        assertEquals(listOf("echo", "second"), model.groups.single { it.source == McpSource.DYNAMIC }.rows.map { it.name })
        val other = model.groups.last().rows.single()
        assertEquals("enterprise", other.rawSource)
        assertTrue(model.rows().filter { it !== other }.all { it.rawSource == null })

        val sessionOnly = panel(server("echo"), server("broken", McpStatus.FAILED))
        assertEquals(listOf(McpSource.DYNAMIC), sessionOnly.groups.map { it.source })
        assertEquals(emptyList(), panel().groups)
    }

    @Test
    fun `a connector shows its name without the prefix`() {
        val connector = row(server("claude.ai Google Drive", source = "claudeai"))
        assertEquals("Google Drive", connector.displayName)
        assertEquals("claude.ai Google Drive", connector.name)
        // Only a connector's prefix goes: another source keeps it.
        assertEquals("claude.ai Notes", row(server("claude.ai Notes", source = "user")).displayName)
    }

    @Test
    fun `a connector named only by the prefix keeps its full name`() {
        val connector = row(server("claude.ai ", source = "claudeai"))
        assertEquals("claude.ai ", connector.displayName)
        assertEquals("claude.ai ", connector.name)
    }

    @Test
    fun `line two is the command without args or the url's scheme, host and port`() {
        fun url(url: String) = row(server("h", config = McpConfig("http", "/bin/fallback", emptyList(), url))).detail
        assertEquals("http://h", url("http://h/mcp?x=1#f"))
        assertEquals("http://h", url("http://h/mcp#f?x=1"))
        // User info and a key in the path never show (spec 3.2: a screen share).
        assertEquals("https://h", url("https://u:secret@h/x"))
        assertEquals("https://h", url("https://h/api/mcp/s/SECRET/mcp"))
        assertEquals("https://h:8443", url("https://h:8443/mcp"))
        assertEquals("https://h:1", url("https://u:p%40ss@h:1/x"))
        assertEquals("http://my_server.local:9", url("http://my_server.local:9/mcp"))
        // No host, or no parse: the command instead, never the raw url.
        assertEquals("/bin/fallback", url("not a url"))
        assertEquals("/bin/fallback", url("mcp/relative"))
        val stdio = server("s", config = McpConfig("stdio", "/usr/bin/python3", listOf("-I", "--token=secret"), null))
        assertEquals("/usr/bin/python3", row(stdio).detail)
        assertNull(row(server("bare")).detail)
        assertNull(row(server("empty", config = McpConfig(null, null, emptyList(), null))).detail)
        // Recorded: mcp-status:18.
        assertEquals(listOf("/usr/bin/python3", "/bin/false", "http://127.0.0.1:33041"), mcpPanel(listed(18)).rows().map { it.detail })
    }

    @Test
    fun `Reconnect on a needs-auth row only after the terminal hint and never on a dynamic one`() {
        val user = server("authy", McpStatus.NEEDS_AUTH, source = "user")
        assertEquals(listOf(McpAction.AUTHENTICATE, McpAction.DISABLE), row(user).actions)
        assertEquals(listOf(McpAction.AUTHENTICATE, McpAction.RECONNECT, McpAction.DISABLE), row(user, loginHinted = setOf("authy")).actions)
        // Another server's hint does not count.
        assertEquals(listOf(McpAction.AUTHENTICATE, McpAction.DISABLE), row(user, loginHinted = setOf("other")).actions)
        // An unknown source gets the hint like any source but dynamic.
        val unknown = server("authy", McpStatus.NEEDS_AUTH, source = null)
        assertEquals(listOf(McpAction.AUTHENTICATE, McpAction.RECONNECT, McpAction.DISABLE), row(unknown, loginHinted = setOf("authy")).actions)
        val dynamic = server("authy", McpStatus.NEEDS_AUTH, source = "dynamic")
        assertEquals(listOf(McpAction.AUTHENTICATE, McpAction.DISABLE), row(dynamic, loginHinted = setOf("authy")).actions)
        // Only needs-auth rows: a connected one stays as it is.
        assertEquals(listOf(McpAction.DISABLE), row(server("authy", source = "user"), loginHinted = setOf("authy")).actions)
    }

    @Test
    fun `a needs-auth entry with an error draws the error line`() {
        val needsAuth = row(server("authy", McpStatus.NEEDS_AUTH, error = "session token rejected"))
        assertEquals("session token rejected", needsAuth.error)
        assertEquals("Needs auth", needsAuth.label)
        // Every status carries its error verbatim; none without one.
        assertEquals("boom", row(server("d", McpStatus.DISABLED, error = "boom")).error)
        assertEquals("  spaced  ", row(server("c", error = "  spaced  ")).error)
        assertNull(row(server("c")).error)
        assertEquals(listOf(null, "Connection closed", null), mcpPanel(listed(18)).rows().map { it.error })
    }

    @Test
    fun `a blank or missing status reads Unknown status`() {
        assertEquals("Unknown status", row(server("x", McpStatus.OTHER, statusText = "")).label)
        assertEquals("Unknown status", row(server("x", McpStatus.OTHER, statusText = "   ")).label)
        assertEquals(listOf(McpAction.DISABLE), row(server("x", McpStatus.OTHER, statusText = "")).actions)
    }

    @Test
    fun `a stopped session's rows have no actions`() {
        val model = panel(
            server("echo"),
            server("broken", McpStatus.FAILED, error = "Connection closed"),
            server("authy", McpStatus.NEEDS_AUTH, source = "user"),
            server("off", McpStatus.DISABLED),
            loginHinted = setOf("authy"),
            live = false,
        )
        assertTrue(model.stopped)
        assertTrue(model.rows().all { it.actions.isEmpty() }, model.toString())
        // The rows themselves stay as last known (the user group first).
        assertEquals(listOf("Needs auth", "Connected", "Failed", "Disabled"), model.rows().map { it.label })
        assertEquals("Connection closed", model.rows()[2].error)
        assertFalse(panel(server("echo")).stopped)
    }

    @Test
    fun `labels and actions - connected`() {
        val connected = row(server("echo", version = "0.1.0", tools = listOf(McpTool("echo"))))
        assertEquals(McpStatus.CONNECTED, connected.status)
        assertEquals("Connected", connected.label)
        assertEquals(listOf(McpAction.DISABLE), connected.actions)
        assertEquals("1 tool", connected.tools)
        assertEquals("v0.1.0", connected.version)
        assertFalse(connected.readOnly)
        assertEquals("3 tools", row(server("many", tools = listOf(McpTool("a"), McpTool("b"), McpTool("c")))).tools)
        assertEquals("0 tools", row(server("none", tools = emptyList())).tools)
        assertNull(row(server("unknown")).tools)
        assertNull(row(server("unknown")).version)
        // Recorded: mcp-status:18's echo.
        val echo = mcpPanel(listed(18)).rows().first()
        assertEquals(listOf("echo", "Connected", "1 tool", "v0.1.0"), listOf(echo.displayName, echo.label, echo.tools, echo.version))
    }

    @Test
    fun `labels and actions - pending`() {
        val pending = row(server("echo", McpStatus.PENDING))
        assertEquals("Pending", pending.label)
        assertEquals(emptyList(), pending.actions)
    }

    @Test
    fun `labels and actions - needs-auth`() {
        val needsAuth = row(server("authy", McpStatus.NEEDS_AUTH))
        assertEquals("Needs auth", needsAuth.label)
        assertEquals(listOf(McpAction.AUTHENTICATE, McpAction.DISABLE), needsAuth.actions)
    }

    @Test
    fun `labels and actions - failed`() {
        val failed = row(server("broken", McpStatus.FAILED, error = "Connection closed"))
        assertEquals("Failed", failed.label)
        assertEquals(listOf(McpAction.RECONNECT, McpAction.DISABLE), failed.actions)
        assertEquals("Connection closed", failed.error)
    }

    @Test
    fun `labels and actions - disabled`() {
        val disabled = row(server("echo", McpStatus.DISABLED))
        assertEquals("Disabled", disabled.label)
        assertEquals(listOf(McpAction.ENABLE), disabled.actions)
    }

    @Test
    fun `labels and actions - any other status`() {
        val other = row(server("w", McpStatus.OTHER, statusText = "weird"))
        assertEquals("weird", other.label)
        assertEquals(McpStatus.OTHER, other.status)
        assertEquals(listOf(McpAction.DISABLE), other.actions)
        assertEquals(
            listOf("Enable" to "Enabling…", "Disable" to "Disabling…", "Reconnect" to "Reconnecting…", "Authenticate" to "Starting sign-in…"),
            McpAction.entries.map { it.label to it.caption },
        )
    }

    @Test
    fun `the own server is read-only only with source dynamic`() {
        val own = row(server("tandem", source = "dynamic", tools = listOf(McpTool("edit"))))
        assertTrue(own.readOnly)
        assertEquals(emptyList(), own.actions)
        val user = row(server("tandem", source = "user"))
        assertFalse(user.readOnly)
        assertEquals(listOf(McpAction.DISABLE), user.actions)
        // The own server's name is a parameter.
        val renamed = mcpPanel(McpState(listOf(server("mine"))), ownServer = "mine").rows().single()
        assertTrue(renamed.readOnly)
        assertEquals(emptyList(), renamed.actions)
    }

    @Test
    fun `mcpBadge reads connected over total`() {
        val listed = listed(18)
        assertEquals("1/3", mcpBadge(listed))
        assertNull(mcpBadge(McpState()))
        val model = mcpPanel(listed)
        assertEquals(1, model.connected)
        assertEquals(3, model.total)
        assertFalse(model.hasPending)
        // mcp-status:2: two servers still pending before the first turn.
        val early = mcpPanel(listed(2))
        assertTrue(early.hasPending)
        assertEquals(0, early.connected)
        assertEquals("0/3", mcpBadge(listed(2)))
        // A disabled server counts in the total.
        assertEquals("0/3", mcpBadge(listed(20)))
        assertEquals(McpPanelModel(0, 0, persistentListOf(), hasPending = false, stopped = false), mcpPanel(McpState()))
    }

    @Test
    fun `two entries with the same name keep distinct keys`() {
        val model = panel(
            server("echo"),
            server("echo", McpStatus.FAILED),
            server("echo", source = "user"),
            server("echo"),
        )
        val keys = model.rows().map { it.key }
        assertEquals(listOf("USER/echo", "DYNAMIC/echo", "DYNAMIC/echo#2", "DYNAMIC/echo#3"), keys)
        assertEquals(keys.size, keys.toSet().size)
        // A name that already reads like a repeat's key never collides.
        val tricky = panel(server("echo#2"), server("echo"), server("echo")).rows().map { it.key }
        assertEquals(tricky.size, tricky.toSet().size, tricky.toString())
    }
}
