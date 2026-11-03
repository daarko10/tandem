package dev.daarkolabs.tandem

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.closeAndDisableAllBalloonNotifications
import com.intellij.driver.sdk.findFile
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.DefaultIdeDistributionFactory
import com.intellij.ide.starter.ide.IdeInstaller
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.awt.Point
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * What the IDE Starter tests share: starting an IDE with the built plugin and the fake CLI (runChat, ideContext, runTandem), and the
 * Driver helpers that find and use Tandem's controls. ToolWindowIdeTest and DemoRecordingIdeTest both use them.
 */

private val pluginZip: Path get() = Path.of(System.getProperty("tandem.pluginZip"))
private val fakeClaude: String get() = System.getProperty("tandem.fakeClaude")
private val ideVersion: String get() = System.getProperty("tandem.ideVersion")

/**
 * The IDE under test: the release we compile against, downloaded and unpacked under out/ide-tests, or with `tandem.ideHome` set, the IDE
 * already unpacked there (the Gradle cache's `idea-<version>` of the same release, say), which saves the download's 1.3 GB and the
 * unpacked copy's 4 GB on a small disk.
 */
private fun testCase(project: Path): TestCase<LocalProjectInfo> {
    val home = System.getProperty("tandem.ideHome").orEmpty()
    // withVersion puts the public downloader back, so it is only for the downloaded IDE.
    if (home.isBlank()) return TestCase(IdeProductProvider.IU, LocalProjectInfo(project)).withVersion(ideVersion)
    return TestCase(IdeProductProvider.IU.copy(getInstaller = { InPlaceIdeInstaller(Path.of(home)) }), LocalProjectInfo(project))
}

/** Runs the IDE unpacked at [home] where it is: the Starter's own ExistingIdeInstaller copies it into out/ide-tests first. */
private class InPlaceIdeInstaller(private val home: Path) : IdeInstaller {
    override suspend fun install(ideInfo: IdeInfo): Pair<String, InstalledIde> {
        val build = Regex("\"buildNumber\"\\s*:\\s*\"([^\"]+)\"").find(home.resolve("product-info.json").readText())?.groupValues?.get(1)
            ?: error("no buildNumber in ${home.resolve("product-info.json")}")
        return build to DefaultIdeDistributionFactory.installIDE(home.toFile(), ideInfo.executableFileName)
    }
}

/** The fake CLI's fixture folder, `src/test/resources/fixtures`, next to `fake-claude.py`. */
internal val fixturesDir: Path get() = Path.of(fakeClaude).resolveSibling("fixtures")

/**
 * [waitFor] for the edit tests, which spend long in the diff: a locally cached IDE in its trial may cover the window with JetBrains'
 * modal feedback dialog at any time, which is dismissed (`No, Thanks`) on every poll; it is never Tandem's.
 */
internal fun Driver.editWait(what: String, timeout: kotlin.time.Duration, condition: () -> Boolean) {
    waitFor(what, timeout) {
        ui.xx(TRIAL_FEEDBACK_NO).list().forEach { it.click() }
        condition()
    }
}

/** Writes `hello.txt` (`alpha\nline two\n`) into [project], the file the `ide-edit*` fixtures' calls name (spec 9.6). */
internal fun writeHello(project: Path): Path = project.resolve("hello.txt").also { it.writeText(HELLO) }

/** A Swing control of the diff's bar, by its Swing name (IDE-edits spec 3.8). */
internal fun Driver.diffButton(swingName: String): UiComponent = ui.x("//div[@name='$swingName']")

/** Whether the diff's editor tab (or window), titled `Tandem: hello.txt`, is shown: the tabs and the tab's label both name it. */
internal fun Driver.diffTabShown(): Boolean =
    ui.xx("//div[contains(@visible_text, 'Tandem: hello.txt') or contains(@accessiblename, 'Tandem: hello.txt')]").list().isNotEmpty()

/** The text of [relativePath]'s `Document` in the IDE: what its editor shows. */
internal fun Driver.documentText(relativePath: String): String? = withContext(OnDispatcher.EDT) {
    findFile(relativePath)?.let { service<FileDocumentManagerRef>().getDocument(it) }?.getText()
}

/** The Tandem tool window. Call on the EDT. */
internal fun Driver.tandemWindow(): ToolWindowRef =
    service<ToolWindowManagerRef>(singleProject()).getToolWindow("Tandem") ?: error("no Tandem tool window")

/** Makes the tool window wide enough to dock the sidebar: the IDE gives it less than 500 dp. */
internal fun Driver.widenToolWindow() {
    withContext(OnDispatcher.EDT) {
        val window = service<ToolWindowManagerRef>(singleProject()).getToolWindow("Tandem") ?: error("no Tandem tool window")
        val width = window.getComponent().getWidth()
        if (width < WIDE) window.stretchWidth(WIDE - width)
    }
}

/** A new project folder with one file. */
internal fun newProject(): Path {
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
 * [extraEnv] adds variables to the IDE's environment, which the fake CLI inherits; with [browserLog] a link the plugin opens in
 * the browser is a `{"type":"__browse__","url":…}` line in [sentLog] instead (`tandem.browserOpener=log`). [systemProperties] are
 * set in the IDE's JVM (`tandem.addFolder`, say).
 */
internal fun runChat(
    name: String,
    fixtures: List<String>,
    configDir: Path? = null,
    gateFile: Path? = null,
    project: Path = newProject(),
    sentLog: Path = Files.createTempFile("tandem-sent", ".ndjson"),
    lineDelayMs: Int? = null,
    extraEnv: Map<String, String> = emptyMap(),
    browserLog: Boolean = false,
    systemProperties: Map<String, String> = emptyMap(),
    script: Driver.() -> Unit,
): List<JsonObject> {
    ideContext(
        name,
        fixtures,
        configDir,
        gateFile,
        project,
        sentLog,
        lineDelayMs,
        extraEnv = extraEnv,
        browserLog = browserLog,
        systemProperties = systemProperties,
    ).runTandem(name, script)
    return if (sentLog.exists()) sentLog.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject } else emptyList()
}

/** The IDE [runChat] starts, with the plugin and the fake CLI; each [runTandem] on it starts it again, with what it kept. */
internal fun ideContext(
    name: String,
    fixtures: List<String>,
    configDir: Path?,
    gateFile: Path?,
    project: Path,
    sentLog: Path,
    lineDelayMs: Int? = null,
    extraEnv: Map<String, String> = emptyMap(),
    browserLog: Boolean = false,
    systemProperties: Map<String, String> = emptyMap(),
): IDETestContext =
    Starter.newContext(name, testCase(project)).apply {
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
            extraEnv.forEach { (key, value) -> withEnv(key, value) }
            if (browserLog) addSystemProperty("tandem.browserOpener", "log")
            systemProperties.forEach { (key, value) -> addSystemProperty(key, value) }
        }
    }

/**
 * Starts this IDE, opens the tool window, runs [script], takes a screenshot named [shot] and closes the IDE. Fails on any error
 * the IDE's log attributes to the plugin.
 */
internal fun IDETestContext.runTandem(shot: String, script: Driver.() -> Unit) {
    val context = this
    context.runIdeWithDriver().useDriverAndCloseIde {
        waitFor("project opened", 5.minutes) { getOpenProjects().isNotEmpty() }
        waitFor("Tandem plugin loaded", 2.minutes) { isPluginLoaded("dev.daarkolabs.tandem") }
        // A freshly opened IDE is busy (indexing, startup activities) and drops early keystrokes.
        waitForIndicators(5.minutes)
        // IDE balloons (e.g. about JVM options) can cover the prompt box and swallow clicks.
        closeAndDisableAllBalloonNotifications()
        // A locally cached IDE whose trial has run a few days asks for feedback in a modal dialog that covers everything.
        dismissTrialFeedback()
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

/**
 * Closes the trial's `Tell us about your experience` dialog with `No, Thanks` if it shows within 2 s (the IDE is idle by then): a JetBrains
 * dialog of a locally cached IDE, not Tandem's, which would take every click and key of the test.
 */
internal fun Driver.dismissTrialFeedback() {
    val noThanks = ui.x(TRIAL_FEEDBACK_NO)
    val shown = runCatching { waitFor("the trial feedback dialog", 2.seconds) { noThanks.present() } }.isSuccess
    if (shown) {
        noThanks.click()
        waitFor("the trial feedback dialog closed", 30.seconds) { !noThanks.present() }
    }
}

internal fun Driver.promptBox(): UiComponent = ui.x(PROMPT_BOX)

internal fun Driver.button(text: String): UiComponent = ui.x("//div[@class='ComposeNode' and @role='Button' and @text='$text']")

/** The sidebar row whose text (title, time, badge) contains [title] and each of [also]. */
internal fun Driver.row(title: String, vararg also: String): UiComponent =
    ui.x("$ROW[" + (listOf(title) + also).joinToString(" and ") { "contains(@text, '$it')" } + "]")

/** An icon button, found by what it says to assistive technology. */
internal fun Driver.iconButton(description: String): UiComponent =
    ui.x("//div[@class='ComposeNode' and @role='Button' and @contentdescription='$description']")

/**
 * The node that assistive technology knows as [description]: a tool row (a lone call, a group or a group's sub-row) by its label,
 * an MCP server row (`echo, Connected`), the MCP panel.
 */
internal fun Driver.described(description: String): UiComponent =
    ui.x("//div[@class='ComposeNode' and @contentdescription='$description']")

/** The sidebar's filter. */
internal fun Driver.filterBox(): UiComponent = ui.x("$EDITABLE[@contentdescription='Filter sessions']")

/** The header's "+". */
internal fun Driver.newSessionButton(): UiComponent = iconButton("New session")

/**
 * Creates a session with the header's "+" and the popup's defaults; it is selected. The popup is a window of its own, and a
 * click that lands while it is still being placed moves nothing (seen once on a loaded CI runner: the popup open, no session),
 * so a Create that leaves the popup open is pressed once more, and the helper returns only with the new session's prompt box.
 */
internal fun Driver.newSession() {
    newSessionButton().click(Point(10, 10))
    waitFor("the new-session popup", 30.seconds) { button("Create").present() }
    clickButton("Create")
    val closed = runCatching { waitFor("the popup closed", 5.seconds) { !button("Create").present() } }.isSuccess
    if (!closed) clickButton("Create")
    waitFor("the new session's prompt box", 30.seconds) { promptBox().present() }
}

/** Opens the sidebar over the chat with the header's toggle. */
internal fun Driver.openSidebar() {
    iconButton("Show sessions").click(Point(10, 10))
}

internal fun Driver.waitForText(text: String) {
    waitFor("'$text' rendered", 2.minutes) { text(text).present() }
}

/** The node whose text is [text]. */
internal fun Driver.text(text: String): UiComponent = ui.x("//div[@class='ComposeNode' and @visible_text='$text']")

/** Types [text] into the prompt box like a person, [keyDelayMs] between keys, and presses Send, checking each step landed. */
internal fun Driver.typePrompt(text: String, keyDelayMs: Long = 20) {
    promptBox().click(Point(20, 20))
    waitFor("prompt box focused", 30.seconds) { ui.x("$PROMPT_BOX[@focused='true']").present() }
    promptBox().keyboard { typeText(text, keyDelayMs) }
    waitFor("prompt box holds '$text'", 30.seconds) { ui.x("$PROMPT_BOX[@editabletext='$text']").present() }
    iconButton("Send").click(Point(10, 10))
    waitFor("prompt box cleared after Send", 30.seconds) { ui.x("$PROMPT_BOX[@editabletext='']").present() }
}

internal fun Driver.clickButton(text: String) {
    button(text).click(Point(12, 10))
}

/** How wide [widenToolWindow] makes the tool window, in pixels: past 500 dp at the scale the tests run at. */
internal const val WIDE = 900

internal const val EDITABLE = "//div[@class='ComposeNode' and @editabletext]"

/** `hello.txt` as the `ide-edit*` IDE tests write it, and after the recorded `alpha` → `beta` edit. */
internal const val HELLO = "alpha\nline two\n"

internal const val BETA = "beta\nline two\n"

/** The diff bar's controls: their Swing names and accessible names (IDE-edits spec 3.8). */
internal const val ACCEPT_BUTTON = "tandem.edit.accept"

/** The `No, Thanks` button of JetBrains' trial feedback dialog (`Tell us about your experience`). */
internal const val TRIAL_FEEDBACK_NO = "//div[@class='JButton' and (@visible_text='No, Thanks' or @accessiblename='No, Thanks')]"

/** Every sidebar row. */
internal const val ROW = "//div[@class='ComposeNode' and @role='Tab']"

/**
 * The prompt box: the last editable text, since a docked sidebar's filter box comes first. A sidebar open over the chat
 * comes after it, so the tests type prompts only while it is closed.
 */
internal const val PROMPT_BOX = "($EDITABLE)[last()]"

/**
 * Places the IDE's frame at the screen's top left, [width] by [height] pixels, the area the demo recording grabs: under Xvfb no
 * window manager maximizes it.
 */
internal fun Driver.placeFrame(width: Int, height: Int) {
    withContext(OnDispatcher.EDT) {
        val frame = service<WindowManagerRef>().getFrame(singleProject()) ?: error("no IDE frame")
        frame.setBounds(0, 0, width, height)
        frame.validate()
    }
}

/** The platform's `WindowManager`, for the project's frame. */
@Remote("com.intellij.openapi.wm.WindowManager")
interface WindowManagerRef {
    fun getFrame(project: Project?): FrameRef?
}

@Remote("javax.swing.JFrame")
interface FrameRef {
    fun setBounds(x: Int, y: Int, width: Int, height: Int)

    fun validate()
}
