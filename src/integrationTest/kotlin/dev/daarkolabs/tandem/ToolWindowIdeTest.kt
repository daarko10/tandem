package dev.daarkolabs.tandem

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ActionManager
import com.intellij.driver.sdk.closeAndDisableAllBalloonNotifications
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import dev.daarkolabs.tandem.session.transcript.TranscriptFolder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Installs the built plugin ZIP into a real IDE (the release we compile against) and uses the Tandem tool window
 * the way a person does: create sessions, type a prompt, press Send, answer permission prompts, press Stop, pick a
 * permission mode and a model below the prompt, switch, rename and delete sessions in the sidebar, with the mouse and
 * the keyboard (the IDE's own context menu key included).
 * The tool window keeps the width the IDE gives it, too narrow to dock the sidebar, so the sidebar opens over the chat; one test
 * widens it to dock the sidebar.
 * The `claude` it talks to is `src/test/resources/fake-claude.py`, which replays a recorded fixture in step with what
 * the UI sends and logs every frame it receives, so each test checks both the rendered UI and the protocol traffic.
 *
 * Unlike the platform tests, the plugin is loaded by its own plugin classloader here, as it is for users.
 * Run with `xvfb-run ./gradlew integrationTest` when there is no display.
 */
class ToolWindowIdeTest {
    private val pluginZip = Path.of(System.getProperty("tandem.pluginZip"))
    private val fakeClaude = System.getProperty("tandem.fakeClaude")
    private val ideVersion = System.getProperty("tandem.ideVersion")

    @Test
    fun `a typed prompt is sent and the streamed reply is rendered`() {
        val sent = runChat("typedPrompt", fixtures = listOf("plain-reply")) {
            newSession()
            // Send with nothing typed sends nothing: the one prompt the CLI gets is the typed one (asserted below).
            iconButton("Send").click(Point(10, 10))
            // Shift+Enter types a newline (spec 4.15): 'a', Shift+Enter, 'b' leaves three characters, not 'ab'. Then they are erased.
            promptBox().click(Point(20, 20))
            waitFor("prompt box focused", 30.seconds) { ui.x("$PROMPT_BOX[@focused='true']").present() }
            promptBox().keyboard { typeText("a", 20) }
            promptBox().keyboard { hotKey(KeyEvent.VK_SHIFT, KeyEvent.VK_ENTER) }
            promptBox().keyboard { typeText("b", 20) }
            val aNewlineB = "string-length(@editabletext)=3 and starts-with(@editabletext, 'a') and substring(@editabletext, 3)='b'"
            waitFor("Shift+Enter typed a newline", 30.seconds) { ui.x("$PROMPT_BOX[$aNewlineB]").present() }
            repeat(3) { promptBox().keyboard { key(KeyEvent.VK_BACK_SPACE) } }
            waitFor("prompt box emptied", 30.seconds) { ui.x("$PROMPT_BOX[@editabletext='']").present() }
            typePrompt("Reply with the single word: pong")
            waitForText("Reply with the single word: pong")
            waitForText("pong")
            waitFor("turn footer", 1.minutes) { ui.x("//div[@class='ComposeNode' and starts-with(@visible_text, 'Worked for')]").present() }
            waitFor("model shown in the model picker", 1.minutes) { picker("Model", "Default model").present() }
            waitFor("Send is offered again", 1.minutes) { iconButton("Send").present() }
        }
        val prompt = sent.single { it.type == "user" }
        assertTrue("Reply with the single word: pong" in prompt.toString(), prompt.toString())
    }

    @Test
    fun `the permission card names the command and answers from the keyboard`() {
        val sent = runChat("permissions", fixtures = listOf("permission")) {
            newSession()
            typePrompt("Create the fixture file")
            waitForText("Allow Claude to Run touch tandem-fixture.txt?")
            // The focus is on Allow once, then Enter (slice-B review: card focus).
            answerCard()
            // The card held the focus, so it gives it back to the composer when it closes (spec 4.12).
            waitFor("focus back in the prompt box", 30.seconds) { ui.x("$PROMPT_BOX[@focused='true']").present() }
            waitFor("tool row", 2.minutes) { toolRow("Ran Create empty fixture file").present() }
            // The first turn has ended, so Send is back for the next prompt.
            waitForText("done.")
            typePrompt("Now remove it")
            waitForText("Allow Claude to Run rm tandem-fixture.txt?")
            iconButton("Deny").click(Point(10, 10))
            waitFor("denied row", 2.minutes) { toolRow("Remove fixture file · Denied").present() }
            waitForText("denied.")
        }
        val answers = sent.filter { it.type == "control_response" }.map { it["response"]!!.jsonObject }
        assertEquals(listOf("<request_id-26>", "<request_id-63>"), answers.map { it["request_id"]!!.jsonPrimitive.content })
        assertEquals(listOf("allow", "deny"), answers.map { it["response"]!!.jsonObject["behavior"]!!.jsonPrimitive.content })
    }

    @Test
    fun `a question card answers with the picked option`() {
        val sent = runChat("question", fixtures = listOf("ask-single")) {
            newSession()
            typePrompt("Ask me a colour")
            waitForText("Which colour?")
            option("Blue").click(Point(10, 10))
            iconButton("Submit answers").click(Point(10, 10))
            // The card's own option is a "Blue" text node too.
            waitFor("question card closed", 2.minutes) {
                !ui.x("//div[@class='ComposeNode' and @contentdescription='Question from Claude']").present()
            }
            // The model's reply, ask-single:31.
            waitForText("Blue")
            waitFor("Questions row", 2.minutes) { toolRow("Questions").present() }
        }
        val answer = sent.filter { it.type == "control_response" }.map { it["response"]!!.jsonObject }
            .single { it["request_id"]!!.jsonPrimitive.content == "<request_id-27>" }["response"]!!.jsonObject
        assertEquals("allow", answer["behavior"]!!.jsonPrimitive.content)
        assertEquals("Blue", answer["updatedInput"]!!.jsonObject["answers"]!!.jsonObject["Which colour?"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Stop interrupts a running turn`() {
        val sent = runChat("stop", fixtures = listOf("interrupt")) {
            newSession()
            typePrompt("Count from 1 to 300")
            waitFor("Stop offered while the turn runs", 1.minutes) { iconButton("Stop").present() }
            iconButton("Stop").click(Point(10, 10))
            waitForText("Interrupted · What should Claude do instead?")
            waitFor("Send is offered again", 1.minutes) { iconButton("Send").present() }
        }
        val requests = sent.filter { it.type == "control_request" }.map { it["request"]!!.jsonObject["subtype"]!!.jsonPrimitive.content }
        assertEquals(listOf("initialize", "interrupt"), requests)
    }

    @Test
    fun `the pickers send set_permission_mode and set_model`() {
        val log = Files.createTempFile("tandem-sent", ".ndjson")
        val sent = runChat("pickers", fixtures = listOf("permission-mode"), sentLog = log) {
            newSession()
            typePrompt("Reply with the single word: one")
            waitForText("one")
            // From the keyboard the open list only moves its highlight: Down goes to Accept edits, and Escape picks nothing.
            ui.x("//div[@class='ComposeNode' and @visible_text='Default']").click(Point(10, 10))
            waitFor("the permission modes listed", 30.seconds) { menuItem("Plan").present() }
            ui.keyboard { down() }
            ui.keyboard { escape() }
            waitFor("Escape closed the list, Default kept", 30.seconds) {
                !menuItem("Plan").present() && picker("Permission mode", "Default").present()
            }
            // Down twice passes Accept edits on the way to Plan, and Enter picks Plan: one request.
            picker("Permission mode", "Default").click(Point(10, 10))
            waitFor("the permission modes listed again", 30.seconds) { menuItem("Plan").present() }
            ui.keyboard {
                down()
                down()
                enter()
            }
            waitFor("Enter closed the list on Plan", 30.seconds) {
                !menuItem("Accept edits").present() && picker("Permission mode", "Plan").present()
            }
            typePrompt("Reply with the single word: two")
            waitForText("two")
            waitFor("the picker shows plan", 1.minutes) { picker("Permission mode", "Plan").present() }
            // A pick in the prompt bar is the project's last one: the new-session popup starts from it. Escape closes the popup
            // and creates nothing.
            newSessionButton().click(Point(10, 10))
            // The prompt bar's picker shows Plan too: the popup's is the second one.
            waitFor("the popup, on the last pick", 30.seconds) {
                button("Create").present() &&
                    ui.xx("//div[@class='ComposeNode' and @role='DropdownList' and @contentdescription='Permission mode' and @visible_text='Plan']")
                        .list().size == 2
            }
            ui.keyboard { escape() }
            waitFor("Escape closed the popup", 30.seconds) { !button("Create").present() }
            ui.x("//div[@class='ComposeNode' and @visible_text='Default model']").click(Point(10, 10))
            menuItem(modelName(1)).click(Point(10, 10))
            // The fake CLI answers set_model with the next acknowledgement it recorded, set_permission_mode's acceptEdits one, and
            // that one's status line: so the session ends in Accept edits. Wait for the frame itself before the IDE closes.
            waitFor("set_model reached the CLI", 30.seconds) { "\"subtype\":\"set_model\"" in log.readText() }
        }
        val requests = sent.filter { it.type == "control_request" }.map { it["request"]!!.jsonObject }
        val modes = requests.filter { it["subtype"]!!.jsonPrimitive.content == "set_permission_mode" }
            .map { it["mode"]!!.jsonPrimitive.content }
        assertEquals(listOf("plan"), modes, "Escape picked nothing, and Enter picked Plan alone")
        val setModel = requests.single { it["subtype"]!!.jsonPrimitive.content == "set_model" }
        assertEquals("sonnet", setModel["model"]!!.jsonPrimitive.content)
        assertEquals(1, sent.count { it.type == "__spawn__" }, "the popup closed with Escape started no session")
    }

    @Test
    fun `several sessions live side by side in the sidebar`() {
        val configDir = Files.createTempDirectory("tandem-config")
        val project = newProject()
        val seeded = "33333333-3333-4333-8333-333333333333"
        // A session from an earlier run, in the CLI's transcript folder for the project: the sidebar lists it as "Seeded".
        val transcript = Path.of(fakeClaude).resolveSibling("fixtures/transcripts/history-reply.jsonl").readText() +
            """{"type":"custom-title","customTitle":"Seeded","sessionId":"$seeded"}""" + "\n"
        configDir.resolve("projects").resolve(TranscriptFolder.key(project.toRealPath().toString())).createDirectories()
            .resolve("$seeded.jsonl").writeText(transcript)
        // The fake CLI holds every permission request back until this file exists.
        val gate = Files.createTempDirectory("tandem-gate").resolve("open")
        val pong = "Reply with the single word: pong"

        val sent = runChat(
            "sidebar",
            fixtures = listOf("plain-reply", "permission", "plain-reply"),
            configDir = configDir,
            gateFile = gate,
            project = project,
        ) {
            newSession()
            typePrompt(pong)
            waitForText("pong")

            newSession()
            typePrompt("Create the fixture file")
            // Switch away before the permission request arrives, then let the fake CLI send it. The window is too narrow to
            // dock the sidebar, so the toggle opens it over the chat, and picking a row closes it.
            openSidebar()
            row(pong).click(Point(10, 10))
            waitFor("sidebar closed after picking a row, prompt box focused", 30.seconds) {
                !row(pong).present() && ui.x("$PROMPT_BOX[@focused='true']").present()
            }
            gate.createFile()
            openSidebar()
            waitFor("badge on the background row", 1.minutes) { row("Create the fixture file", "needs you").present() }
            row("Create the fixture file").click(Point(10, 10))
            waitForText("Allow Claude to Run touch tandem-fixture.txt?")
            iconButton("Allow once").click(Point(10, 10))
            waitForText("done.")
            openSidebar()
            waitFor("badge cleared", 30.seconds) { row("Create the fixture file").present() && !row("needs you").present() }

            // Rename from the "⋯" a hovered row shows. A title field open for one session never carries over to another:
            // with the pong session's field still open, Rename on another row's menu shows that row's title.
            row(pong).moveMouse(Point(10, 10))
            iconButton("Session actions").click(Point(10, 10))
            menuItem("Rename").click(Point(10, 10))
            waitFor("title field open", 30.seconds) { focusedEditable(pong).present() }
            openSidebar()
            row("Create the fixture file").rightClick(Point(10, 10))
            menuItem("Rename").click(Point(10, 10))
            waitFor("the other title in the field", 30.seconds) { focusedEditable("Create the fixture file").present() }
            ui.keyboard {
                typeText("Alpha", 20)
                enter()
            }
            openSidebar()
            waitFor("row renamed", 30.seconds) { row("Alpha").present() }
            ui.keyboard { escape() }
            waitFor("Escape closes the sidebar and focuses the toggle", 30.seconds) {
                !row("Alpha").present() && focusedIconButton("Show sessions").present()
            }

            // From the keyboard: the sidebar opens with the filter focused, Tab goes to the first row and then to its "⋯",
            // whose menu Enter opens and Escape closes.
            openSidebar()
            waitFor("filter focused", 30.seconds) { focusedEditable("").present() }
            ui.keyboard { tab() }
            waitFor("Tab focuses the first row", 30.seconds) { ui.x("$ROW[@focused='true']").present() }
            ui.keyboard { tab() }
            waitFor("its ⋯ focused", 30.seconds) { focusedIconButton("Session actions").present() }
            ui.keyboard { enter() }
            waitFor("menu opened from the ⋯", 30.seconds) { menuItem("Copy resume command").present() }
            ui.keyboard { escape() }
            waitFor("menu closed, sidebar still open", 30.seconds) { !menuItem("Copy resume command").present() && row("Alpha").present() }
            ui.keyboard { escape() }
            waitFor("Escape closes the sidebar", 30.seconds) { !row("Alpha").present() }

            // The dimmed chat under the sidebar is out of the Tab order: Shift+Tab from "New session" goes to the header's "+",
            // and Tab from there back to "New session".
            openSidebar()
            waitFor("filter focused for Shift+Tab", 30.seconds) { focusedEditable("").present() }
            ui.keyboard { hotKey(KeyEvent.VK_SHIFT, KeyEvent.VK_TAB) }
            waitFor("New session focused", 30.seconds) { focusedButton("New session").present() }
            ui.keyboard { hotKey(KeyEvent.VK_SHIFT, KeyEvent.VK_TAB) }
            waitFor("Shift+Tab passes the chat by, to the header's +", 30.seconds) { focusedIconButton("New session").present() }
            ui.keyboard { tab() }
            waitFor("Tab passes the chat by, back to New session", 30.seconds) { focusedButton("New session").present() }
            ui.keyboard { escape() }
            waitFor("Escape closes the sidebar once more", 30.seconds) { !row("Alpha").present() }

            // Two rows left by the filter, newest first. Down and Up move between them, End and Home go to the last and the
            // first. The IDE's context menu key opens the focused row's menu: Close only for the open session. Delete asks and
            // Escape takes it back. Down and Enter show the second row's session, with the focus in its prompt box. The seeded
            // session's history comes from its transcript; opening it resumes it.
            val showPopupMenu = service<ActionManager>().getKeyboardShortcut("ShowPopupMenu")?.getFirstKeyStroke()
            assertEquals(KeyEvent.VK_CONTEXT_MENU, showPopupMenu?.getKeyCode(), "the context menu key runs ShowPopupMenu")
            openSidebar()
            waitFor("filter focused again", 30.seconds) { focusedEditable("").present() }
            ui.keyboard { typeText("e", 20) }
            waitFor("rows filtered", 30.seconds) { row(pong).present() && row("Seeded").present() && !row("Alpha").present() }
            ui.keyboard { tab() }
            waitFor("Tab focuses the first filtered row", 30.seconds) { focusedRow(pong).present() }
            ui.keyboard { down() }
            waitFor("Down focuses the second row", 30.seconds) { focusedRow("Seeded").present() }
            ui.keyboard { up() }
            waitFor("Up focuses the first row again", 30.seconds) { focusedRow(pong).present() }
            ui.keyboard { key(KeyEvent.VK_END) }
            waitFor("End focuses the last row", 30.seconds) { focusedRow("Seeded").present() }
            contextMenuKey()
            waitFor("the seeded row's menu, without Close", 30.seconds) {
                menuItem("Copy resume command").present() && !menuItem("Close").present()
            }
            ui.keyboard { escape() }
            waitFor("menu closed", 30.seconds) { !menuItem("Copy resume command").present() && focusedRow("Seeded").present() }
            ui.keyboard { key(KeyEvent.VK_HOME) }
            waitFor("Home focuses the first row", 30.seconds) { focusedRow(pong).present() }
            contextMenuKey()
            waitFor("the open session's menu, with Close", 30.seconds) { menuItem("Close").present() }
            ui.keyboard { escape() }
            waitFor("menu closed again", 30.seconds) { !menuItem("Close").present() && focusedRow(pong).present() }
            ui.keyboard { key(KeyEvent.VK_DELETE) }
            waitFor("Delete asks", 30.seconds) { button("Delete").present() }
            ui.keyboard { escape() }
            waitFor("Escape takes it back", 30.seconds) { !button("Delete").present() && focusedRow(pong).present() }
            ui.keyboard { down() }
            waitFor("Down focuses the second row again", 30.seconds) { focusedRow("Seeded").present() }
            ui.keyboard { enter() }
            waitFor("sidebar closed after Enter, prompt box focused", 30.seconds) {
                !row("Seeded").present() && ui.x("$PROMPT_BOX[@focused='true']").present()
            }
            waitForText("pong")
            openSidebar()
            waitFor("seeded row selected", 30.seconds) { ui.x("$ROW[@selected='true' and contains(@text, 'Seeded')]").present() }

            row("Seeded").rightClick(Point(10, 10))
            menuItem("Delete…").click(Point(10, 10))
            clickButton("Delete")
            waitFor("seeded row gone", 30.seconds) { !row("Seeded").present() && row("Alpha").present() }
        }

        val spawns = sent.filter { it.type == "__spawn__" }.map { spawn -> spawn["argv"]!!.jsonArray.map { it.jsonPrimitive.content } }
        assertEquals(3, spawns.size, spawns.toString())
        assertEquals(2, spawns.count { "--session-id" in it }, spawns.toString())
        assertEquals(1, spawns.count { "--resume" in it }, spawns.toString())
        assertTrue(sent.any { it["request"]?.jsonObject?.get("subtype")?.jsonPrimitive?.content == "rename_session" })
        assertFalse(configDir.walk().any { it.fileName.toString() == "$seeded.jsonl" }, "the seeded transcript is deleted")
    }

    @Test
    fun `a wide tool window docks the sidebar, which stays collapsed in the next IDE run`() {
        val configDir = Files.createTempDirectory("tandem-config")
        val project = newProject()
        val seeded = "44444444-4444-4444-8444-444444444444"
        val transcript = Path.of(fakeClaude).resolveSibling("fixtures/transcripts/history-reply.jsonl").readText() +
            """{"type":"custom-title","customTitle":"Seeded","sessionId":"$seeded"}""" + "\n"
        configDir.resolve("projects").resolve(TranscriptFolder.key(project.toRealPath().toString())).createDirectories()
            .resolve("$seeded.jsonl").writeText(transcript)
        val pong = "Reply with the single word: pong"
        val sentLog = Files.createTempFile("tandem-sent", ".ndjson")
        val context = ideContext("docked", listOf("plain-reply"), configDir, null, project, sentLog)

        context.runTandem("docked") {
            widenToolWindow()
            waitFor("the sidebar docked beside the chat", 30.seconds) {
                filterBox().present() && iconButton("Hide sessions").present() && row("Seeded").present()
            }

            // A session from the docked sidebar's "New session".
            clickButton("New session")
            clickButton("Create")
            typePrompt(pong)
            waitForText("pong")
            waitFor("its row", 30.seconds) { row(pong).present() }

            // Rename opens the title field; Escape leaves the title as it was. (A double-click on the title opens it too, but the
            // robot's double click does not reach Compose as one under Xvfb.)
            row(pong).rightClick(Point(10, 10))
            menuItem("Rename").click(Point(10, 10))
            waitFor("title field open", 30.seconds) { focusedEditable(pong).present() }
            ui.keyboard {
                typeText("Zeta", 20)
                escape()
            }
            waitFor("title kept", 30.seconds) { !focusedEditable("Zeta").present() && row(pong).present() && !row("Zeta").present() }

            // Fork the seeded session: the fork opens, selected, under the original's title.
            row("Seeded").rightClick(Point(10, 10))
            menuItem("Fork").click(Point(10, 10))
            waitFor("the fork listed", 30.seconds) { row("Seeded (fork)").present() }

            // Delete… from a right click while typing a prompt asks in the row, and leaves the focus in the prompt box.
            val original = "$ROW[contains(@text, 'Seeded') and not(contains(@text, '(fork)'))]"
            promptBox().click(Point(20, 20))
            waitFor("prompt box focused before the right click", 30.seconds) { ui.x("$PROMPT_BOX[@focused='true']").present() }
            ui.x(original).rightClick(Point(10, 10))
            menuItem("Delete…").click(Point(10, 10))
            waitFor("the question, the prompt box still focused", 30.seconds) {
                button("Delete").present() && ui.x("$PROMPT_BOX[@focused='true']").present()
            }
            clickButton("Cancel")
            waitFor("question taken back by Cancel", 30.seconds) { !button("Delete").present() }

            // From the keyboard: End focuses the last row, the original, and the context menu key opens its menu. Delete… there
            // warns that the open fork has no transcript of its own, and Cancel takes the focus from the "⋯" that goes away.
            // Escape takes the question back, and the row has the focus again.
            filterBox().click(Point(10, 10))
            waitFor("filter focused for the keyboard", 30.seconds) { focusedEditable("").present() }
            ui.keyboard {
                tab()
                key(KeyEvent.VK_END)
            }
            waitFor("the original focused", 30.seconds) { ui.x("$original[@focused='true']").present() }
            contextMenuKey()
            menuItem("Delete…").click(Point(10, 10))
            waitFor("the question, with the fork warning, Cancel focused", 30.seconds) {
                ui.x("//div[@class='ComposeNode' and contains(@visible_text, 'An open fork of it has no transcript')]").present() &&
                    focusedButton("Cancel").present()
            }
            ui.keyboard { escape() }
            waitFor("question taken back, row focused", 30.seconds) {
                !button("Delete").present() && ui.x("$original[@focused='true']").present()
            }

            // The context menu key with the focus outside the rows opens no menu, and the next click goes through.
            filterBox().click(Point(10, 10))
            waitFor("filter focused", 30.seconds) { focusedEditable("").present() }
            contextMenuKey()
            row(pong).click(Point(10, 10))
            waitFor("no menu, and the click selected the row", 30.seconds) {
                !menuItem("Copy resume command").present() && ui.x("$ROW[@selected='true' and contains(@text, '$pong')]").present()
            }

            // The "x" on the hovered open row closes it. The fake CLI writes no transcript, so its row goes, and the fork, its
            // neighbour, is selected.
            row(pong).moveMouse(Point(10, 10))
            iconButton("Close session").click(Point(8, 8))
            waitFor("closed, the fork selected", 30.seconds) {
                !row(pong).present() && ui.x("$ROW[@selected='true' and contains(@text, 'Seeded (fork)')]").present()
            }

            // Deleting the focused first row, the fork, hands the focus to the next one.
            filterBox().click(Point(10, 10))
            waitFor("filter focused for the delete", 30.seconds) { focusedEditable("").present() }
            ui.keyboard { tab() }
            waitFor("the fork focused", 30.seconds) { ui.x("$ROW[@focused='true' and contains(@text, 'Seeded (fork)')]").present() }
            ui.keyboard { key(KeyEvent.VK_DELETE) }
            clickButton("Delete")
            waitFor("the fork gone, the next row focused", 30.seconds) {
                !row("Seeded (fork)").present() && ui.x("$original[@focused='true']").present()
            }

            iconButton("Hide sessions").click(Point(10, 10))
            waitFor("sidebar collapsed", 30.seconds) { !filterBox().present() && iconButton("Show sessions").present() }
        }

        context.runTandem("docked-again") {
            widenToolWindow()
            waitFor("the sidebar still collapsed", 30.seconds) { iconButton("Show sessions").present() && !filterBox().present() }
            iconButton("Show sessions").click(Point(10, 10))
            waitFor("docked again", 30.seconds) { filterBox().present() && row("Seeded").present() && iconButton("Hide sessions").present() }
        }
    }

    /** Makes the tool window wide enough to dock the sidebar: the IDE gives it less than 500 dp. */
    private fun Driver.widenToolWindow() {
        withContext(OnDispatcher.EDT) {
            val window = service<ToolWindowManagerRef>(singleProject()).getToolWindow("Tandem") ?: error("no Tandem tool window")
            val width = window.getComponent().getWidth()
            if (width < WIDE) window.stretchWidth(WIDE - width)
        }
    }

    /**
     * The display name of model [index] in the CLI's `initialize` response recorded in `fixtures/permission-mode.ndjson`, next to the
     * fake CLI: the first control_response that lists models.
     */
    private fun modelName(index: Int): String {
        val fixture = Path.of(fakeClaude).resolveSibling("fixtures/permission-mode.ndjson")
        val models = fixture.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.type == "control_response" }
            .firstNotNullOf { it["response"]?.jsonObject?.get("response")?.jsonObject?.get("models")?.jsonArray }
        return models[index].jsonObject["displayName"]!!.jsonPrimitive.content
    }

    /** A new project folder with one file. */
    private fun newProject(): Path {
        val project = Files.createTempDirectory("tandem-ide-test").resolve("demo").createDirectories()
        project.resolve("README.md").writeText("# Demo\n")
        return project
    }

    /**
     * Starts the IDE on [project] with the plugin and the fake CLI, opens the tool window, runs [script], and returns the frames
     * the plugin wrote to the CLI's stdin, each process's `__spawn__` line included. The Nth process the plugin starts replays
     * fixture N of [fixtures] (the last one once they run out). [configDir] becomes the CLI's config directory
     * (`CLAUDE_CONFIG_DIR`), and the fake CLI holds every permission request back until [gateFile] exists. The frames go to
     * [sentLog] as the fake CLI reads them, so that [script] can wait for one.
     */
    private fun runChat(
        name: String,
        fixtures: List<String>,
        configDir: Path? = null,
        gateFile: Path? = null,
        project: Path = newProject(),
        sentLog: Path = Files.createTempFile("tandem-sent", ".ndjson"),
        script: Driver.() -> Unit,
    ): List<JsonObject> {
        ideContext(name, fixtures, configDir, gateFile, project, sentLog).runTandem(name, script)
        return if (sentLog.exists()) sentLog.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject } else emptyList()
    }

    /** The IDE [runChat] starts, with the plugin and the fake CLI; each [runTandem] on it starts it again, with what it kept. */
    private fun ideContext(
        name: String,
        fixtures: List<String>,
        configDir: Path?,
        gateFile: Path?,
        project: Path,
        sentLog: Path,
    ): IDETestContext =
        Starter.newContext(name, TestCase(IdeProductProvider.IU, LocalProjectInfo(project)).withVersion(ideVersion)).apply {
            PluginConfigurator(this).installPluginFromPath(pluginZip)
            applyVMOptionsPatch {
                addSystemProperty("tandem.claudePath", fakeClaude)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
                addSystemProperty("ide.show.tips.on.startup.default.value", false)
                withEnv("TANDEM_FAKE_FIXTURES", fixtures.joinToString(","))
                withEnv("TANDEM_FAKE_LOG", sentLog.toString())
                if (configDir != null) withEnv("CLAUDE_CONFIG_DIR", configDir.toString())
                if (gateFile != null) withEnv("TANDEM_FAKE_GATE_FILE", gateFile.toString())
            }
        }

    /**
     * Starts this IDE, opens the tool window, runs [script], takes a screenshot named [shot] and closes the IDE. Fails on any error
     * the IDE's log attributes to the plugin.
     */
    private fun IDETestContext.runTandem(shot: String, script: Driver.() -> Unit) {
        val context = this
        context.runIdeWithDriver().useDriverAndCloseIde {
            waitFor("project opened", 5.minutes) { getOpenProjects().isNotEmpty() }
            waitFor("Tandem plugin loaded", 2.minutes) { isPluginLoaded("dev.daarkolabs.tandem") }
            // A freshly opened IDE is busy (indexing, startup activities) and drops early keystrokes.
            waitForIndicators(5.minutes)
            // IDE balloons (e.g. about JVM options) can cover the prompt box and swallow clicks.
            closeAndDisableAllBalloonNotifications()
            openToolWindow("Tandem")
            waitFor("tool window shown", 2.minutes) { newSessionButton().present() }
            script()
            // Kept as a test artifact for people to look at.
            takeScreenshot(shot)
        }

        // Any exception attributed to the plugin (e.g. a class from an undeclared IDE module) fails the test.
        val logs = context.paths.testHome.walk().filter { it.fileName.toString() == "idea.log" }.toList()
        assertTrue(logs.isNotEmpty(), "no idea.log under ${context.paths.testHome}")
        val pluginErrors = logs.flatMap { it.readLines() }.filter { "Plugin: dev.daarkolabs.tandem" in it }
        assertTrue(pluginErrors.isEmpty(), "errors attributed to Tandem:\n" + pluginErrors.joinToString("\n"))
    }

    // Compose semantics nodes appear in the Driver's UI hierarchy as ComposeNode, with their text and role. They
    // report 0x0 bounds, so a plain click() hits the node's top-left corner pixel; click a point inside instead.

    private fun Driver.promptBox(): UiComponent = ui.x(PROMPT_BOX)

    private fun Driver.button(text: String): UiComponent = ui.x("//div[@class='ComposeNode' and @role='Button' and @text='$text']")

    /** The sidebar row whose text (title, time, badge) contains [title] and each of [also]. */
    private fun Driver.row(title: String, vararg also: String): UiComponent =
        ui.x("$ROW[" + (listOf(title) + also).joinToString(" and ") { "contains(@text, '$it')" } + "]")

    /** The sidebar row that has the keyboard focus, if its text contains [title]. */
    private fun Driver.focusedRow(title: String): UiComponent = ui.x("$ROW[@focused='true' and contains(@text, '$title')]")

    private fun Driver.menuItem(text: String): UiComponent = ui.x("//div[@class='ComposeNode' and @visible_text='$text']")

    /** The picker that assistive technology knows as [description], if it shows [text]. */
    private fun Driver.picker(description: String, text: String): UiComponent =
        ui.x("//div[@class='ComposeNode' and @role='DropdownList' and @contentdescription='$description' and @visible_text='$text']")

    /** An icon button, found by what it says to assistive technology. */
    private fun Driver.iconButton(description: String): UiComponent =
        ui.x("//div[@class='ComposeNode' and @role='Button' and @contentdescription='$description']")

    /** A question card's option (a radio button or a checkbox), found by its label, which is what it says to assistive technology. */
    private fun Driver.option(label: String): UiComponent = ui.x(
        "//div[@class='ComposeNode' and @contentdescription='$label' and (@role='RadioButton' or @role='Checkbox')]",
    )

    /** A tool row (a lone call, a group or a group's sub-row), found by its label, which is what it says to assistive technology. */
    private fun Driver.toolRow(label: String): UiComponent = ui.x("//div[@class='ComposeNode' and @contentdescription='$label']")

    /** The editable text (a text field or area) that has the keyboard focus, if it holds [text]. */
    private fun Driver.focusedEditable(text: String): UiComponent = ui.x("$EDITABLE[@focused='true' and @editabletext='$text']")

    /** The sidebar's filter. */
    private fun Driver.filterBox(): UiComponent = ui.x("$EDITABLE[@contentdescription='Filter sessions']")

    /** The button that says [text], if it has the keyboard focus. */
    private fun Driver.focusedButton(text: String): UiComponent =
        ui.x("//div[@class='ComposeNode' and @role='Button' and @text='$text' and @focused='true']")

    /** The icon button described as [description], if it has the keyboard focus. */
    private fun Driver.focusedIconButton(description: String): UiComponent =
        ui.x("//div[@class='ComposeNode' and @role='Button' and @contentdescription='$description' and @focused='true']")

    /** The header's "+". */
    private fun Driver.newSessionButton(): UiComponent = iconButton("New session")

    /** Creates a session with the header's "+" and the popup's defaults; it is selected. */
    private fun Driver.newSession() {
        newSessionButton().click(Point(10, 10))
        clickButton("Create")
    }

    /**
     * Does what the context menu key does: runs the IDE's ShowPopupMenu, which the keymap binds to it, on what has the focus. The
     * robot cannot press that key under Xvfb, where it arrives as an undefined key.
     */
    private fun Driver.contextMenuKey() {
        invokeAction("ShowPopupMenu")
    }

    /** Opens the sidebar over the chat with the header's toggle. */
    private fun Driver.openSidebar() {
        iconButton("Show sessions").click(Point(10, 10))
    }

    private fun Driver.waitForText(text: String) {
        waitFor("'$text' rendered", 2.minutes) { ui.x("//div[@class='ComposeNode' and @visible_text='$text']").present() }
    }

    /** Types [text] into the prompt box like a person and presses Send, checking each step landed. */
    private fun Driver.typePrompt(text: String) {
        promptBox().click(Point(20, 20))
        waitFor("prompt box focused", 30.seconds) { ui.x("$PROMPT_BOX[@focused='true']").present() }
        promptBox().keyboard { typeText(text, 20) }
        waitFor("prompt box holds '$text'", 30.seconds) { ui.x("$PROMPT_BOX[@editabletext='$text']").present() }
        iconButton("Send").click(Point(10, 10))
        waitFor("prompt box cleared after Send", 30.seconds) { ui.x("$PROMPT_BOX[@editabletext='']").present() }
    }

    /**
     * Answers the open permission card with Allow once: with Enter when the card has the focus, which must then sit on Allow once,
     * never on Deny; else, when the Driver does not see the card's focus, with a click (the keyboard path stays on runIde checklist 6,
     * spec 10.6).
     */
    private fun Driver.answerCard() {
        val focused = runCatching {
            waitFor("card focused", 10.seconds) { ui.x(FOCUSED_IN_CARD).present() }
            true
        }.getOrDefault(false)
        if (focused) {
            assertTrue(
                ui.x("//div[@class='ComposeNode' and @role='Button' and @focused='true' and @contentdescription='Allow once']").present(),
                "the card's focus must sit on Allow once, never on Deny",
            )
            ui.keyboard { enter() }
        } else {
            println("card focus not visible to the Driver; clicking Allow once (runIde checklist 6)")
            iconButton("Allow once").click(Point(10, 10))
        }
    }

    private fun Driver.clickButton(text: String) {
        button(text).click(Point(12, 10))
    }

    private val JsonObject.type: String? get() = this["type"]?.jsonPrimitive?.content

    private companion object {
        /** How wide [widenToolWindow] makes the tool window, in pixels: past 500 dp at the scale the tests run at. */
        const val WIDE = 900

        const val EDITABLE = "//div[@class='ComposeNode' and @editabletext]"

        /** Every sidebar row. */
        const val ROW = "//div[@class='ComposeNode' and @role='Tab']"

        /**
         * The prompt box: the last editable text, since a docked sidebar's filter box comes first. A sidebar open over the chat
         * comes after it, so the tests type prompts only while it is closed.
         */
        const val PROMPT_BOX = "($EDITABLE)[last()]"

        /**
         * A permission card's button that has the keyboard focus. The card puts the focus on its default button when it opens, so a
         * focused card button means the card has the focus, whatever the Driver's hierarchy nests it in.
         */
        const val FOCUSED_IN_CARD = "//div[@class='ComposeNode' and @role='Button' and @focused='true' and " +
            "(@contentdescription='Deny' or @contentdescription='Always allow for session' or @contentdescription='Allow once')]"
    }
}

/** The platform's tool window manager, as the IDE tests reach it. */
@Remote("com.intellij.openapi.wm.ToolWindowManager")
interface ToolWindowManagerRef {
    fun getToolWindow(id: String): ToolWindowRef?
}

@Remote("com.intellij.openapi.wm.ex.ToolWindowEx")
interface ToolWindowRef {
    fun stretchWidth(value: Int)

    fun getComponent(): JComponentRef
}

@Remote("javax.swing.JComponent")
interface JComponentRef {
    fun getWidth(): Int
}
