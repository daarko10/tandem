package dev.daarkolabs.tandem

import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Installs the built plugin ZIP into a real IDE (the release we compile against), opens a project, and lets the
 * Tandem tool window talk to a fake `claude` that replays the `plain-reply` fixture. Unlike the platform tests,
 * the plugin is loaded by its own plugin classloader here, as it is for users, and the assertions read what the
 * Compose UI actually renders.
 *
 * Run with `xvfb-run ./gradlew integrationTest` when there is no display.
 */
class ToolWindowIdeTest {
    private val pluginZip = Path.of(System.getProperty("tandem.pluginZip"))
    private val fakeClaude = System.getProperty("tandem.fakeClaude")
    private val ideVersion = System.getProperty("tandem.ideVersion")

    @Test
    fun `tool window renders a reply from the CLI through the installed plugin`() {
        val project = Files.createTempDirectory("tandem-ide-test").resolve("demo").createDirectories()
        project.resolve("README.md").writeText("# Demo\n")

        val context = Starter.newContext(
            "toolWindowReply",
            TestCase(IdeProductProvider.IU, LocalProjectInfo(project)).withVersion(ideVersion),
        ).apply {
            PluginConfigurator(this).installPluginFromPath(pluginZip)
            applyVMOptionsPatch {
                addSystemProperty("tandem.claudePath", fakeClaude)
                // Opens the tool window on startup and sends one prompt (see ui/dev/RenderingSpike.kt).
                addSystemProperty("tandem.autoPrompt", "Reply with the single word: pong")
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
                addSystemProperty("ide.show.tips.on.startup.default.value", false)
            }
        }

        context.runIdeWithDriver().useDriverAndCloseIde {
            waitFor("project opened", 5.minutes) { getOpenProjects().isNotEmpty() }
            waitFor("Tandem plugin loaded", 2.minutes) { isPluginLoaded("dev.daarkolabs.tandem") }
            // Compose semantics nodes appear in the Driver's UI hierarchy as ComposeNode with their visible text.
            waitFor("prompt rendered", 2.minutes) { ui.x(composeText("Reply with the single word: pong")).present() }
            waitFor("reply rendered", 2.minutes) { ui.x(composeText("pong")).present() }
            waitFor("model shown in the status line", 1.minutes) {
                ui.x("//div[@class='ComposeNode' and starts-with(@visible_text, 'claude-sonnet-5-5')]").present()
            }
            // Kept as a test artifact for people to look at.
            takeScreenshot("tandem-reply")
        }

        // Any exception attributed to the plugin (e.g. a class from an undeclared IDE module) fails the test.
        val logs = context.paths.testHome.walk().filter { it.fileName.toString() == "idea.log" }.toList()
        assertTrue(logs.isNotEmpty(), "no idea.log under ${context.paths.testHome}")
        val pluginErrors = logs.flatMap { it.readLines() }.filter { "Plugin: dev.daarkolabs.tandem" in it }
        assertTrue(pluginErrors.isEmpty(), "errors attributed to Tandem:\n" + pluginErrors.joinToString("\n"))
    }

    private fun composeText(text: String): String = "//div[@class='ComposeNode' and @visible_text='$text']"
}
