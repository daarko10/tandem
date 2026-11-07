package dev.daarkolabs.tandem.bench

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RemoteMouseButton
import com.intellij.driver.sdk.closeAndDisableAllBalloonNotifications
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.remote.Component
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.runner.Starter
import dev.daarkolabs.tandem.dismissTrialFeedback
import dev.daarkolabs.tandem.newSession
import dev.daarkolabs.tandem.newSessionButton
import dev.daarkolabs.tandem.placeFrame
import dev.daarkolabs.tandem.promptBox
import dev.daarkolabs.tandem.testCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Point
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Time to first token (docs/benchmarks.md): one test per plugin, each a fresh IDE start that sends the cold prompt, waits for the reply,
 * then sends the warm prompt in the same session. Before each Enter it appends a line to the marks file (`tandem.bench.marks`, JSON
 * lines) with the Enter's epoch millisecond, the screen region the plugin's UI covers and the anchor the detector looks under; a run
 * that gets no reply appends a failure with a screenshot instead. `scripts/bench/ttft/run.sh` records the screen meanwhile and finds
 * the first frame showing the reply afterwards (`detect.py`); this test only waits for that reply on screen with the same detector, to
 * know when to send the next prompt.
 *
 * Only the `benchTtft` task runs this class (it sets `tandem.bench=true`), against the real `claude` (`tandem.bench.claude`).
 */
class TtftBenchmarkIdeTest {
    @BeforeEach
    fun onlyWhenBenchmarking() {
        assumeTrue(System.getProperty("tandem.bench") == "true", "run by ./gradlew benchTtft")
    }

    @Test
    fun floor() = measure(Floor)

    @Test
    fun tandem() = measure(TandemAdapter)

    @Test
    fun anthropic() = measure(AnthropicAdapter)

    @Test
    fun air() = measure(AirAdapter)

    @Test
    fun ccgui() = measure(CcGuiAdapter)

    @Test
    fun ccwithgui() = measure(CcWithGuiAdapter)
}

internal const val PROMPT = "Reply with exactly the single word PONG and nothing else."
internal const val PROMPT_WARM = "$PROMPT (2)"
internal const val SCREEN_WIDTH = 1600
internal const val SCREEN_HEIGHT = 1000
internal val REPLY_TIMEOUT: Duration = 90.seconds
internal const val KEY_DELAY_MS = 15L
private val JCEF_REGISTRY: Map<String, String> = mapOf("ide.browser.jcef.sandbox.enable" to "false", "ide.browser.jcef.run-under-superuser.allowed" to "true")

/** A screen rectangle, in pixels. */
internal data class Region(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun arg(): String = "$x,$y,$width,$height"
}

/** How the benchmark drives one plugin. */
internal abstract class Adapter(val key: String, val name: String) {
    /** The plugin ZIPs to install, and the version measured. */
    abstract fun install(): Pair<List<Path>, String>

    open val coldPrompt: String = PROMPT
    open val warmPrompt: String = PROMPT_WARM

    /** A line of the prompt's echo, which the reply is drawn under (detect.py). */
    open val coldAnchor: String = "nothing\\s*else"
    open val warmAnchor: String = "\\(2\\)"

    /** System properties for the IDE. */
    open val ideProperties: Map<String, String> = emptyMap()

    /** Opens the plugin's UI ready for a prompt and returns the region it covers on screen (null: the whole screen). */
    abstract fun Driver.open(): Region?

    /** Puts the keyboard focus where the prompt is typed. */
    abstract fun Driver.focusInput()

    /** The region to recompute after a reply, when the UI moves (the default keeps the first). */
    open fun Driver.regionAfter(previous: Region?): Region? = previous

    /** Regions of the screen the detector ignores, such as a prompt box that keeps the prompt after Enter. */
    open fun Driver.masks(): List<Region> = emptyList()
}

private val benchDir: Path get() = Path.of(System.getProperty("tandem.bench.dir"))
private val runIndex: Int get() = System.getProperty("tandem.bench.run").toInt()
private val marksFile: Path get() = Path.of(System.getProperty("tandem.bench.marks"))
internal val claudePath: String get() = System.getProperty("tandem.bench.claude")
private val detector: String get() = System.getProperty("tandem.bench.detector")

private fun mark(line: JsonObject) {
    marksFile.parent.createDirectories()
    Files.writeString(marksFile, line.toString() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
}

private fun now(): Long = System.currentTimeMillis()

/** Grabs the whole screen into [out] with ffmpeg (the display the IDE runs on). */
internal fun grabScreen(out: Path) {
    out.parent.createDirectories()
    val display = System.getenv("DISPLAY") ?: error("no DISPLAY")
    exec(listOf("ffmpeg", "-v", "error", "-y", "-f", "x11grab", "-video_size", "${SCREEN_WIDTH}x$SCREEN_HEIGHT", "-i", display, "-frames:v", "1", out.toString()))
}

private fun exec(command: List<String>, timeout: Duration = 1.minutes): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(timeout.inWholeSeconds, TimeUnit.SECONDS)) { "${command[0]} timed out" }
    check(process.exitValue() == 0) { "${command[0]} failed (${process.exitValue()}): ${output.take(500)}" }
    return output
}

/**
 * Waits until the screen shows the reply to the prompt sent at [enterMs], as detect.py reads it: the frame grabbed just before the
 * Enter ([before]) gives the PONG lines already there. Returns whether it came within [REPLY_TIMEOUT].
 */
private fun waitForReplyOnScreen(before: Path, enterMs: Long, anchor: String, region: Region?, masks: List<Region>): Boolean {
    val folder = Files.createTempDirectory("ttft-wait")
    Files.copy(before, folder.resolve("${enterMs - 1}.png"))
    val deadline = enterMs + REPLY_TIMEOUT.inWholeMilliseconds
    while (now() < deadline) {
        val frame = folder.resolve("${now()}.png")
        grabScreen(frame)
        val args = mutableListOf("python3", "-I", detector, "--frames", folder.toString(), "--enter", enterMs.toString(), "--anchor", anchor, "--timeout", "100000")
        if (region != null) args += listOf("--region", region.arg())
        masks.forEach { args += listOf("--mask", it.arg()) }
        val result = Json.parseToJsonElement(exec(args).lines().last { it.isNotBlank() }).jsonObject
        Files.delete(frame)
        if (result["hit"]?.jsonPrimitive?.content == "true") return true
        Thread.sleep(500)
    }
    return false
}

/** Starts the IDE with [adapter]'s plugin on a small project and measures one cold and one warm prompt. */
internal fun measure(adapter: Adapter) {
    val run = runIndex
    val (zips, version) = adapter.install()
    val project = Files.createTempDirectory("tandem-ttft").resolve("pong").createDirectories()
    project.resolve("README.md").writeText("# Pong\n\nA small project for the time-to-first-token benchmark.\n")
    val context = Starter.newContext("ttft-${adapter.key}-$run", testCase(project)).apply {
        zips.forEach { PluginConfigurator(this).installPluginFromPath(it) }
        // The benchmark's IDE may run as root (a container), where JCEF refuses its sandbox and suspends every webview: these registry
        // keys run JCEF without it, as the IDE's own notification offers.
        val options = paths.configDir.resolve("options").createDirectories()
        options.resolve("ide.general.xml").writeText(
            "<application>\n  <component name=\"Registry\">\n" +
                JCEF_REGISTRY.entries.joinToString("") { "    <entry key=\"${it.key}\" value=\"${it.value}\" />\n" } +
                "  </component>\n</application>\n",
        )
        paths.configDir.resolve("early-access-registry.txt").writeText(JCEF_REGISTRY.entries.joinToString("") { "${it.key}\n${it.value}\n" })
        applyVMOptionsPatch {
            addSystemProperty("idea.trust.all.projects", true)
            addSystemProperty("jb.consents.confirmation.enabled", false)
            addSystemProperty("ide.show.tips.on.startup.default.value", false)
            JCEF_REGISTRY.forEach { (key, value) -> addSystemProperty(key, value) }
            // The CLI stays at the version measured.
            withEnv("DISABLE_AUTOUPDATER", "1")
            // The same model for every plugin that runs the CLI with its defaults (docs/benchmarks.md).
            withEnv("ANTHROPIC_MODEL", "sonnet")
            adapter.ideProperties.forEach { (key, value) -> addSystemProperty(key, value) }
        }
    }
    fun base(phase: String) = buildJsonObject {
        put("plugin", adapter.key)
        put("name", adapter.name)
        put("version", version)
        put("run", run)
        put("phase", phase)
        // The CLI keys its transcripts on this folder: run.sh reads the model it answered with from there.
        put("project", project.toRealPath().toString())
    }
    fun fail(phase: String, reason: String) {
        val shot = benchDir.resolve("screens/${adapter.key}-$phase-$run.png")
        runCatching { grabScreen(shot) }
        mark(JsonObject(base(phase) + mapOf("failure" to JsonPrimitive(reason), "screenshot" to JsonPrimitive(shot.toString()))))
    }
    context.runIdeWithDriver().useDriverAndCloseIde {
        waitFor("project opened", 5.minutes) { getOpenProjects().isNotEmpty() }
        waitForIndicators(5.minutes)
        closeAndDisableAllBalloonNotifications()
        dismissTrialFeedback()
        placeFrame(SCREEN_WIDTH, SCREEN_HEIGHT)
        var region = try {
            with(adapter) { open() }
        } catch (e: Throwable) {
            fail("cold", "could not open the plugin's UI: ${e.message?.lines()?.first()}")
            fail("warm", "no cold run")
            return@useDriverAndCloseIde
        }
        var ok = true
        for ((phase, prompt, anchor) in listOf(Triple("cold", adapter.coldPrompt, adapter.coldAnchor), Triple("warm", adapter.warmPrompt, adapter.warmAnchor))) {
            if (!ok) {
                fail(phase, "no reply to the cold prompt")
                continue
            }
            try {
                with(adapter) { focusInput() }
                ui.keyboard { typeText(prompt, KEY_DELAY_MS) }
                // Let the last key land before the Enter's mark.
                Thread.sleep(700)
                val masks = with(adapter) { masks() }
                val before = benchDir.resolve("screens/before-${adapter.key}-$phase-$run.png")
                grabScreen(before)
                val enterMs = now()
                ui.keyboard { enter() }
                mark(
                    JsonObject(
                        base(phase) + mapOf(
                            "enter_ms" to JsonPrimitive(enterMs),
                            "anchor" to JsonPrimitive(anchor),
                            "region" to (region?.let { JsonPrimitive(it.arg()) } ?: JsonPrimitive("")),
                            "masks" to JsonArray(masks.map { JsonPrimitive(it.arg()) }),
                        ),
                    ),
                )
                ok = waitForReplyOnScreen(before, enterMs, anchor, region, masks)
                if (!ok) {
                    fail(phase, "timeout")
                    continue
                }
                region = with(adapter) { regionAfter(region) }
                // The reply settles (the turn's end, the footer) before the warm prompt.
                Thread.sleep(3000)
            } catch (e: Throwable) {
                ok = false
                fail(phase, "error: ${e.message?.lines()?.first()}")
            }
        }
    }
}

/** Downloads (once, into build/bench/plugins) the newest Marketplace update of plugin [id] whose file name contains [fileContains]. */
internal fun marketplacePlugin(id: Int, fileContains: String = ""): Pair<List<Path>, String> {
    val updates = Json.parseToJsonElement(URI("https://plugins.jetbrains.com/api/plugins/$id/updates?size=20").toURL().readText()).jsonArray
    val update = updates.map { it.jsonObject }.first { fileContains in it["file"]!!.jsonPrimitive.content }
    val file = update["file"]!!.jsonPrimitive.content
    val version = update["version"]!!.jsonPrimitive.content
    val target = benchDir.resolve("plugins").resolve(file.substringAfterLast('/')).also { it.parent.createDirectories() }
    if (!target.exists()) {
        val partial = target.resolveSibling(target.fileName.toString() + ".part")
        URI("https://plugins.jetbrains.com/files/$file").toURL().openStream().use { Files.copy(it, partial, StandardCopyOption.REPLACE_EXISTING) }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    }
    return listOf(target) to version
}

/** The bounds on screen of tool window [id]'s component. */
internal fun Driver.toolWindowRegion(id: String): Region = withContext(OnDispatcher.EDT) {
    val component = service<BenchToolWindowManager>(singleProject()).getToolWindow(id)?.getComponent() ?: error("no $id tool window")
    val at = component.getLocationOnScreen()
    Region(at.x, at.y, component.width, component.height)
}

/** The bounds on screen of a found UI component. */
internal fun UiComponent.region(): Region {
    val at = component.getLocationOnScreen()
    return Region(at.x, at.y, component.width, component.height)
}

/** The method's floor: the IDE's own terminal echoing PONG. */
internal object Floor : Adapter("floor", "Floor: IDE terminal, `echo PONG`") {
    override fun install(): Pair<List<Path>, String> = emptyList<Path>() to ""

    override val coldPrompt = "echo PONG"
    override val warmPrompt = "echo PONG"
    override val coldAnchor = "echo"
    override val warmAnchor = "echo"

    override fun Driver.open(): Region? {
        openToolWindow("Terminal")
        // The shell's first prompt.
        Thread.sleep(8000)
        return toolWindowRegion("Terminal")
    }

    override fun Driver.focusInput() {
        withContext(OnDispatcher.EDT) { service<BenchToolWindowManager>(singleProject()).getToolWindow("Terminal")?.activate(null) }
        Thread.sleep(500)
    }
}

/** Tandem, the ZIP this branch builds. */
internal object TandemAdapter : Adapter("tandem", "Tandem") {
    override fun install(): Pair<List<Path>, String> {
        val zip = Path.of(System.getProperty("tandem.pluginZip"))
        return listOf(zip) to zip.fileName.toString().removePrefix("tandem-").removeSuffix(".zip")
    }

    override val ideProperties: Map<String, String> get() = mapOf("tandem.claudePath" to claudePath)

    override fun Driver.open(): Region? {
        openToolWindow("Tandem")
        waitFor("Tandem's tool window", 2.minutes) { newSessionButton().present() }
        newSession()
        return toolWindowRegion("Tandem")
    }

    override fun Driver.focusInput() {
        promptBox().click(Point(20, 20))
        waitFor("prompt box focused", 30.seconds) { ui.x("(//div[@class='ComposeNode' and @editabletext])[last()][@focused='true']").present() }
    }
}

/** What tesseract reads in [region] of the screen now (the whole screen without one). */
internal fun screenText(region: Region? = null): String {
    val shot = Files.createTempFile("ttft-screen", ".png")
    try {
        grabScreen(shot)
        if (region != null) {
            val crop = Files.createTempFile("ttft-crop", ".png")
            exec(listOf("ffmpeg", "-v", "error", "-y", "-i", shot.toString(), "-vf", "crop=${region.width}:${region.height}:${region.x}:${region.y},scale=iw*2:ih*2", crop.toString()))
            Files.move(crop, shot, StandardCopyOption.REPLACE_EXISTING)
        }
        return exec(listOf("tesseract", shot.toString(), "stdout", "--psm", "3"))
    } finally {
        Files.deleteIfExists(shot)
    }
}

/** A line of text tesseract read on the screen, in screen pixels. */
internal data class ScreenLine(val text: String, val x: Int, val y: Int, val width: Int, val height: Int) {
    val center: Point get() = Point(x + width / 2, y + height / 2)
}

/** The lines of text on the screen (or in [region]), read at twice the size, in screen coordinates; with [words], each word alone. */
internal fun screenLines(region: Region? = null, words: Boolean = false): List<ScreenLine> {
    val shot = Files.createTempFile("ttft-screen", ".png")
    try {
        grabScreen(shot)
        val r = region ?: Region(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT)
        val crop = Files.createTempFile("ttft-crop", ".png")
        exec(listOf("ffmpeg", "-v", "error", "-y", "-i", shot.toString(), "-vf", "crop=${r.width}:${r.height}:${r.x}:${r.y},scale=iw*2:ih*2,format=gray", crop.toString()))
        val tsv = exec(listOf("tesseract", crop.toString(), "stdout", "--psm", "3", "tsv"))
        Files.deleteIfExists(crop)
        val rows = tsv.lines().drop(1).map { it.split('\t') }.filter { it.size == 12 && it[0] == "5" && it[11].isNotBlank() }
        return rows.groupBy { if (words) it.subList(1, 6).joinToString(",") else it.subList(1, 5).joinToString(",") }.values.map { line ->
            val left = line.minOf { it[6].toInt() }
            val top = line.minOf { it[7].toInt() }
            val right = line.maxOf { it[6].toInt() + it[8].toInt() }
            val bottom = line.maxOf { it[7].toInt() + it[9].toInt() }
            ScreenLine(line.joinToString(" ") { it[11] }, r.x + left / 2, r.y + top / 2, (right - left) / 2, (bottom - top) / 2)
        }
    } finally {
        Files.deleteIfExists(shot)
    }
}

/** A click as a person makes it: the pointer moves there first, and the button is held for a moment (a webview misses instant clicks). */
internal fun Driver.slowClick(point: Point) {
    ui.moveMouse(point)
    Thread.sleep(300)
    ui.robot.pressMouse(RemoteMouseButton.LEFT)
    Thread.sleep(120)
    ui.robot.releaseMouse(RemoteMouseButton.LEFT)
    Thread.sleep(300)
}

/** Waits for a line (or with [word], a word) matching [pattern] on the screen (or in [region]) and clicks its middle. */
internal fun Driver.clickScreenText(what: String, pattern: Regex, region: Region? = null, timeout: Duration = 1.minutes, word: Boolean = false) {
    val deadline = now() + timeout.inWholeMilliseconds
    var seen = emptyList<ScreenLine>()
    while (now() < deadline) {
        seen = screenLines(region, word)
        val line = seen.firstOrNull { pattern.containsMatchIn(it.text) }
        if (line != null) {
            slowClick(line.center)
            Thread.sleep(1000)
            return
        }
        Thread.sleep(1000)
    }
    error("$what: no '$pattern' on screen after $timeout; read: ${seen.joinToString(" | ") { it.text }.take(400)}")
}

/** Waits for a line matching [pattern] on the screen (or in [region]) and returns it. */
internal fun waitForLine(what: String, pattern: Regex, region: Region? = null, timeout: Duration = 1.minutes): ScreenLine {
    val deadline = now() + timeout.inWholeMilliseconds
    while (now() < deadline) {
        screenLines(region).firstOrNull { pattern.containsMatchIn(it.text) }?.let { return it }
        Thread.sleep(1000)
    }
    error("$what: no '$pattern' on screen after $timeout")
}

/** Waits until the screen (or [region]) shows text matching [pattern] and returns that text. */
internal fun waitForScreenText(what: String, pattern: Regex, region: Region? = null, timeout: Duration = 1.minutes): String =
    waitForScreen(what, region, timeout) { pattern.containsMatchIn(it) }

/** Waits until the text tesseract reads on the screen (or [region]) satisfies [ok], and returns that text. */
internal fun waitForScreen(what: String, region: Region?, timeout: Duration, ok: (String) -> Boolean): String {
    val deadline = now() + timeout.inWholeMilliseconds
    var last = ""
    while (now() < deadline) {
        last = screenText(region)
        if (ok(last)) return last
        Thread.sleep(1000)
    }
    error("$what: not on screen after $timeout; last read: ${last.replace('\n', ' ').take(300)}")
}

/** Anthropic's `Claude Code [Beta]`: its action runs `claude` in the IDE's terminal, where the prompt is typed into the CLI's own UI. */
internal object AnthropicAdapter : Adapter("anthropic", "Claude Code [Beta] (Anthropic)") {
    override fun install(): Pair<List<Path>, String> = marketplacePlugin(27310)

    override fun Driver.open(): Region? {
        invokeAction("com.anthropic.code.plugin.actions.OpenClaudeInTerminalAction")
        waitFor("the terminal", 1.minutes) { runCatching { toolWindowRegion("Terminal") }.isSuccess }
        val region = toolWindowRegion("Terminal")
        // A folder the CLI has not seen asks whether to trust it first; the selected answer is `No, exit`, the next one `Yes`.
        val first = waitForScreenText("the CLI's UI", Regex("trust this folder|for shortcuts|Try \"", RegexOption.IGNORE_CASE), region, 2.minutes)
        if (Regex("trust this folder", RegexOption.IGNORE_CASE).containsMatchIn(first)) {
            ui.keyboard {
                down()
                enter()
            }
            Thread.sleep(2000)
        }
        // Its prompt line names the shortcuts; a UI that words it otherwise gets the same 30 s and is then typed into anyway.
        runCatching { waitForScreenText("the CLI's prompt", Regex("for shortcuts|Try \"|\\? for", RegexOption.IGNORE_CASE), region, 30.seconds) }
        Thread.sleep(1500)
        return toolWindowRegion("Terminal")
    }

    override fun Driver.focusInput() {
        withContext(OnDispatcher.EDT) { service<BenchToolWindowManager>(singleProject()).getToolWindow("Terminal")?.activate(null) }
        Thread.sleep(500)
    }
}

/** Text a webview shows while it is not ready for a prompt. */
private val NOT_READY = Regex("suspended|Waiting for backend|Loading|Initializing|starting", RegexOption.IGNORE_CASE)

/**
 * A plugin whose chat is a webview: [show] opens it and returns the region it covers, its prompt box is clicked [inputFromBottom]
 * pixels above that region's bottom edge, and the prompt is typed there. Ready means the region shows some text and none of [NOT_READY].
 */
internal abstract class WebviewAdapter(key: String, name: String, private val inputFromBottom: Int = 70) : Adapter(key, name) {
    abstract fun Driver.show(): Region

    private var region: Region? = null

    override fun Driver.open(): Region? {
        val shown = show()
        region = shown
        waitForScreen("$name's UI ready", shown, 2.minutes) { it.count { c -> c.isLetter() } > 20 && !NOT_READY.containsMatchIn(it) }
        Thread.sleep(3000)
        return shown
    }

    override fun Driver.focusInput() {
        val r = region ?: error("not open")
        slowClick(Point(r.x + r.width / 2, r.y + r.height - inputFromBottom))
        Thread.sleep(500)
    }
}

/** The editor area, where plugins that open their chat as an editor tab show it. */
internal fun Driver.editorRegion(): Region = ui.x("//div[@class='EditorsSplitters']").region()

internal object CcGuiAdapter : WebviewAdapter("ccgui", "CC GUI") {
    override fun install(): Pair<List<Path>, String> = marketplacePlugin(29342)

    override fun Driver.show(): Region {
        openToolWindow("CCG")
        Thread.sleep(2000)
        val region = toolWindowRegion("CCG")
        // On its first start the chat says the Claude Code SDK it runs is not installed: `Go to Install` opens its settings, where
        // the SDK is installed from npm, once, before any chat (setup, not timed). Then back to the chat.
        waitForScreen("CC GUI's first page", region, 2.minutes) { it.count { c -> c.isLetter() } > 20 && !NOT_READY.containsMatchIn(it) }
        // It checks for the SDK after the page shows.
        val first = runCatching { waitForScreenText("CC GUI's SDK check", Regex("Go to Install|not installed|Install v", RegexOption.IGNORE_CASE), region, 20.seconds) }.getOrDefault("")
        if (Regex("Go to Install|not installed|Install v", RegexOption.IGNORE_CASE).containsMatchIn(first)) {
            if (!Regex("Install v").containsMatchIn(first)) {
                // `Go to Install` is the small button at the right end of the warning's line.
                val warning = screenLines(region).first { Regex("not installed", RegexOption.IGNORE_CASE).containsMatchIn(it.text) }
                slowClick(Point(region.x + region.width - 60, warning.center.y))
                Thread.sleep(2000)
            }
            // The Install button's white-on-blue label does not OCR: it sits under the `Target version` line.
            val target = waitForLine("the SDK's target version", Regex("Target version"), region)
            slowClick(Point(target.x + 80, target.center.y + 45))
            Thread.sleep(3000)
            waitForScreen("the SDK installed", region, 5.minutes) { !Regex("Install v|Installing", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            grabScreen(benchDir.resolve("screens/ccgui-sdk-$runIndex.png"))
            // Back to the chat: the arrow at the left of the `Settings` title.
            val settings = waitForLine("CC GUI's settings title", Regex("Settings$"), Region(region.x, region.y, region.width, 80))
            slowClick(Point(region.x + 32, settings.center.y))
            Thread.sleep(3000)
        }
        if (System.getProperty("tandem.bench.explore") == "true") {
            slowClick(Point(region.x + region.width - 25, region.y + 25))
            Thread.sleep(3000)
            grabScreen(benchDir.resolve("screens/ccgui-settings-$runIndex.png"))
            runCatching { clickScreenText("Provider Management", Regex("Provider"), Region(region.x, region.y, 90, region.height), 10.seconds) }
            Thread.sleep(3000)
            grabScreen(benchDir.resolve("screens/ccgui-providers-$runIndex.png"))
        }
        return region
    }
}

internal object CcWithGuiAdapter : WebviewAdapter("ccwithgui", "Claude Code with GUI") {
    override fun install(): Pair<List<Path>, String> = marketplacePlugin(30313)

    override fun Driver.show(): Region {
        // It opens its chat as an editor tab named Claude Code on the project's first start; the action opens it otherwise.
        val tab = ui.x("//div[@class='EditorTabLabel' and contains(@visible_text, 'Claude Code')]")
        if (!runCatching { waitFor("its editor tab", 20.seconds) { tab.present() } }.isSuccess) invokeAction("ClaudeCode.Open")
        Thread.sleep(2000)
        val region = editorRegion()
        waitForScreenText("its chat", Regex("Past Conversations|Ask before edits|Enter to send", RegexOption.IGNORE_CASE), region, 2.minutes)
        // The usage-statistics question sits over the prompt box.
        runCatching { clickScreenText("Decline usage statistics", Regex("^Decline$"), region, 5.seconds) }
        // Its model picker shows another model by default: pick Sonnet (setup, not timed).
        // Best effort: the run records the model the CLI answered with either way.
        runCatching {
            clickScreenText("its model picker", Regex("^(Fable|Opus|Sonnet|Haiku|Default)$"), Region(region.x, region.y + region.height - 120, region.width, 120), word = true)
            grabScreen(benchDir.resolve("screens/ccwithgui-models-$runIndex.png"))
            clickScreenText("Sonnet in its model menu", Regex("Sonnet"), region, 10.seconds)
        }.onFailure { ui.keyboard { escape() } }
        grabScreen(benchDir.resolve("screens/ccwithgui-picked-$runIndex.png"))
        Thread.sleep(1000)
        return region
    }

    override fun Driver.focusInput() {
        val region = editorRegion()
        // Its prompt box takes the keyboard after a click on the box's text line.
        val box = screenLines(Region(region.x, region.y + region.height - 160, region.width, 160))
            .firstOrNull { Regex("reference files|Enter to send|Reply with", RegexOption.IGNORE_CASE).containsMatchIn(it.text) }
        val target = box?.center ?: Point(region.x + region.width / 2, region.y + region.height - 80)
        slowClick(target)
        Thread.sleep(500)
    }
}

/** JetBrains' Air, with Claude Code as its agent: its `New Session` button in the main toolbar, then the agent picker. */
internal object AirAdapter : WebviewAdapter("air", "Air (JetBrains)") {
    override fun install(): Pair<List<Path>, String> = marketplacePlugin(33314, "linux-x86_64")

    override fun Driver.show(): Region {
        val button = ui.x("//div[contains(@visible_text, 'New Session') or contains(@accessiblename, 'New Session')]")
        waitFor("Air's New Session button", 1.minutes) { button.present() }
        button.click()
        Thread.sleep(5000)
        val region = editorRegion()
        // The agent picker under the prompt box names Junie by default: pick Claude Code (setup, not timed).
        clickScreenText("Air's agent picker", Regex("Junie$"), Region(region.x, region.y + region.height / 2, region.width, region.height / 2))
        grabScreen(benchDir.resolve("screens/air-agents-$runIndex.png"))
        clickScreenText("Claude in Air's agent list", Regex("^Claude$"), region, 20.seconds, word = true)
        Thread.sleep(3000)
        // Air installs its Claude Agent (an ACP adapter for Claude Code) from the ACP registry on first use (setup, not timed).
        if (Regex("Install Claude Agent", RegexOption.IGNORE_CASE).containsMatchIn(screenText(region))) {
            clickScreenText("Install the Claude Agent", Regex("^Install$"), region, 20.seconds, word = true)
            Thread.sleep(5000)
            grabScreen(benchDir.resolve("screens/air-installing-$runIndex.png"))
            waitForScreen("the Claude Agent installed", region, 5.minutes) { !Regex("Install Claude Agent|Installing", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        }
        grabScreen(benchDir.resolve("screens/air-picked-$runIndex.png"))
        // Air's Claude agent signs in on its own: with no Anthropic API key it offers only its terminal view, whose sign-in Tandem's
        // benchmark never drives (it does not touch credentials).
        val shown = screenText(region)
        check(!Regex("Terminal view only|Add API Key", RegexOption.IGNORE_CASE).containsMatchIn(shown)) {
            "Air's Claude agent offers its chat view only with an Anthropic API key (it read: Claude subscription works in Terminal view only)"
        }
        return region
    }

    override fun Driver.focusInput() {
        val region = editorRegion()
        val box = screenLines(region).firstOrNull { Regex("Ask|Type|task|Reply with", RegexOption.IGNORE_CASE).containsMatchIn(it.text) }
        slowClick(box?.center ?: Point(region.x + region.width / 2, region.y + region.height / 2 - 30))
        Thread.sleep(600)
    }
}

@Remote("com.intellij.openapi.wm.ToolWindowManager")
internal interface BenchToolWindowManager {
    fun getToolWindow(id: String): BenchToolWindow?
}

@Remote("com.intellij.openapi.wm.ToolWindow")
internal interface BenchToolWindow {
    fun getComponent(): Component

    fun activate(runnable: Runnable?)
}
