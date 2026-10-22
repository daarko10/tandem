package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.McpAction
import dev.daarkolabs.tandem.chat.McpPanelModel
import dev.daarkolabs.tandem.chat.McpRowModel
import dev.daarkolabs.tandem.chat.formatDuration
import dev.daarkolabs.tandem.ide.BrowserOpener
import dev.daarkolabs.tandem.protocol.McpAuthStart
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.McpStatus
import dev.daarkolabs.tandem.session.McpAuthOutcome
import dev.daarkolabs.tandem.session.McpAuthWait
import dev.daarkolabs.tandem.session.McpControls
import dev.daarkolabs.tandem.session.McpError
import dev.daarkolabs.tandem.session.McpResult
import dev.daarkolabs.tandem.session.McpState
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MCP panel's controller (docs/superpowers/specs/2026-10-21-mcp-controls-design.md 3.2, 3.3, 4.4, 9.4) against a recording
 * [McpControls] and plain flows. Under `runBlocking` with a 20 ms re-poll (plan deviation: no `kotlinx-coroutines-test` beside the
 * platform's coroutines).
 */
class McpPanelControllerTest {
    private val scope = CoroutineScope(SupervisorJob())

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private val echo = server("echo", McpStatus.CONNECTED)
    private val authy = server("authy", McpStatus.NEEDS_AUTH, source = "user")

    private val servers = MutableStateFlow(McpState(listOf(echo, authy), detailed = false, initCount = 1))
    private val auth = MutableStateFlow<Map<String, McpAuthWait>>(emptyMap())
    private val loginHinted = MutableStateFlow<Set<String>>(emptySet())
    private val status = MutableStateFlow(SessionStatus.IDLE)
    private val controls = RecordingControls()

    private fun controller(
        opener: BrowserOpener = BrowserOpener { },
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): McpPanelController =
        McpPanelController(SESSION, servers, auth, loginHinted, status, controls, opener, scope, dispatcher, pendingRepollMillis = 20)

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private suspend fun until(condition: () -> Boolean) {
        while (!condition()) delay(5)
    }

    private fun McpPanelModel.row(name: String): McpRowModel = groups.flatMap { it.rows }.single { it.name == name }

    /** What the sheet draws at row [name]'s right, from the controller's flows as the sheet reads them. */
    private fun McpPanelController.buttonsOf(name: String): McpRowButtons =
        mcpRowButtons(rows.value.row(name), busy.value, auth.value[name], rows.value.stopped)

    @Test
    fun `refresh on open, on initCount, after pending and never when closed`() = test {
        controls.refreshAnswer = { McpResult.Ok(listOf(echo, authy)) }
        val controller = controller()
        until { controls.count("refresh") == 1 }

        servers.value = servers.value.copy(initCount = 2)
        until { controls.count("refresh") == 2 }

        // An answer with a pending server is asked again, at most 5 times in a row.
        val pending = server("slow", McpStatus.PENDING)
        controls.refreshAnswer = { McpResult.Ok(listOf(echo, pending)) }
        servers.value = servers.value.copy(initCount = 3)
        until { controls.count("refresh") == 8 }
        delay(200)
        assertEquals(8, controls.count("refresh"), "five re-polls at most")

        // An answer without one resets the count.
        controls.refreshAnswer = { McpResult.Ok(listOf(echo)) }
        servers.value = servers.value.copy(initCount = 4)
        until { controls.count("refresh") == 9 }
        controls.refreshAnswer = { McpResult.Ok(listOf(echo, pending)) }
        servers.value = servers.value.copy(initCount = 5)
        until { controls.count("refresh") == 15 }
        delay(200)
        assertEquals(15, controls.count("refresh"))

        controller.close()
        servers.value = servers.value.copy(initCount = 6)
        status.value = SessionStatus.STOPPED
        status.value = SessionStatus.IDLE
        controller.refresh()
        delay(100)
        assertEquals(15, controls.count("refresh"), "a closed panel asks nothing")
    }

    @Test
    fun `a refresh error shows in the title area`() = test {
        controls.refreshAnswer = { McpResult.Failed(McpError.Rejected("Unknown subtype")) }
        val controller = controller()
        until { controller.message.value != null }
        assertEquals("Could not list servers: Unknown subtype", controller.message.value)
        assertTrue(controller.messageRetry.value)

        controls.refreshAnswer = { McpResult.Ok(listOf(echo)) }
        controller.refresh()
        until { controller.message.value == null }
        assertFalse(controller.messageRetry.value)
        assertEquals(false, controller.refreshing.value)
    }

    @Test
    fun `a refresh that finds the session ended shows it without Retry`() = test {
        controls.refreshAnswer = { McpResult.Failed(McpError.Ended) }
        val controller = controller()
        until { controller.message.value != null }
        assertEquals("The session has ended.", controller.message.value)
        assertFalse(controller.messageRetry.value, "a retry cannot help an ended session")
    }

    @Test
    fun `an action whose follow-up refresh fails shows the title-area error`() = test {
        controls.refreshAnswer = { McpResult.Ok(listOf(echo, authy)) }
        val controller = controller()
        until { controls.count("refresh") == 1 }
        controls.refreshAnswer = { McpResult.Failed(McpError.TimedOut(15)) }

        controller.run(McpAction.DISABLE, "echo")

        until { controller.message.value != null }
        assertEquals("Could not list servers: The CLI did not answer within 15 s.", controller.message.value)
        assertTrue(controller.messageRetry.value)
        assertEquals(listOf("toggle echo false"), controls.calls.filter { it.startsWith("toggle") })
        assertNull(controller.busy.value)
    }

    @Test
    fun `an action error on a server gone from the refresh moves to the title area`() = test {
        controls.refreshAnswer = { McpResult.Ok(listOf(echo, authy)) }
        val controller = controller()
        until { controls.count("refresh") == 1 }
        controls.toggleAnswer = McpResult.Failed(McpError.Rejected("Server not found: echo"))
        controls.refreshAnswer = { McpResult.Ok(listOf(authy)) }

        controller.run(McpAction.DISABLE, "echo")

        until { controller.message.value != null }
        assertEquals("echo: Server not found: echo · echo is no longer configured", controller.message.value)
        assertFalse(controller.messageRetry.value, "Retry belongs to a listing that failed (spec 3.2)")
        assertEquals(emptyMap(), controller.rowErrors.value)
    }

    @Test
    fun `a row action error stays in the row with Retry`() = test {
        controls.refreshAnswer = { McpResult.Ok(listOf(echo, authy)) }
        val controller = controller()
        until { controls.count("refresh") == 1 }
        controls.reconnectAnswer = McpResult.Failed(McpError.TimedOut(30))

        controller.run(McpAction.RECONNECT, "authy")

        until { controls.count("refresh") == 2 }
        until { controller.rowErrors.value.isNotEmpty() }
        assertEquals(mapOf("authy" to "The CLI did not answer within 30 s."), controller.rowErrors.value)
        assertEquals(mapOf("authy" to McpAction.RECONNECT), controller.retryActions.value)

        controls.reconnectAnswer = McpResult.Failed(McpError.Ended)
        controller.run(McpAction.RECONNECT, "authy")
        until { controller.rowErrors.value["authy"] == "The session has ended." }
        assertEquals(emptyMap(), controller.retryActions.value, "no Retry once the session has ended")
        delay(50)
        assertEquals(2, controls.count("refresh"), "no refresh after Ended")
    }

    @Test
    fun `a panel opened while the session is stopped refreshes when the process starts`() = test {
        status.value = SessionStatus.STOPPED
        controls.refreshAnswer = { McpResult.Failed(McpError.NotRunning) }
        val controller = controller()
        until { controls.count("refresh") == 1 }
        assertNull(controller.message.value, "the stopped sheet says it itself")
        assertTrue(controller.rows.value.stopped)

        controls.refreshAnswer = { McpResult.Ok(listOf(echo, authy)) }
        status.value = SessionStatus.IDLE

        until { controls.count("refresh") == 2 }
        until { !controller.rows.value.stopped }
    }

    @Test
    fun `a process that stops greys the rows and hides the actions`() = test {
        val controller = controller()
        assertEquals(listOf(McpAction.DISABLE), controller.rows.value.row("echo").actions)

        status.value = SessionStatus.STOPPED

        until { controller.rows.value.stopped }
        assertTrue(controller.rows.value.groups.flatMap { it.rows }.all { it.actions.isEmpty() })
    }

    @Test
    fun `Authenticate opens the url off the main thread`() = test {
        val threads = Collections.synchronizedList(mutableListOf<String>())
        controls.authAnswer = { open ->
            open("http://127.0.0.1:33041/authorize?state=x")
            McpResult.Ok(McpAuthStart("http://127.0.0.1:33041/authorize?state=x"))
        }
        val edt = Executors.newSingleThreadExecutor { Thread(it, "edt") }.asCoroutineDispatcher()
        try {
            val controller = controller(opener = { threads += Thread.currentThread().name }, dispatcher = Dispatchers.Default)
            withContext(edt) { controller.run(McpAction.AUTHENTICATE, "authy") }
            until { threads.isNotEmpty() }
            assertNotEquals("edt", threads.single())
            until { controller.busy.value == null }
        } finally {
            edt.close()
        }
    }

    @Test
    fun `a wait already running shows on a new controller`() = test {
        val wait = McpAuthWait("authy", startedAtMillis = 1_000)
        auth.value = mapOf("authy" to wait)

        val controller = controller()

        assertEquals(wait, controller.auth.value["authy"])
        val caption = authCaption(controller.auth.value.getValue("authy"), nowMillis = 4_000)!!
        assertEquals("Waiting for the browser… · ${formatDuration(3_000)}", caption.text)
        assertTrue(caption.showCancel)
        // What the sheet draws at the row's right: the wait's Cancel, enabled.
        assertEquals(McpRowButtons.Cancel(enabled = true), controller.buttonsOf("authy"))
    }

    @Test
    fun `cancelAuth calls cancelMcpAuth`() = test {
        val controller = controller()
        controller.cancelAuth("authy")
        until { "cancel $SESSION authy" in controls.calls }
    }

    @Test
    fun `other rows stay enabled during the wait`() = test {
        controls.authAnswer = { open ->
            open("http://127.0.0.1:33041/authorize")
            auth.value = mapOf("authy" to McpAuthWait("authy", startedAtMillis = 0))
            McpResult.Ok(McpAuthStart("http://127.0.0.1:33041/authorize"))
        }
        val controller = controller()

        controller.run(McpAction.AUTHENTICATE, "authy")

        until { controls.count("auth") == 1 && controller.busy.value == null }
        assertTrue("authy" in controller.auth.value)
        assertNull(controller.busy.value, "the wait is not a request: no row is busy")
        assertEquals(listOf(McpAction.DISABLE), controller.rows.value.row("echo").actions)
    }

    @Test
    fun `two rows wait at once`() = test {
        // The two connectors of tandem-04: each needs auth, each starts its own wait.
        servers.value = servers.value.copy(servers = listOf(echo, authy, server("other", McpStatus.NEEDS_AUTH, source = "claudeai")))
        auth.value = mapOf("authy" to McpAuthWait("authy", 0), "other" to McpAuthWait("other", 500))

        val controller = controller()

        assertEquals(setOf("authy", "other"), controller.auth.value.keys)
        assertTrue(controller.auth.value.values.all { authCaption(it, 1_000)!!.showCancel })
        // Each waiting row has its own enabled Cancel, and the other row keeps its buttons (spec 3.2, 9.4).
        assertEquals(McpRowButtons.Cancel(enabled = true), controller.buttonsOf("authy"))
        assertEquals(McpRowButtons.Cancel(enabled = true), controller.buttonsOf("other"))
        assertEquals(McpRowButtons.Actions(listOf(McpAction.DISABLE), enabled = true), controller.buttonsOf("echo"))
    }

    @Test
    fun `a request on another row disables every button, a wait's Cancel included`() = test {
        auth.value = mapOf("authy" to McpAuthWait("authy", 0))
        val controller = controller()
        val busy = McpBusy("echo", McpAction.DISABLE, McpAction.DISABLE.caption, since = 0)
        val rows = controller.rows.value

        assertEquals(McpRowButtons.Busy(McpAction.DISABLE.caption), mcpRowButtons(rows.row("echo"), busy, null, stopped = false))
        assertEquals(McpRowButtons.Cancel(enabled = false), mcpRowButtons(rows.row("authy"), busy, auth.value["authy"], stopped = false))
        assertEquals(
            McpRowButtons.Actions(listOf(McpAction.AUTHENTICATE, McpAction.DISABLE), enabled = false),
            mcpRowButtons(rows.row("authy"), busy, null, stopped = false),
        )
        assertEquals(McpRowButtons.None, mcpRowButtons(rows.row("authy"), null, auth.value["authy"], stopped = true))
        assertTrue(!McpRowButtons.Cancel(enabled = false).hasEnabledButton())
        assertTrue(!McpRowButtons.Actions(emptyList(), enabled = true).hasEnabledButton())
    }

    @Test
    fun `loginHinted from the handle survives a new controller`() = test {
        loginHinted.value = setOf("authy")

        val controller = controller()

        assertEquals(listOf(McpAction.AUTHENTICATE, McpAction.RECONNECT, McpAction.DISABLE), controller.rows.value.row("authy").actions)
    }

    @Test
    fun `a needs-auth row offers Reconnect after the terminal hint`() = test {
        val controller = controller()
        assertEquals(listOf(McpAction.AUTHENTICATE, McpAction.DISABLE), controller.rows.value.row("authy").actions)
        controls.authAnswer = {
            // As the manager does: a sign-in that failed to start keeps the terminal hint for the session.
            loginHinted.update { it + "authy" }
            McpResult.Failed(McpError.Rejected("Unknown subtype"))
        }

        controller.run(McpAction.AUTHENTICATE, "authy")

        until { McpAction.RECONNECT in controller.rows.value.row("authy").actions }
        until { "authy" in controller.rowErrors.value }
        assertEquals("Could not start sign-in: Unknown subtype\n${terminalHint("authy")}", controller.rowErrors.value["authy"])
    }

    // spec: sign-in string: Could not start with the terminal hint on a user row
    @Test
    fun `sign-in string - Could not start with the terminal hint on a user row`() = test {
        assertEquals(
            "Could not start sign-in: Unknown subtype\nRun claude mcp login \"authy\" in a terminal, then press Reconnect, or start a new session.",
            authenticateError(authy, McpError.Rejected("Unknown subtype")),
        )
    }

    // spec: sign-in string: Could not start with the new-session hint on a dynamic row
    @Test
    fun `sign-in string - Could not start with the new-session hint on a dynamic row`() = test {
        val dynamic = server("authy", McpStatus.NEEDS_AUTH, source = "dynamic")
        assertEquals(
            "Could not start sign-in: Unknown subtype\nStart a new session to try the sign-in again.",
            authenticateError(dynamic, McpError.Rejected("Unknown subtype")),
        )
    }

    // spec: sign-in string: a link that is not http or https
    @Test
    fun `sign-in string - a link that is not http or https`() = test {
        // Spec 3.3 step 2: mcp_authenticate itself succeeded, so no terminal hint (step 4's is for its own error).
        assertEquals(
            "Could not start sign-in: the sign-in link is not an http(s) URL",
            authenticateError(authy, McpError.Rejected("the sign-in link is not an http(s) URL", linkFailed = true)),
        )
    }

    // spec: sign-in string: no sign-in link
    @Test
    fun `sign-in string - no sign-in link`() {
        assertEquals(
            AuthCaption("Could not start sign-in: the CLI sent no sign-in link", isError = true, showCancel = false),
            authCaption(McpAuthWait("authy", 0, McpAuthOutcome.NoLink), 0),
        )
    }

    // spec: sign-in string: Signed in
    @Test
    fun `sign-in string - Signed in`() {
        val waiting = authCaption(McpAuthWait("authy", 1_000), nowMillis = 66_000)!!
        assertEquals(AuthCaption("Waiting for the browser… · ${formatDuration(65_000)}", isError = false, showCancel = true), waiting)
        val done = authCaption(McpAuthWait("authy", 1_000, McpAuthOutcome.Connected), nowMillis = 66_000)!!
        assertEquals("Signed in", done.text)
        assertEquals(false, done.showCancel)
    }

    // spec: sign-in string: still needs auth after 3 minutes
    @Test
    fun `sign-in string - still needs auth after 3 minutes`() {
        val caption = authCaption(McpAuthWait("authy", 0, McpAuthOutcome.StillNeedsAuth), nowMillis = 180_000)!!
        assertEquals("Still needs auth after 3 minutes. Finish signing in, then press Refresh servers.", caption.text)
        assertEquals(false, caption.showCancel)
    }

    // spec: sign-in string: the authenticate request times out
    @Test
    fun `sign-in string - the authenticate request times out`() = test {
        val error = authenticateError(authy, McpError.TimedOut(30))
        assertEquals("The CLI did not answer within 30 s.\n${terminalHint("authy")}", error)
        assertTrue(!error.startsWith("Could not start sign-in:"))
    }

    // spec: sign-in string: a poll whose last tick failed shows the bare error
    @Test
    fun `sign-in string - a poll whose last tick failed shows the bare error`() = test {
        val rejected = McpAuthWait("authy", 0, McpAuthOutcome.Failed(McpError.Rejected("Unknown subtype")))
        assertEquals(AuthCaption("Unknown subtype", isError = true, showCancel = false), authCaption(rejected, 180_000))
        val timedOut = McpAuthWait("authy", 0, McpAuthOutcome.Failed(McpError.TimedOut(15)))
        assertEquals(AuthCaption("The CLI did not answer within 15 s.", isError = true, showCancel = false), authCaption(timedOut, 180_000))

        auth.value = mapOf("authy" to rejected)
        val controller = controller()
        assertEquals(rejected, controller.auth.value["authy"])
        assertTrue(McpAction.AUTHENTICATE in controller.rows.value.row("authy").actions, "Authenticate is offered again")
    }

    // spec: sign-in string: a server gone from the poll
    @Test
    fun `sign-in string - a server gone from the poll`() = test {
        val controller = controller()
        assertNull(controller.titleNote.value)
        val removed = McpAuthWait("authy", 0, McpAuthOutcome.Removed)

        auth.value = mapOf("authy" to removed)

        until { controller.titleNote.value != null }
        assertEquals(" · authy is no longer configured", controller.titleNote.value)
        assertNull(authCaption(removed, 1_000))
        assertNull(authCaption(McpAuthWait("authy", 0, McpAuthOutcome.Changed(McpStatus.FAILED, "boom")), 1_000))

        auth.value = emptyMap()
        until { controller.titleNote.value == null }
    }

    // spec: sign-in string: the session has ended
    @Test
    fun `sign-in string - the session has ended`() = test {
        assertEquals("The session has ended.", authCaption(McpAuthWait("authy", 0, McpAuthOutcome.Failed(McpError.Ended)), 0)!!.text)
        assertEquals("The session has ended.", authCaption(McpAuthWait("authy", 0, McpAuthOutcome.Failed(McpError.NotRunning)), 0)!!.text)
        assertEquals("The session has ended.", authenticateError(authy, McpError.Ended), "no hint once the session has ended")
    }

    @Test
    fun `a sign-in that cannot start because the session is not running shows nothing in the row`() = test {
        controls.authAnswer = { McpResult.Failed(McpError.NotRunning) }
        val controller = controller()
        controller.run(McpAction.AUTHENTICATE, "authy")
        until { controls.count("auth") == 1 && controller.busy.value == null }
        delay(20)
        assertEquals(emptyMap(), controller.rowErrors.value)
    }

    /** Runs Authenticate on [server]'s row with the CLI answering [error], and returns the row's error text. */
    private suspend fun authenticateError(server: McpServerInfo, error: McpError): String {
        servers.value = McpState(listOf(echo, server), detailed = true, initCount = 1)
        controls.authAnswer = { McpResult.Failed(error) }
        val controller = controller()
        controller.run(McpAction.AUTHENTICATE, server.name)
        until { server.name in controller.rowErrors.value }
        assertNull(controller.busy.value)
        return controller.rowErrors.value.getValue(server.name)
    }

    private fun terminalHint(name: String): String =
        "Run claude mcp login \"$name\" in a terminal, then press Reconnect, or start a new session."

    /** An [McpControls] whose answers each test sets, and which records every call. */
    private class RecordingControls : McpControls {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var refreshAnswer: () -> McpResult<List<McpServerInfo>> = { McpResult.Ok(emptyList()) }

        @Volatile
        var toggleAnswer: McpResult<Unit> = McpResult.Ok(Unit)

        @Volatile
        var reconnectAnswer: McpResult<Unit> = McpResult.Ok(Unit)

        @Volatile
        var authAnswer: suspend (open: suspend (String) -> Unit) -> McpResult<McpAuthStart> = { McpResult.Ok(McpAuthStart(null)) }

        fun count(prefix: String): Int = synchronized(calls) { calls.count { it.startsWith(prefix) } }

        override suspend fun refreshMcp(id: SessionId): McpResult<List<McpServerInfo>> {
            calls += "refresh $id"
            return refreshAnswer()
        }

        override suspend fun toggleMcp(id: SessionId, server: String, enabled: Boolean): McpResult<Unit> {
            calls += "toggle $server $enabled"
            return toggleAnswer
        }

        override suspend fun reconnectMcp(id: SessionId, server: String): McpResult<Unit> {
            calls += "reconnect $server"
            return reconnectAnswer
        }

        override suspend fun startMcpAuth(id: SessionId, server: String, open: suspend (url: String) -> Unit): McpResult<McpAuthStart> {
            calls += "auth $server"
            return authAnswer(open)
        }

        override fun cancelMcpAuth(id: SessionId, server: String) {
            calls += "cancel $id $server"
        }
    }

    private companion object {
        const val SESSION: SessionId = "s1"

        fun server(name: String, status: McpStatus, source: String = "dynamic"): McpServerInfo =
            McpServerInfo(name, status, status.wire, source = source)
    }
}
