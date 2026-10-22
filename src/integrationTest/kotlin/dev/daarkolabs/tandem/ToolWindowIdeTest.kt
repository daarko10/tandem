package dev.daarkolabs.tandem

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RemoteMouseButton
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
import dev.daarkolabs.tandem.session.CLI_MODEL_ALIASES
import dev.daarkolabs.tandem.session.transcript.TranscriptFolder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
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
 * Run with `xvfb-run ./gradlew integrationTest` when there is no display. CI splits the tests across jobs ([IdeTestShard]).
 */
@ExtendWith(IdeTestShard::class)
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
            waitFor("the running model shown in the footer", 1.minutes) { modelControl(defaultModelName("plain-reply")).present() }
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
            // The card took the focus on its first option. `3`, the Other row's key, focuses the Other field and types nothing into
            // it; Shift+Enter there types a newline (spec 4.13): 'a', Shift+Enter, 'b' leaves exactly three characters, so a stray
            // '3' fails too.
            waitFor("first option focused", 30.seconds) { ui.x(FOCUSED_OPTION_RED).present() }
            ui.keyboard { key(KeyEvent.VK_3) }
            waitFor("Other field focused", 30.seconds) {
                ui.x("$EDITABLE[@focused='true']").present() && !ui.x("$PROMPT_BOX[@focused='true']").present()
            }
            ui.keyboard { typeText("a", 20) }
            ui.keyboard { hotKey(KeyEvent.VK_SHIFT, KeyEvent.VK_ENTER) }
            ui.keyboard { typeText("b", 20) }
            val aNewlineB = "string-length(@editabletext)=3 and starts-with(@editabletext, 'a') and substring(@editabletext, 3)='b'"
            waitFor("Other holds a, a newline and b", 30.seconds) { ui.x("$EDITABLE[@focused='true' and $aNewlineB]").present() }
            // Picking an option of a one-answer question clears Other, so the answer is the option.
            option("Blue").click(Point(10, 10))
            // A one-question card never offers Next (spec 2.8 of the polish). The click is the IDE tests' mouse path of the primary;
            // the two-question test covers Enter.
            waitFor("Submit answers, and no Next, on a one-question card", 30.seconds) {
                iconButton("Submit answers").present() && !iconButton("Next").present()
            }
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
    fun `a two-question card is answered from the keyboard`() {
        // ask-two: Which colour? (Red, Blue) then Which toppings would you like? (Cheese, Olives, Ham; multiSelect).
        val sent = runChat("question-two", fixtures = listOf("ask-two")) {
            newSession()
            typePrompt("Ask me two questions")
            waitForText("Which colour?")
            waitFor("first option focused", 30.seconds) { ui.x(FOCUSED_OPTION_RED).present() }
            waitFor("Next while the other question is unanswered", 30.seconds) {
                iconButton("Next").present() && !iconButton("Submit answers").present()
            }
            ui.keyboard { key(KeyEvent.VK_1) }
            waitFor("Red picked, no auto-advance", 30.seconds) {
                questionTab("Colour").present() && ui.x(FOCUSED_OPTION_RED).present() && iconButton("Next").present()
            }
            // `3`, the Other row's key, focuses the empty Other field (Red stays the answer); Enter there runs Next (QuestionKey.Primary).
            ui.keyboard { key(KeyEvent.VK_3) }
            waitFor("Other field focused", 30.seconds) {
                ui.x("$EDITABLE[@focused='true']").present() && !ui.x("$PROMPT_BOX[@focused='true']").present()
            }
            // Held until the focus is on Cheese, as a person's Enter is, so the release lands on that row, which must not toggle it
            // (spec 2.8 of the polish, edge 8). enter() presses and releases back to back: its release would reach the field before
            // the focus moves. pressing() (driver 261.27258.48) presses, runs the block and releases in a finally. A key repeat
            // during the hold is a no-op (Next to the shown question, or a disabled Submit answers).
            ui.keyboard {
                pressing(KeyEvent.VK_ENTER) {
                    waitFor("Cheese focused while Enter is held", 30.seconds) { focusedOption("Cheese").present() }
                }
            }
            waitForText("Which toppings would you like?")
            waitFor("the second tab keeps its dot and Submit answers shows", 30.seconds) {
                questionTab("Toppings ·").present() && iconButton("Submit answers").present() && !iconButton("Next").present()
            }
            // The TextArea is the same node across questions: the next digit must reach the Cheese row, not the field; the Enter's
            // newline reached no field.
            waitFor("Cheese focused, no field focused or filled", 30.seconds) {
                focusedOption("Cheese").present() && !ui.x("$EDITABLE[@focused='true']").present() &&
                    !ui.x("$EDITABLE[string-length(@editabletext) > 0]").present()
            }
            // Had the held Enter's release toggled Cheese on, this 1 would toggle it off and the answer would read Ham.
            ui.keyboard { key(KeyEvent.VK_1) }
            ui.keyboard { key(KeyEvent.VK_3) }
            waitFor("Cheese and Ham picked", 30.seconds) { questionTab("Toppings").present() }
            // On the focused Cheese row: CardFrame's onEnter runs Submit answers, which sends the answer on the press.
            ui.keyboard { enter() }
            waitFor("question card closed", 2.minutes) {
                !ui.x("//div[@class='ComposeNode' and @contentdescription='Question from Claude']").present()
            }
            // The model's reply, ask-two:43.
            waitForText("Red and Cheese, Ham")
            waitFor("Questions row", 2.minutes) { toolRow("Questions").present() }
        }
        val answer = sent.filter { it.type == "control_response" }.map { it["response"]!!.jsonObject }
            .single { it["request_id"]!!.jsonPrimitive.content == "<request_id-38>" }["response"]!!.jsonObject
        assertEquals("allow", answer["behavior"]!!.jsonPrimitive.content)
        val answers = answer["updatedInput"]!!.jsonObject["answers"]!!.jsonObject
        assertEquals("Red", answers["Which colour?"]!!.jsonPrimitive.content)
        assertEquals("Cheese, Ham", answers["Which toppings would you like?"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the plan card approves and the picker follows`() {
        val sent = runChat("plan", fixtures = listOf("plan-approve")) {
            newSession()
            typePrompt("Plan a changelog")
            waitForText("Approve this plan?")
            iconButton("Approve and start coding").click(Point(10, 10))
            // The CLI's status line after the approval (plan-approve:90) says acceptEdits.
            waitFor("mode follows the approval", 1.minutes) { picker("Permission mode", "Accept edits").present() }
        }
        val approval = sent.filter { it.type == "control_response" }.map { it["response"]!!.jsonObject }
            .single { it["request_id"]!!.jsonPrimitive.content == "<request_id-97>" }["response"]!!.jsonObject
        assertEquals("allow", approval["behavior"]!!.jsonPrimitive.content)
        assertEquals("setMode", approval["updatedPermissions"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the working indicator shows while a turn runs and a footer after it`() {
        // The fake CLI holds both permission requests (permission:21 and :52) back until this file exists.
        val gate = Files.createTempDirectory("tandem-gate").resolve("open")
        val sent = runChat("working", fixtures = listOf("permission"), gateFile = gate) {
            newSession()
            typePrompt("Create the fixture file")
            // One merged node: "<Verb>… · Ns" (spec 4.19), its text ending in the elapsed seconds (or in "tokens").
            waitFor("working row with elapsed", 1.minutes) {
                node("Claude is working") { "contains($it, ' · ') and substring($it, string-length($it)) = 's'" }.present()
            }
            // A lone running call opens on its command (polish spec 2.1) while its permission request is still held back.
            toolRow("Creating empty fixture file").click(Point(10, 10))
            waitFor("a lone running call opens on its command", 30.seconds) {
                ui.x("//div[@class='ComposeNode' and starts-with(@visible_text, '\$ touch tandem-fixture.txt')]").present()
            }
            gate.createFile()
            waitForText("Allow Claude to Run touch tandem-fixture.txt?")
            waitFor("amber waiting row", 1.minutes) { workingRow("Claude is waiting for you").present() }
            answerCard()
            waitFor("footer, working row gone", 2.minutes) {
                ui.x(FOOTER).present() && !workingRow("Claude is working").present() && !workingRow("Claude is waiting for you").present()
            }
            // permission.ndjson is two turns (result :35, init :40): the second request (:52) belongs to the second one.
            typePrompt("Now remove it")
            waitForText("Allow Claude to Run rm tandem-fixture.txt?")
            waitFor("waiting again", 1.minutes) { workingRow("Claude is waiting for you").present() }
            iconButton("Deny").click(Point(10, 10))
            waitFor("denied row and footer, working row gone", 2.minutes) {
                toolRow("Remove fixture file · Denied").present() && ui.x(FOOTER).present() &&
                    !workingRow("Claude is working").present() && !workingRow("Claude is waiting for you").present()
            }
        }
        val answers = sent.filter { it.type == "control_response" }
            .map { it["response"]!!.jsonObject["response"]!!.jsonObject["behavior"]!!.jsonPrimitive.content }
        assertEquals(listOf("allow", "deny"), answers)
    }

    @Test
    fun `an opened running group lists its finished calls`() {
        // tools-read streams its 50 stream_event lines at 400 ms (about 20 s); its calls run as a group from line 24 on. The lone
        // pending `Reading…` of lines 9-16 matches neither of the running group's predicates and cannot open.
        runChat("live-group", fixtures = listOf("tools-read"), lineDelayMs = 400) {
            newSession()
            typePrompt("Read the file")
            waitFor("running group row", 1.minutes) { runningGroupRow().present() }
            runningGroupRow().click(Point(10, 10))
            waitFor("finished sub-row inside the open box", 30.seconds) { toolRow("Read <path>").present() }
            waitFor("the group finished with its box still open", 1.minutes) {
                toolRow("Read a file, found files, searched for a pattern").present() && toolRow("Searched for seven · 1 match").present()
            }
        }
    }

    @Test
    fun `scrolling up pauses following and the jump button catches up`() {
        val configDir = Files.createTempDirectory("tandem-config")
        val project = newProject()
        val seeded = "55555555-5555-4555-8555-555555555555"
        // A session long enough to overflow the chat, so that Home moves the viewport away from the bottom.
        configDir.resolve("projects").resolve(TranscriptFolder.key(project.toRealPath().toString())).createDirectories()
            .resolve("$seeded.jsonl").writeText(longTranscript(seeded))
        // synthetic-long-reply streams 183 lines, 70 ms apart: about 12.8 s, one new row per paragraph.
        val sent = runChat(
            "autoscroll",
            fixtures = listOf("synthetic-long-reply"),
            configDir = configDir,
            project = project,
            lineDelayMs = 70,
        ) {
            openSidebar()
            row("Seeded").click(Point(10, 10))
            waitFor("seeded history shown, prompt focused", 1.minutes) { ui.x("$PROMPT_BOX[@focused='true']").present() }
            typePrompt("Write me a long reply")
            waitFor("streaming", 30.seconds) { iconButton("Stop").present() }
            // Inside the 16 dp padding: the press focuses the list, and no row takes it.
            ui.x(TRANSCRIPT).click(Point(10, 10))
            ui.keyboard { key(KeyEvent.VK_HOME) }
            waitFor("jump button offered", 30.seconds) { iconButton("Jump to bottom").present() }
            // The first bubble is in the viewport (only visible rows are in the tree).
            waitForText("Prompt 1 of the seeded history")
            Thread.sleep(3_000)
            // The streamed paragraphs are below the viewport: following the stream would bring them into the tree.
            assertFalse(
                ui.x("//div[@class='ComposeNode' and starts-with(@visible_text, 'Paragraph ')]").present(),
                "the viewport followed the streamed rows while paused",
            )
            assertFalse(ui.x("//div[@class='ComposeNode' and @visible_text='pong']").present(), "the viewport moved while paused")
            assertTrue(iconButton("Stop").present(), "the turn is still streaming")
            assertTrue(
                node("Jump to bottom") { "starts-with($it, '↓ ') and substring($it, string-length($it) - 3) = ' new'" }.present(),
                "rows were appended while paused",
            )
            assertTrue(
                ui.x("//div[@class='ComposeNode' and @visible_text='Prompt 1 of the seeded history']").present(),
                "still at the top",
            )
            iconButton("Jump to bottom").click(Point(10, 10))
            waitForText("pong")
            waitFor("jump button gone", 30.seconds) { !iconButton("Jump to bottom").present() }
        }
        assertEquals(1, sent.count { it.type == "user" })
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
    fun `the footer menus send set_permission_mode and set_model`() {
        val log = Files.createTempFile("tandem-sent", ".ndjson")
        val sent = runChat("footer-menus", fixtures = listOf("permission-mode"), sentLog = log) {
            newSession()
            typePrompt("Reply with the single word: one")
            waitForText("one")
            // The mode menu lists each mode with its description, a string only the open menu shows; Escape picks nothing.
            picker("Permission mode", "Manual").click(Point(10, 10))
            waitFor("the mode menu open", 30.seconds) { menuItemContaining(PLAN_DESCRIPTION).present() }
            ui.keyboard { escape() }
            waitFor("Escape closed the menu, Manual kept", 30.seconds) {
                !menuItemContaining(PLAN_DESCRIPTION).present() && picker("Permission mode", "Manual").present()
            }
            // 4 is Plan's digit: one request.
            picker("Permission mode", "Manual").click(Point(10, 10))
            waitFor("the mode menu open again", 30.seconds) { menuItemContaining(PLAN_DESCRIPTION).present() }
            ui.keyboard { key(KeyEvent.VK_4) }
            waitFor("4 picked Plan and closed the menu", 30.seconds) {
                !menuItemContaining(PLAN_DESCRIPTION).present() && picker("Permission mode", "Plan").present()
            }
            typePrompt("Reply with the single word: two")
            waitForText("two")
            waitFor("the mode control shows plan", 1.minutes) { picker("Permission mode", "Plan").present() }
            // A pick in the footer is the project's last one: the new-session popup starts from it. Escape closes the popup
            // and creates nothing.
            newSessionButton().click(Point(10, 10))
            // The footer's mode control shows Plan too: the popup's is the second one.
            waitFor("the popup, on the last pick", 30.seconds) {
                button("Create").present() &&
                    ui.xx(
                        "//div[@class='ComposeNode' and @role='DropdownList' and @contentdescription='Permission mode' and " +
                            "(@visible_text='Plan' or @text='Plan')]",
                    ).list().size == 2
            }
            ui.keyboard { escape() }
            waitFor("Escape closed the popup", 30.seconds) { !button("Create").present() }
            modelControl(defaultModelName("permission-mode")).click(Point(10, 10))
            waitFor("the model menu open", 30.seconds) { menuItemContaining("Opus").present() }
            // Kept as a test artifact: the menu 8 dp above the pill and right-aligned with it.
            takeScreenshot("footer-model-menu")
            // Picked with the pointer straight from the control: on its way up it crosses the menu's last row, `Effort`, which turns
            // the menu to its effort page on a click alone, never on hover (spec open item 13). The menus are popup layers of the tool
            // window's own Compose scene, which gives the pointer to the topmost one alone, so a row that opened a popup on hover
            // would take the pointer from the models and the click on Opus would only close the menus.
            menuItemContaining("Opus").click(Point(10, 10))
            // The fake CLI answers set_model with the next acknowledgement it recorded, set_permission_mode's acceptEdits one, and
            // that one's status line: so the session ends in Accept edits. Wait for the frame itself before the IDE closes.
            waitFor("set_model reached the CLI", 30.seconds) { "\"subtype\":\"set_model\"" in log.readText() }
        }
        val requests = sent.filter { it.type == "control_request" }.map { it["request"]!!.jsonObject }
        val modes = requests.filter { it["subtype"]!!.jsonPrimitive.content == "set_permission_mode" }
            .map { it["mode"]!!.jsonPrimitive.content }
        assertEquals(listOf("plan"), modes, "Escape picked nothing, and 4 picked Plan alone")
        val setModel = requests.single { it["subtype"]!!.jsonPrimitive.content == "set_model" }
        assertEquals("opus", setModel["model"]!!.jsonPrimitive.content)
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

    @Test
    fun `a pasted image is sent as an image block`() {
        // The IDE reads the clipboard of the display this JVM shares with it; open item 9: where this JVM cannot own it, the paste
        // stays on the runIde checklist and the drop test below covers the attach path.
        assumeTrue(
            runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransferable(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)), null) }
                .isSuccess,
            "the test JVM cannot own the clipboard",
        )
        val sent = runChat("pasted-image", fixtures = listOf("user-image")) {
            newSession()
            promptBox().click(Point(10, 10))
            waitFor("prompt box focused", 30.seconds) { ui.x("$PROMPT_BOX[@focused='true']").present() }
            ui.keyboard { hotKey(if (isMac) KeyEvent.VK_META else KeyEvent.VK_CONTROL, KeyEvent.VK_V) }
            waitFor("thumbnail", 30.seconds) { ui.x(FIRST_IMAGE).present() }
            typePrompt("What colour?")
            waitForText("Red.")
        }
        assertImagePrompt(sent)
    }

    @Test
    fun `a dropped image file is attached`() {
        // Open item 10. The drag starts in the IDE's own Project view, as a user drags a file from it: a drag from another process
        // reaches no window under Xvfb, which has no window manager to mark the IDE's window as a drop site.
        assumeTrue(!GraphicsEnvironment.isHeadless(), "no display to drag on")
        val project = newProject()
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", project.resolve("drop.png").toFile())
        val sent = runChat("dropped-image", fixtures = listOf("user-image"), project = project) {
            newSession()
            val box = promptBox().component
            val boxOrigin = box.getLocationOnScreen()
            val target = Point(boxOrigin.x + maxOf(box.width / 2, 20), boxOrigin.y + maxOf(box.height / 2, 15))
            val tree = ui.x("//div[@class='MyProjectViewTree']")
            val node = tree.waitOneText("drop.png")
            val treeOrigin = tree.component.getLocationOnScreen()
            val start = Point(treeOrigin.x + node.point.x, treeOrigin.y + node.point.y)
            val robot = tree.robot
            robot.moveMouse(start)
            robot.pressMouse(RemoteMouseButton.LEFT)
            var x = start.x
            var y = start.y
            while (x != target.x || y != target.y) {
                x += (target.x - x).coerceIn(-10, 10)
                y += (target.y - y).coerceIn(-10, 10)
                robot.moveMouse(Point(x, y))
                Thread.sleep(15)
            }
            Thread.sleep(300)
            robot.releaseMouse(RemoteMouseButton.LEFT)
            waitFor("thumbnail", 30.seconds) { ui.x(FIRST_IMAGE).present() }
            typePrompt("What colour?")
            waitForText("Red.")
        }
        assertImagePrompt(sent)
    }

    /** The one prompt sent: `What colour?` as its text block, then a PNG image block with data. */
    private fun assertImagePrompt(sent: List<JsonObject>) {
        val content = sent.single { it.type == "user" }["message"]!!.jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, content.size, content.toString())
        assertEquals("text", content[0]["type"]!!.jsonPrimitive.content)
        assertEquals("What colour?", content[0]["text"]!!.jsonPrimitive.content)
        assertEquals("image", content[1]["type"]!!.jsonPrimitive.content)
        val source = content[1]["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        assertTrue(source["data"]!!.jsonPrimitive.content.isNotEmpty(), "the image block carries data")
    }

    /** A clipboard image with no text: what a screenshot tool or a browser's Copy Image leaves. */
    private class ImageTransferable(private val image: Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
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
     * What the footer's model control reads for [fixture] (`fixtures/<fixture>.ndjson`, next to the fake CLI) with no model picked
     * (spec 2.3, `<Model>`): the display name of the entry other than `default`, in its first control_response that lists models,
     * whose `resolvedModel` is the model its first `system/init` reports; without such a list, in an IDE where no process has listed
     * any, the name of the CLI's alias for that model's family ([CLI_MODEL_ALIASES]: `Sonnet` for `claude-sonnet-5-5`); else that
     * model's wire id.
     */
    private fun defaultModelName(fixture: String): String {
        val lines = Path.of(fakeClaude).resolveSibling("fixtures/$fixture.ndjson").readLines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
        val running = lines.first { it.type == "system" && it["subtype"]?.jsonPrimitive?.content == "init" }["model"]!!.jsonPrimitive.content
        val models = lines.filter { it.type == "control_response" }
            .firstNotNullOfOrNull { it["response"]?.jsonObject?.get("response")?.jsonObject?.get("models")?.jsonArray }
            .orEmpty().map { it.jsonObject }
        val entry = models.firstOrNull {
            it["value"]?.jsonPrimitive?.content != "default" && it["resolvedModel"]?.jsonPrimitive?.content == running
        }
        return entry?.get("displayName")?.jsonPrimitive?.content
            ?: CLI_MODEL_ALIASES.firstOrNull { it.value != "default" && it.value in running.split('-') }?.displayName
            ?: running
    }

    /**
     * A transcript of [turns] short turns, long enough to overflow the chat: history-reply.jsonl's user line (3) and assistant
     * line (18), repeated with the user text `Prompt <n> of the seeded history` and the reply `Reply <n>.`, the uuids `u<n>` and
     * `a<n>` chained through `parentUuid`, and a `message.id` of its own on each assistant line (the reducer merges lines with
     * equal message ids); then the title `Seeded` for session [seeded].
     */
    private fun longTranscript(seeded: String, turns: Int = 30): String {
        val lines = Path.of(fakeClaude).resolveSibling("fixtures/transcripts/history-reply.jsonl").readLines()
        val user = Json.parseToJsonElement(lines[2]).jsonObject
        val assistant = Json.parseToJsonElement(lines[17]).jsonObject
        val message = assistant["message"]!!.jsonObject
        val text = message["content"]!!.jsonArray.single().jsonObject
        return buildString {
            for (n in 1..turns) {
                val prompt = user + mapOf(
                    "uuid" to JsonPrimitive("u$n"),
                    "parentUuid" to if (n == 1) JsonNull else JsonPrimitive("a${n - 1}"),
                    "message" to JsonObject(user["message"]!!.jsonObject + ("content" to JsonPrimitive("Prompt $n of the seeded history"))),
                )
                val reply = assistant + mapOf(
                    "uuid" to JsonPrimitive("a$n"),
                    "parentUuid" to JsonPrimitive("u$n"),
                    "message" to JsonObject(
                        message + mapOf(
                            "id" to JsonPrimitive("m$n"),
                            "content" to JsonArray(listOf(JsonObject(text + ("text" to JsonPrimitive("Reply $n."))))),
                        ),
                    ),
                )
                append(JsonObject(prompt)).append('\n')
                append(JsonObject(reply)).append('\n')
            }
            append("""{"type":"custom-title","customTitle":"Seeded","sessionId":"$seeded"}""").append('\n')
        }
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
     * (`CLAUDE_CONFIG_DIR`), and the fake CLI holds every permission request back until [gateFile] exists and sleeps
     * [lineDelayMs] before each stream line. The frames go to [sentLog] as the fake CLI reads them, so that [script] can wait for one.
     */
    private fun runChat(
        name: String,
        fixtures: List<String>,
        configDir: Path? = null,
        gateFile: Path? = null,
        project: Path = newProject(),
        sentLog: Path = Files.createTempFile("tandem-sent", ".ndjson"),
        lineDelayMs: Int? = null,
        script: Driver.() -> Unit,
    ): List<JsonObject> {
        ideContext(name, fixtures, configDir, gateFile, project, sentLog, lineDelayMs).runTandem(name, script)
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
        lineDelayMs: Int? = null,
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
                if (lineDelayMs != null) withEnv("TANDEM_FAKE_LINE_DELAY_MS", lineDelayMs.toString())
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

    /**
     * A footer menu's item whose text contains [text]. An item merges its label, description, check and digit into one node
     * (`Plan || Create a plan before making changes || 4`), which may expose that text as `@text` or as `@visible_text` (base
     * 10.6); it is the node with a `selected` state, since the menu's popup has a node of its own whose visible text holds every
     * item's.
     */
    private fun Driver.menuItemContaining(text: String): UiComponent =
        ui.x("//div[@class='ComposeNode' and @selected and (contains(@visible_text,'$text') or contains(@text,'$text'))]")

    /**
     * The picker or footer control that assistive technology knows as [description], if it shows [text]. A footer control is a
     * clickable box whose merged node may expose its text as `@text` (base 10.6).
     */
    private fun Driver.picker(description: String, text: String): UiComponent = ui.x(
        "//div[@class='ComposeNode' and @role='DropdownList' and @contentdescription='$description' and " +
            "(@visible_text='$text' or @text='$text')]",
    )

    /** The footer's model control, if its text starts with the model [name]. */
    private fun Driver.modelControl(name: String): UiComponent = ui.x(
        "//div[@class='ComposeNode' and @role='DropdownList' and @contentdescription='Model' and " +
            "(starts-with(@visible_text,'$name') or starts-with(@text,'$name'))]",
    )

    /** An icon button, found by what it says to assistive technology. */
    private fun Driver.iconButton(description: String): UiComponent =
        ui.x("//div[@class='ComposeNode' and @role='Button' and @contentdescription='$description']")

    /** A question card's option (a radio button or a checkbox), found by its label, which is what it says to assistive technology. */
    private fun Driver.option(label: String): UiComponent = ui.x(
        "//div[@class='ComposeNode' and @contentdescription='$label' and (@role='RadioButton' or @role='Checkbox')]",
    )

    /**
     * A tab of the question card's strip, by its label (`Colour`, `Toppings ·`): Jewel's SegmentedControl button holds its Text as
     * one node.
     */
    private fun Driver.questionTab(label: String): UiComponent = ui.x("//div[@class='ComposeNode' and @visible_text='$label']")

    /** A question card's option (a radio button or a checkbox) that has the keyboard focus, by its label. */
    private fun Driver.focusedOption(label: String): UiComponent = ui.x(
        "//div[@class='ComposeNode' and @contentdescription='$label' and @focused='true' and (@role='RadioButton' or @role='Checkbox')]",
    )

    /** The working row, found by what it says to assistive technology: `Claude is working` or `Claude is waiting for you`. */
    private fun Driver.workingRow(description: String): UiComponent =
        ui.x("//div[@class='ComposeNode' and @contentdescription='$description']")

    /**
     * The node described as [description] whose text passes [textPredicate], an XPath condition on the attribute it is given: the
     * merged text as `@text` first, `@visible_text` as the fallback (spec 10.6).
     */
    private fun Driver.node(description: String, textPredicate: (attr: String) -> String): UiComponent = ui.x(
        "//div[@class='ComposeNode' and @contentdescription='$description' and " +
            "((${textPredicate("@text")}) or (${textPredicate("@visible_text")}))]",
    )

    /** A tool row (a lone call, a group or a group's sub-row), found by its label, which is what it says to assistive technology. */
    private fun Driver.toolRow(label: String): UiComponent = ui.x("//div[@class='ComposeNode' and @contentdescription='$label']")

    /**
     * The header of a running group, a row only a running group has: its sentence ends with `…` and reads `Reading <count>…` or
     * holds a clause separator (`Read a file, finding files…`).
     */
    private fun Driver.runningGroupRow(): UiComponent = ui.x(
        "//div[@class='ComposeNode' and @role='Button' and substring(@contentdescription, string-length(@contentdescription)) = '…' and " +
            "(starts-with(@contentdescription, 'Reading ') or contains(@contentdescription, ', '))]",
    )

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

    /**
     * Creates a session with the header's "+" and the popup's defaults; it is selected. The popup is a window of its own, and a
     * click that lands while it is still being placed moves nothing (seen once on a loaded CI runner: the popup open, no session),
     * so a Create that leaves the popup open is pressed once more, and the helper returns only with the new session's prompt box.
     */
    private fun Driver.newSession() {
        newSessionButton().click(Point(10, 10))
        waitFor("the new-session popup", 30.seconds) { button("Create").present() }
        clickButton("Create")
        val closed = runCatching { waitFor("the popup closed", 5.seconds) { !button("Create").present() } }.isSuccess
        if (!closed) clickButton("Create")
        waitFor("the new session's prompt box", 30.seconds) { promptBox().present() }
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

        /** The first attached or sent image: a thumbnail, or its placeholder while it decodes. */
        const val FIRST_IMAGE = "//div[@class='ComposeNode' and @contentdescription='[Image #1]']"

        /** Whether the IDE runs on macOS, where paste is Cmd+V. */
        val isMac: Boolean = System.getProperty("os.name").lowercase().startsWith("mac")

        /** A turn's footer. */
        const val FOOTER = "//div[@class='ComposeNode' and starts-with(@visible_text, 'Worked for')]"

        /** The Plan item's description: a string only the open mode menu shows (the footer itself can read `Plan`). */
        const val PLAN_DESCRIPTION = "Create a plan before making changes"

        /** The transcript, the chat's list of rows. */
        const val TRANSCRIPT = "//div[@class='ComposeNode' and @contentdescription='Transcript']"

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

        /** The `Red` option of ask-single's question card, when it has the keyboard focus. */
        const val FOCUSED_OPTION_RED = "//div[@class='ComposeNode' and @contentdescription='Red' and @focused='true' and " +
            "(@role='RadioButton' or @role='Checkbox')]"
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
