package dev.daarkolabs.tandem

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.closeAndDisableAllBalloonNotifications
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The "open a long conversation" benchmark for Tandem (docs/benchmarks.md): `scripts/bench/make_long_session.py` writes a session of
 * 400 or 1000 turns into the CLI's transcript folder for the project, and the IDE, with the plugin installed, opens it from the
 * sidebar. Timed, from Enter on the focused sidebar row: until the last reply's text is in the chat; then, after Home in the transcript,
 * until the first prompt is. Each IDE run also counts the freezes the IDE itself reports in that time (`UI was frozen for` lines in
 * idea.log, `threadDumps-freeze-*` folders; the watcher's threshold is lowered to 1 s) and the slowest round trip of a no-op on the
 * EDT while waiting. The numbers go to stdout and to `build/reports/long-session/<turns>.json`. Nothing is asserted about the times:
 * the test fails only when a step does not finish within 60 s.
 */
@org.junit.jupiter.api.extension.ExtendWith(IdeTestShard::class)
class LongSessionIdeTest {
    private val pluginZip = Path.of(System.getProperty("tandem.pluginZip"))
    private val fakeClaude = System.getProperty("tandem.fakeClaude")
    private val ideVersion = System.getProperty("tandem.ideVersion")
    private val repoRoot: Path = Path.of(fakeClaude).toAbsolutePath().parent.parent.parent.parent

    @Test
    fun `a 1000-turn session opens from the sidebar`() = measure(1000)

    @Test
    fun `a 400-turn session opens from the sidebar`() = measure(400)

    private fun measure(turns: Int) {
        val project = Files.createTempDirectory("tandem-long").resolve("orders").createDirectories().toRealPath()
        project.resolve("README.md").writeText("# Orders\n")
        val configDir = Files.createTempDirectory("tandem-config")
        val transcript = generate(turns, project, configDir)
        val title = "Long session $turns"
        // A rename, as the sidebar's own Rename writes it, so the row is easy to find (both sizes share one generated title).
        transcript.appendText("""{"type":"custom-title","customTitle":"$title","sessionId":"${transcript.name.removeSuffix(".jsonl")}"}""" + "\n")

        val context = Starter.newContext("long-session-$turns", ideTestCase(project, ideVersion)).apply {
            PluginConfigurator(this).installPluginFromPath(pluginZip)
            applyVMOptionsPatch {
                addSystemProperty("tandem.claudePath", fakeClaude)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
                addSystemProperty("ide.show.tips.on.startup.default.value", false)
                addSystemProperty("performance.watcher.unresponsive.interval.ms", 1000)
                withEnv("TANDEM_FAKE_FIXTURES", "plain-reply")
                withEnv("CLAUDE_CONFIG_DIR", configDir.toString())
            }
        }
        val result = StringBuilder()
        context.runIdeWithDriver().useDriverAndCloseIde {
            waitFor("project opened", 5.minutes) { getOpenProjects().isNotEmpty() }
            waitFor("Tandem plugin loaded", 2.minutes) { isPluginLoaded("dev.daarkolabs.tandem") }
            waitForIndicators(5.minutes)
            closeAndDisableAllBalloonNotifications()
            dismissTrialFeedback()
            openToolWindow("Tandem")
            waitFor("tool window shown", 2.minutes) { ui.x(NEW_SESSION).present() }
            // The driver's own cost of a click, for comparison: opening the sidebar, which loads nothing.
            val baselineClick = timed { ui.x(SHOW_SESSIONS).click(Point(10, 10)) }
            val row = ui.x("$ROW[contains(@text, '$title')]")
            waitFor("the long session listed", 1.minutes) { row.present() }
            Thread.sleep(2_000) // let the IDE settle, so the freeze count starts from a quiet IDE

            val before = freezes(context)
            // Opened from the keyboard: the driver's mouse click alone takes about 4 s here (baselineClickMs), which would hide the
            // load. The sidebar opens with its filter focused; Tab focuses the (only) row and Enter opens it.
            waitFor("filter focused", 30.seconds) { ui.x("$EDITABLE[@focused='true' and @editabletext='']").present() }
            ui.keyboard { tab() }
            waitFor("the row focused", 30.seconds) { ui.x("$ROW[@focused='true' and contains(@text, '$title')]").present() }
            val open = timed { ui.keyboard { enter() } }
            val edtAfterClick = edtRoundTrip()
            val lastReply = ui.x("//div[@class='ComposeNode' and contains(@visible_text, 'Summary of the session ($turns turns)')]")
            val shown = poll("the last reply shown") { lastReply.present() }
            Thread.sleep(3_000) // a freeze is logged when it ends
            val afterOpen = freezes(context)
            if (turns == 1000) println("long-session screenshot: ${takeScreenshot("long-session-$turns")}")

            ui.x(TRANSCRIPT).click(Point(10, 10))
            val home = timed { ui.keyboard { key(KeyEvent.VK_HOME) } }
            val edtAfterHome = edtRoundTrip()
            val firstPrompt = ui.x("//div[@class='ComposeNode' and contains(@visible_text, 'Let us start with src/app/Orders.kt')]")
            val top = poll("the first prompt shown") { firstPrompt.present() }
            Thread.sleep(3_000)
            val afterTop = freezes(context)

            val openFreezes = afterOpen - before
            val topFreezes = afterTop - afterOpen
            result.append(
                """{"turns":$turns,"bytes":${transcript.fileSize()},"ide":"${productBuild(context)}","baselineClickMs":$baselineClick,""" +
                    """"open":{"enterMs":$open,"untilLastReplyMs":${open + shown.millis},"maxEdtRoundTripMs":${maxOf(edtAfterClick, shown.maxEdtMillis)},""" +
                    """"freezes":${openFreezes.count},"longestFreezeMs":${openFreezes.longest}},""" +
                    """"scrollToTop":{"keyMs":$home,"untilFirstPromptMs":${home + top.millis},"maxEdtRoundTripMs":${maxOf(edtAfterHome, top.maxEdtMillis)},""" +
                    """"freezes":${topFreezes.count},"longestFreezeMs":${topFreezes.longest}}}""",
            )
        }
        println("long-session: $result")
        val out = repoRoot.resolve("build/reports/long-session").createDirectories().resolve("$turns.json")
        out.writeText(result.toString() + "\n")
        println("long-session report: $out")
        assertTrue(result.isNotEmpty())
    }

    private fun generate(turns: Int, project: Path, configDir: Path): Path {
        val process = ProcessBuilder(
            "python3", "-I", repoRoot.resolve("scripts/bench/make_long_session.py").toString(),
            "--turns", "$turns", "--project", project.toString(), "--config", configDir.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(5, TimeUnit.MINUTES), "the generator did not finish")
        assertEquals(0, process.exitValue(), output)
        println("long-session generated: ${output.trim().replace('\n', ' ')}")
        return Path.of(output.lines().first())
    }

    private class Waited(val millis: Long, val maxEdtMillis: Long)

    private class Freezes(val count: Int, val longest: Long, val durations: List<Long>) {
        operator fun minus(earlier: Freezes): Freezes {
            val new = durations.drop(earlier.durations.size)
            return Freezes(count - earlier.count, new.maxOrNull() ?: 0, new)
        }
    }

    private fun timed(action: () -> Unit): Long {
        val start = System.nanoTime()
        action()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    /** Polls [condition] until it holds, at most 60 s, timing a no-op on the EDT between polls. */
    private fun Driver.poll(what: String, condition: () -> Boolean): Waited {
        val start = System.nanoTime()
        var maxEdt = 0L
        while (true) {
            if (condition()) return Waited(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), maxEdt)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            check(elapsed < 60_000) { "$what: not within 60 s (slowest EDT round trip ${maxEdt}ms)" }
            maxEdt = maxOf(maxEdt, edtRoundTrip())
            Thread.sleep(50)
        }
    }

    /** How long a no-op on the EDT takes from here, the driver's round trip included. */
    private fun Driver.edtRoundTrip(): Long = timed { withContext(OnDispatcher.EDT) { utility<SystemRef>().nanoTime() } }

    /**
     * The freezes the IDE reported so far: the watcher's `UI was frozen for N ms` lines in idea.log and the `threadDumps-freeze-*`
     * folders, whichever count is higher.
     */
    private fun freezes(context: IDETestContext): Freezes {
        val home = context.paths.testHome
        val lines = home.walk().filter { it.name == "idea.log" }.flatMap { it.readLines().asSequence() }.toList()
        // The watcher logs each freeze twice: its own line, and the Throwable it attaches.
        val frozen = lines.filter { "PerformanceWatcher" in it }.mapNotNull { FROZEN.find(it)?.groupValues?.get(1)?.toLong() }
        val dumps = Files.walk(home).use { s -> s.filter { it.isDirectory() && it.name.startsWith("threadDumps-freeze") }.count().toInt() }
        return Freezes(maxOf(frozen.size, dumps), frozen.maxOrNull() ?: 0, frozen)
    }

    private fun productBuild(context: IDETestContext): String =
        context.ide.build.ifBlank { "unknown" }

    private fun Driver.dismissTrialFeedback() {
        val noThanks = ui.x(TRIAL_FEEDBACK_NO)
        if (runCatching { waitFor("the trial feedback dialog", 2.seconds) { noThanks.present() } }.isSuccess) noThanks.click()
    }

    private companion object {
        val FROZEN = Regex("UI was frozen for (\\d+)\\s*ms")
        const val NEW_SESSION = "//div[@class='ComposeNode' and @role='Button' and @contentdescription='New session']"
        const val SHOW_SESSIONS = "//div[@class='ComposeNode' and @role='Button' and @contentdescription='Show sessions']"
        const val EDITABLE = "//div[@class='ComposeNode' and @editabletext]"
        const val ROW = "//div[@class='ComposeNode' and @role='Tab']"
        const val TRANSCRIPT = "//div[@class='ComposeNode' and @contentdescription='Transcript']"
        const val TRIAL_FEEDBACK_NO = "//div[@class='JButton' and (@visible_text='No, Thanks' or @accessiblename='No, Thanks')]"
    }
}

@Remote("java.lang.System")
interface SystemRef {
    fun nanoTime(): Long
}
