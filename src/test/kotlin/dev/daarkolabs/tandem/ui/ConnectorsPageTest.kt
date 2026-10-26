@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import dev.daarkolabs.tandem.chat.ConnectorMenuModel
import dev.daarkolabs.tandem.chat.ConnectorRow
import dev.daarkolabs.tandem.chat.McpAction
import dev.daarkolabs.tandem.chat.McpPanelModel
import dev.daarkolabs.tandem.chat.mcpPanel
import dev.daarkolabs.tandem.ide.BrowserOpener
import dev.daarkolabs.tandem.protocol.McpAuthStart
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.session.McpAuthWait
import dev.daarkolabs.tandem.session.McpControls
import dev.daarkolabs.tandem.session.McpResult
import dev.daarkolabs.tandem.session.McpStart
import dev.daarkolabs.tandem.session.McpState
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionStatus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `+` menu's Connectors page (desktop-parity spec 17.2, 9.8): a row's semantics and click, the page's controller and its note. The
 * rows are synthetic, as `ConnectorMenuTest`'s: no fixture has a claude.ai connector (spec 11 item 20).
 */
@TestApplication
class ConnectorsPageTest {
    private val alpha = ConnectorRow("claude.ai Alpha", "claude.ai Alpha", "Alpha", on = true, status = null, caption = null, error = null, switchable = true)
    private val beta = ConnectorRow("claude.ai Beta", "claude.ai Beta", "Beta", on = true, status = "Needs auth", caption = null, error = "boom", switchable = true)
    private val gamma = ConnectorRow("claude.ai Gamma", "claude.ai Gamma", "Gamma", on = false, status = null, caption = null, error = null, switchable = false)
    private val delta = ConnectorRow("claude.ai Delta", "claude.ai Delta", "Delta", on = false, status = null, caption = null, error = null, switchable = true)

    // spec: a connector row is a switch named for its connector
    @Test
    fun `a connector row is a switch named for its connector`() {
        assertEquals(RowSemantics("Alpha connector", "On", true), connectorRowSemantics(alpha))
        assertEquals(RowSemantics("Beta connector, Needs auth, boom", "On", true), connectorRowSemantics(beta))
        assertEquals(RowSemantics("Gamma connector", "Off", false), connectorRowSemantics(gamma))
        val busy = beta.copy(caption = "Disabling…", on = false, error = null, switchable = false)
        assertEquals(RowSemantics("Beta connector, Disabling…", "Off", false), connectorRowSemantics(busy))
    }

    // spec: a click holds the menu open and toggles once
    @Test
    fun `a click holds the menu open and toggles once`() {
        val menu = PagedMenuState().apply { openAt(PLUS_CONNECTORS) }
        val runs = mutableListOf<Pair<McpAction, String>>()
        onConnectorRow(alpha, menu) { action, name -> runs += action to name }
        assertEquals(listOf(McpAction.DISABLE to "claude.ai Alpha"), runs)
        assertFalse(menu.dismiss(InputMode.Touch, anchorHovered = false), "the click's close request is refused")
        assertTrue(menu.open)
        assertEquals(PLUS_CONNECTORS, menu.page)

        onConnectorRow(delta, menu) { action, name -> runs += action to name }
        assertEquals(McpAction.ENABLE to "claude.ai Delta", runs.last())
        onConnectorRow(gamma, menu) { action, name -> runs += action to name }
        assertEquals(2, runs.size, "a row that cannot switch runs nothing")
    }

    // spec: the drawn page's rows (D6a's ImageComposeScene composes Jewel's PopupMenu)
    @Test
    fun `the drawn page's rows`() = timeoutRunBlocking(kotlin.time.Duration.parse("60s")) {
        val menu = PagedMenuState().apply { openAt(PLUS_CONNECTORS) }
        val runs = mutableListOf<Pair<McpAction, String>>()
        val page = ConnectorsPage(ConnectorMenuModel(persistentListOf(alpha, gamma), null)) { action, name -> runs += action to name }
        ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Reference Cleaner", "Java2D Disposer")
        withContext(Dispatchers.EDT) {
            val scene = ImageComposeScene(400, 300, Density(1f)) {
                SwingBridgeTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.align(Alignment.BottomStart).size(24.dp)) {
                            PlusMenu(
                                menu,
                                persistentListOf(),
                                true,
                                240.dp to 360.dp,
                                maxHeight = 268.dp,
                                gapPx = 8,
                                onDismissRequest = { mode -> menu.dismiss(mode, anchorHovered = false) },
                                actions = PlusMenuActions({}, {}, {}, {}, {}),
                                connectors = page,
                            )
                        }
                    }
                }
            }
            try {
                scene.render()
                scene.render(16_000_000L)
                val a = node(scene, "Alpha connector")
                assertEquals(Role.Switch, a.config.getOrNull(SemanticsProperties.Role))
                assertEquals("On", a.config.getOrNull(SemanticsProperties.StateDescription))
                assertNull(a.config.getOrNull(SemanticsProperties.Disabled))
                val g = node(scene, "Gamma connector")
                assertEquals(Role.Switch, g.config.getOrNull(SemanticsProperties.Role))
                assertEquals("Off", g.config.getOrNull(SemanticsProperties.StateDescription))
                assertTrue(g.config.contains(SemanticsProperties.Disabled), "a row that cannot switch is disabled")

                val center = a.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Move, center)
                scene.sendPointerEvent(PointerEventType.Press, center)
                scene.sendPointerEvent(PointerEventType.Release, center)
                scene.render(100_000_000L)
                scene.render(200_000_000L)
                assertEquals(listOf(McpAction.DISABLE to "claude.ai Alpha"), runs)
                assertTrue(menu.open, "the switch's click keeps the menu open")
                assertEquals(PLUS_CONNECTORS, menu.page)
            } finally {
                scene.close()
            }
        }
    }

    // spec: the page's controller starts a stopped session once (17.2)
    @Test
    fun `the page's controller starts a stopped session once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val ui = ChatUiState()
            ui.openMcpPanel()
            val controls = RecordingControls()
            val controller = connectorPageController(
                "s1",
                MutableStateFlow(McpState(emptyList(), detailed = false)),
                MutableStateFlow<Map<String, McpAuthWait>>(emptyMap()),
                MutableStateFlow<Set<String>>(emptySet()),
                MutableStateFlow(SessionStatus.STOPPED),
                controls,
                BrowserOpener { },
                scope,
                endReason = { null },
            )
            withTimeout(10_000) { while (controls.starts() == 0) delay(5) }
            delay(100)
            assertEquals(1, controls.starts())
            assertTrue(ui.takeMcpStartOnOpen(), "the page leaves the panel's one-shot flag alone")
            controller.close()
        } finally {
            scope.cancel()
        }
    }

    // spec: the page's notice is the controller's message, else its start's
    @Test
    fun `the page's notice is the controller's message, else its start's`() {
        val live: McpPanelModel = mcpPanel(McpState(emptyList(), detailed = true), live = true)
        val stopped: McpPanelModel = mcpPanel(McpState(emptyList(), detailed = true), live = false)
        val message = "Could not list servers: x"
        for (start in listOf(McpPanelStart.None, McpPanelStart.Starting, McpPanelStart.NoRoom(1), McpPanelStart.Failed("boom"))) {
            assertEquals(message, connectorPageNotice(live, start, message))
        }
        assertEquals("Starting the session…", connectorPageNotice(live, McpPanelStart.Starting, null))
        assertEquals(mcpPanelNotice(live, McpPanelStart.NoRoom(1), null)!!.text, connectorPageNotice(live, McpPanelStart.NoRoom(1), null))
        assertEquals(mcpPanelNotice(live, McpPanelStart.Failed("boom"), null)!!.text, connectorPageNotice(live, McpPanelStart.Failed("boom"), null))
        assertNull(connectorPageNotice(live, McpPanelStart.None, null))
        assertNull(connectorPageNotice(stopped, McpPanelStart.None, null))
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { owner -> all(owner.rootSemanticsNode) }

    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)

    private fun node(scene: ImageComposeScene, description: String): SemanticsNode =
        nodes(scene).first { description in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    /** The MCP requests a controller makes, recorded; every one answers at once. */
    private class RecordingControls : McpControls {
        private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun starts(): Int = synchronized(calls) { calls.count { it == "start" } }

        override suspend fun refreshMcp(id: SessionId): McpResult<List<McpServerInfo>> = McpResult.Ok(emptyList())

        override suspend fun toggleMcp(id: SessionId, server: String, enabled: Boolean): McpResult<Unit> = McpResult.Ok(Unit)

        override suspend fun reconnectMcp(id: SessionId, server: String): McpResult<Unit> = McpResult.Ok(Unit)

        override suspend fun startMcpAuth(id: SessionId, server: String, open: suspend (url: String) -> Unit): McpResult<McpAuthStart> =
            McpResult.Ok(McpAuthStart(null))

        override fun cancelMcpAuth(id: SessionId, server: String) = Unit

        override val knownMcpServers: StateFlow<List<McpServerInfo>> = MutableStateFlow(emptyList())

        override suspend fun startForMcp(id: SessionId, overCap: Boolean): McpStart {
            calls += "start"
            return McpStart.NoRoom(1)
        }
    }
}
