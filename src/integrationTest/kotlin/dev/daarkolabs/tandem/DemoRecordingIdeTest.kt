package dev.daarkolabs.tandem

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import dev.daarkolabs.tandem.session.transcript.TranscriptFolder
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The launch GIF's three scenes (docs/launch.md, tactics 1 and 2), played in a real IDE against the fake CLI so that
 * `scripts/demo/record.sh` can record them again after any UI change. Each scene paces itself for a viewer: replies stream line by
 * line, and it pauses where there is something to read. It writes `scene-N start <epoch ms>` and `scene-N end <epoch ms>` to the file
 * named by `tandem.demoMarks`, which the script uses to cut the screen recording.
 *
 * Only the `demoRecord` task runs this class (it sets `tandem.demo=true`); `integrationTest` excludes it.
 */
class DemoRecordingIdeTest {
    @BeforeEach
    fun onlyWhenRecording() {
        assumeTrue(System.getProperty("tandem.demo") == "true", "run by ./gradlew demoRecord")
    }

    @Test
    fun `scene 1 an edit opens as a native diff and Accept applies it`() {
        val project = newProject()
        writeHello(project)
        runChat("demo-edit", fixtures = listOf("ide-edit"), project = project, lineDelayMs = LINE_DELAY_MS, systemProperties = IDE_PROPERTIES) {
            prepare(toolWindowWidth = SCREEN_WIDTH * 55 / 100)
            openFile("hello.txt", waitForCodeAnalysis = false)
            newSession()
            focusPrompt()
            scene(1) {
                pause(600)
                send("Change alpha to beta in hello.txt", keyDelayMs = KEY_DELAY_MS)
                soon("the diff", 1.minutes) { diffTabShown() && diffButton(ACCEPT_BUTTON).present() }
                pause(1200)
                // The recorded reply (ide-edit) says the person made `beta` into `beta!` in the diff before accepting: do just that, so the
                // reply tells the truth. The proposal is the diff's editor furthest to the right.
                val right = ui.xx(DIFF_EDITOR).list().maxBy { it.component.getLocationOnScreen().x }
                right.click(Point(60, 10))
                ui.keyboard {
                    hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_HOME)
                    key(KeyEvent.VK_END)
                    typeText("!", KEY_DELAY_MS)
                }
                pause(700)
                diffButton(ACCEPT_BUTTON).click()
                soon("the diff closed", 30.seconds) { !diffButton(ACCEPT_BUTTON).present() }
                soon("the editor shows beta!", 30.seconds) { documentText("hello.txt") == "beta!\nline two\n" }
                soon("the row", 1.minutes) { described("Edited hello.txt").present() }
                soon("the turn ended", 1.minutes) { iconButton("Send").present() }
                pause(1800)
            }
        }
    }

    @Test
    fun `scene 2 two sessions run side by side`() {
        // Both turns stay running after their replies (the fake holds each `result` back) until the scene has switched between them.
        val resultGate = Files.createTempDirectory("tandem-demo-gate").resolve("open")
        runChat(
            "demo-sessions",
            fixtures = listOf("think-opus", "think-adaptive"),
            lineDelayMs = SLOW_LINE_DELAY_MS,
            extraEnv = mapOf("TANDEM_FAKE_RESULT_GATE_FILE" to resultGate.toString()),
            systemProperties = IDE_PROPERTIES,
        ) {
            prepare(toolWindowWidth = null)
            soon("the sidebar docked beside the chat", 30.seconds) { filterBox().present() }
            // The scene starts as the first prompt is typed: creating the session is setup.
            newSessionFromSidebar()
            focusPrompt()
            scene(2) {
                send("Which of 17, 19 and 21 is prime? One word.", keyDelayMs = KEY_DELAY_MS)
                pause(600)
                // The second session: the clicks show, the IDE's catching up after Create is cut from the recording.
                clickButton("New session")
                soon("the new-session popup") { button("Create").present() }
                pause(500)
                clickButton("Create")
                mark("scene-2 cut")
                soon("the popup closed") { !button("Create").present() }
                soon("the new session's prompt box") { promptBox().present() }
                focusPrompt()
                mark("scene-2 resume")
                pause(300)
                send("Is 17, 19 or 21 prime? Step by step.", keyDelayMs = KEY_DELAY_MS)
                pause(1500)
                // Back to the first while both work, then to the second again.
                row("One word").click(Point(10, 10))
                pause(1800)
                row("Step by step").click(Point(10, 10))
                pause(1000)
                resultGate.createFile()
                soon("the second turn ended", 1.minutes) { iconButton("Send").present() && !iconButton("Stop").present() }
                pause(1000)
                row("One word").click(Point(10, 10))
                soon("the first turn ended", 1.minutes) { iconButton("Send").present() && !iconButton("Stop").present() }
                pause(1800)
            }
        }
    }

    @Test
    fun `scene 3 a session started in a terminal appears in the sidebar`() {
        val configDir = Files.createTempDirectory("tandem-config")
        val project = newProject()
        // The CLI's transcript folder for the project, empty: a terminal session's transcript lands here while the IDE runs.
        val folder = configDir.resolve("projects").resolve(TranscriptFolder.key(project.toRealPath().toString())).createDirectories()
        val terminalSession = "55555555-5555-4555-8555-555555555555"
        runChat(
            "demo-terminal",
            fixtures = listOf("think-default"),
            configDir = configDir,
            project = project,
            lineDelayMs = LINE_DELAY_MS,
            systemProperties = IDE_PROPERTIES,
        ) {
            prepare(toolWindowWidth = null)
            soon("the sidebar docked beside the chat", 30.seconds) { filterBox().present() }
            newSessionFromSidebar()
            send("Which of 17, 19 and 21 is prime?", keyDelayMs = 5L)
            soon("the turn ended", 1.minutes) { iconButton("Send").present() && !iconButton("Stop").present() }
            scene(3) {
                pause(800)
                // What `claude` started in a terminal in the project writes: the recorded transcript, as of now.
                val now = Instant.now().toString()
                val transcript = fixturesDir.resolve("transcripts/history-reply.jsonl").readText()
                    .replace(Regex("\"timestamp\":\"[^\"]*\""), "\"timestamp\":\"$now\"")
                folder.resolve("$terminalSession.jsonl").writeText(transcript, options = arrayOf(StandardOpenOption.CREATE_NEW))
                soon("the terminal session listed", 30.seconds) { row(TERMINAL_TITLE).present() }
                pause(1500)
                row(TERMINAL_TITLE).click(Point(10, 10))
                waitForText("pong")
                pause(2200)
            }
        }
    }

    /**
     * Sizes the IDE's frame to the recorded screen and the Tandem tool window to [toolWindowWidth] pixels, or with null maximizes it over
     * the editors (its sidebar docks beside the chat), and lets it settle.
     */
    private fun Driver.prepare(toolWindowWidth: Int?) {
        placeFrame(SCREEN_WIDTH, SCREEN_HEIGHT)
        // Nothing but Tandem and the scene's own editor: the trial's page and the Project view go.
        invokeAction("CloseAllEditors")
        withContext(OnDispatcher.EDT) { service<ToolWindowManagerRef>(singleProject()).getToolWindow("Project")?.hide() }
        pause(1000)
        withContext(OnDispatcher.EDT) {
            val window = tandemWindow()
            if (toolWindowWidth == null) {
                service<MaximizingToolWindowManagerRef>(singleProject()).setMaximized(window, true)
            } else {
                window.stretchWidth(toolWindowWidth - window.getComponent().getWidth())
            }
        }
        pause(1000)
    }

    /** The docked sidebar's "New session", then the popup's Create. */
    private fun Driver.newSessionFromSidebar() {
        clickButton("New session")
        soon("the new-session popup", 30.seconds) { button("Create").present() }
        clickButton("Create")
        soon("the popup closed", 30.seconds) { !button("Create").present() }
        soon("the new session's prompt box", 30.seconds) { promptBox().present() }
    }

    /** Puts the focus in the prompt box, ahead of a scene, so that the scene starts with the typing. */
    private fun Driver.focusPrompt() {
        promptBox().click(Point(20, 20))
        soon("prompt box focused") { ui.x("$PROMPT_BOX[@focused='true']").present() }
    }

    /**
     * The prompt box takes [text], typed a key every [keyDelayMs], and Send sends it: `typePrompt` with [soon]'s quick polls, so that the
     * recording has no idle second between the steps.
     */
    private fun Driver.send(text: String, keyDelayMs: Long) {
        if (!ui.x("$PROMPT_BOX[@focused='true']").present()) focusPrompt()
        promptBox().keyboard { typeText(text, keyDelayMs) }
        soon("prompt box holds '$text'") { ui.x("$PROMPT_BOX[@editabletext='$text']").present() }
        iconButton("Send").click(Point(10, 10))
        soon("prompt box cleared after Send") { ui.x("$PROMPT_BOX[@editabletext='']").present() }
    }

    /** [waitFor] polling every 100 ms instead of every second, so that a scene moves on as soon as the IDE has caught up. */
    private fun soon(what: String, timeout: Duration = 30.seconds, condition: () -> Boolean) {
        waitFor(what, timeout, 100.milliseconds, condition = condition)
    }

    /** Runs [body] between scene [number]'s start and end marks. */
    private fun scene(number: Int, body: () -> Unit) {
        mark("scene-$number start")
        body()
        mark("scene-$number end")
    }

    private fun mark(what: String) {
        val file = System.getProperty("tandem.demoMarks")?.takeIf { it.isNotBlank() } ?: return
        Files.writeString(Path.of(file), "$what ${System.currentTimeMillis()}\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /**
     * Waits [millis] for a viewer to read. It queries nothing: a Driver query costs up to a second here, which would stretch every pause
     * (the trial feedback dialog is kept away by [IDE_PROPERTIES] instead).
     */
    @Suppress("UnusedReceiverParameter")
    private fun Driver.pause(millis: Long) {
        Thread.sleep(millis)
    }

    private companion object {
        /** The screen `scripts/demo/record.sh` records. */
        const val SCREEN_WIDTH = 1280
        const val SCREEN_HEIGHT = 800

        /** How long the fake CLI sleeps before each streamed line, so that text visibly streams. */
        const val LINE_DELAY_MS = 25

        /** Scene 2's slower stream, so that the second reply streams while the first session is shown again. */
        const val SLOW_LINE_DELAY_MS = 120

        /** The pause between typed keys: a person typing, briskly. */
        const val KEY_DELAY_MS = 30L

        /** The terminal session's title in the sidebar: its transcript's `ai-title`. */
        const val TERMINAL_TITLE = "Pong"

        /**
         * No trial feedback survey in the middle of a scene (the platform's `evaluation.feedback.enabled` registry key), and the UI at 125 %
         * so that its text reads in a GIF at README width.
         */
        val IDE_PROPERTIES: Map<String, String> = mapOf("evaluation.feedback.enabled" to "false", "ide.ui.scale" to "1.25")

        /** A text editor: a side of the diff. */
        const val DIFF_EDITOR = "//div[@class='EditorComponentImpl']"
    }
}

/** The platform's `ToolWindowManager`, to maximize a tool window over the editors. */
@Remote("com.intellij.openapi.wm.ToolWindowManager")
interface MaximizingToolWindowManagerRef {
    fun setMaximized(window: ToolWindowRef, maximized: Boolean)
}
