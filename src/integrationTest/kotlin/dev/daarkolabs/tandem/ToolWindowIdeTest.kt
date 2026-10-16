package dev.daarkolabs.tandem

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.closeAndDisableAllBalloonNotifications
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.awt.Point
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Installs the built plugin ZIP into a real IDE (the release we compile against) and uses the Tandem tool window
 * the way a person does: type a prompt, press Send, answer permission prompts, press Stop. The `claude` it talks to
 * is `src/test/resources/fake-claude.py`, which replays a recorded fixture in step with what the UI sends and logs
 * every frame it receives, so each test checks both the rendered UI and the protocol traffic.
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
        val sent = runChat("typedPrompt", fixture = "plain-reply") {
            typePrompt("Reply with the single word: pong")
            waitForText("Reply with the single word: pong")
            waitForText("pong")
            waitFor("model shown in the status line", 1.minutes) {
                ui.x("//div[@class='ComposeNode' and starts-with(@visible_text, 'claude-sonnet-5-5')]").present()
            }
            waitFor("Send is offered again", 1.minutes) { button("Send").present() }
        }
        val prompt = sent.single { it.type == "user" }
        assertTrue("Reply with the single word: pong" in prompt.toString(), prompt.toString())
    }

    @Test
    fun `permission prompts are answered from the tool window`() {
        val sent = runChat("permissions", fixture = "permission") {
            typePrompt("Create the fixture file")
            waitForText("Allow Bash?")
            clickButton("Allow once")
            waitForText("done.")

            typePrompt("Now remove it")
            waitForText("Allow Bash?")
            clickButton("Deny")
            waitForText("denied.")
        }
        val answers = sent.filter { it.type == "control_response" }.map { it["response"]!!.jsonObject }
        assertEquals(listOf("<request_id-26>", "<request_id-63>"), answers.map { it["request_id"]!!.jsonPrimitive.content })
        val behaviors = answers.map { it["response"]!!.jsonObject["behavior"]!!.jsonPrimitive.content }
        assertEquals(listOf("allow", "deny"), behaviors)
    }

    @Test
    fun `Stop interrupts a running turn`() {
        val sent = runChat("stop", fixture = "interrupt") {
            typePrompt("Count from 1 to 300")
            waitFor("Stop offered while the turn runs", 1.minutes) { button("Stop").present() }
            clickButton("Stop")
            waitForText("Stopped.")
            waitFor("Send is offered again", 1.minutes) { button("Send").present() }
        }
        val requests = sent.filter { it.type == "control_request" }.map { it["request"]!!.jsonObject["subtype"]!!.jsonPrimitive.content }
        assertEquals(listOf("initialize", "interrupt"), requests)
    }

    /**
     * Starts the IDE with the plugin and the fake CLI replaying [fixture], opens the tool window, runs [script],
     * and returns the frames the plugin wrote to the CLI's stdin.
     */
    private fun runChat(name: String, fixture: String, script: Driver.() -> Unit): List<JsonObject> {
        val project = Files.createTempDirectory("tandem-ide-test").resolve("demo").createDirectories()
        project.resolve("README.md").writeText("# Demo\n")
        val sentLog = Files.createTempFile("tandem-sent", ".ndjson")

        val context = Starter.newContext(name, TestCase(IdeProductProvider.IU, LocalProjectInfo(project)).withVersion(ideVersion)).apply {
            PluginConfigurator(this).installPluginFromPath(pluginZip)
            applyVMOptionsPatch {
                addSystemProperty("tandem.claudePath", fakeClaude)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
                addSystemProperty("ide.show.tips.on.startup.default.value", false)
                withEnv("TANDEM_FAKE_FIXTURE", fixture)
                withEnv("TANDEM_FAKE_LOG", sentLog.toString())
            }
        }

        context.runIdeWithDriver().useDriverAndCloseIde {
            waitFor("project opened", 5.minutes) { getOpenProjects().isNotEmpty() }
            waitFor("Tandem plugin loaded", 2.minutes) { isPluginLoaded("dev.daarkolabs.tandem") }
            // A freshly opened IDE is busy (indexing, startup activities) and drops early keystrokes.
            waitForIndicators(5.minutes)
            // IDE balloons (e.g. about JVM options) can cover the prompt box and swallow clicks.
            closeAndDisableAllBalloonNotifications()
            openToolWindow("Tandem")
            waitFor("prompt box shown", 2.minutes) { promptBox().present() }
            script()
            // Kept as a test artifact for people to look at.
            takeScreenshot(name)
        }

        // Any exception attributed to the plugin (e.g. a class from an undeclared IDE module) fails the test.
        val logs = context.paths.testHome.walk().filter { it.fileName.toString() == "idea.log" }.toList()
        assertTrue(logs.isNotEmpty(), "no idea.log under ${context.paths.testHome}")
        val pluginErrors = logs.flatMap { it.readLines() }.filter { "Plugin: dev.daarkolabs.tandem" in it }
        assertTrue(pluginErrors.isEmpty(), "errors attributed to Tandem:\n" + pluginErrors.joinToString("\n"))

        return if (sentLog.exists()) sentLog.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject } else emptyList()
    }

    // Compose semantics nodes appear in the Driver's UI hierarchy as ComposeNode, with their text and role. They
    // report 0x0 bounds, so a plain click() hits the node's top-left corner pixel; click a point inside instead.

    private fun Driver.promptBox(): UiComponent = ui.x(PROMPT_BOX)

    private fun Driver.button(text: String): UiComponent = ui.x("//div[@class='ComposeNode' and @role='Button' and @text='$text']")

    private fun Driver.waitForText(text: String) {
        waitFor("'$text' rendered", 2.minutes) { ui.x("//div[@class='ComposeNode' and @visible_text='$text']").present() }
    }

    /** Types [text] into the prompt box like a person and presses Send, checking each step landed. */
    private fun Driver.typePrompt(text: String) {
        promptBox().click(Point(20, 20))
        waitFor("prompt box focused", 30.seconds) { ui.x("$PROMPT_BOX[@focused='true']").present() }
        promptBox().keyboard { typeText(text, 20) }
        waitFor("prompt box holds '$text'", 30.seconds) { ui.x("$PROMPT_BOX[@editabletext='$text']").present() }
        clickButton("Send")
        waitFor("prompt box cleared after Send", 30.seconds) { ui.x("$PROMPT_BOX[@editabletext='']").present() }
    }

    private fun Driver.clickButton(text: String) {
        button(text).click(Point(12, 10))
    }

    private val JsonObject.type: String? get() = this["type"]?.jsonPrimitive?.content

    private companion object {
        const val PROMPT_BOX = "//div[@class='ComposeNode' and @editabletext]"
    }
}
