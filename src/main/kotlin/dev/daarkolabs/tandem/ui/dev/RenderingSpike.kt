package dev.daarkolabs.tandem.ui.dev

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import dev.daarkolabs.tandem.TandemProjectScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Development-only Jewel rendering spike. Enabled with `-Dtandem.spike=true`
 * (`./gradlew runIde -Ptandem.spike=true`): streams [FakeTranscript] into the tool window while
 * [EdtStallMonitor] measures EDT stalls and [RecompositionCounter] counts row recompositions. Progress goes to idea.log as
 * `TANDEM-SPIKE` lines.
 */
internal object RenderingSpike {
    val enabled: Boolean get() = System.getProperty("tandem.spike") == "true"

    val transcript: FakeTranscript by lazy { FakeTranscript() }
}

/** Development-only: opens the tool window on startup for the spike and for `-Dtandem.autoPrompt` runs. Does nothing otherwise. */
internal class DevStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val autoPrompt = !System.getProperty("tandem.autoPrompt").isNullOrBlank()
        if (!RenderingSpike.enabled && !autoPrompt) return
        withContext(Dispatchers.EDT) {
            val window = ToolWindowManager.getInstance(project).getToolWindow("Tandem") ?: return@withContext
            window.show()
            (window as? ToolWindowEx)?.stretchWidth(600)
        }
        if (RenderingSpike.enabled) runSpike(project)
    }

    private fun runSpike(project: Project) {
        val log = thisLogger()
        TandemProjectScope.get(project).scope.launch(Dispatchers.Default) {
            // Baseline first: EDT stalls of the IDE itself (startup, indexing) with the tool window idle.
            val baseline = EdtStallMonitor()
            val baselineProbe = launch { baseline.run() }
            delay(20_000)
            baselineProbe.cancelAndJoin()
            log.warn("TANDEM-SPIKE phase=baseline ${baseline.summary()}")

            val monitor = EdtStallMonitor()
            val probe = launch { monitor.run() }
            log.warn("TANDEM-SPIKE phase=start")
            RenderingSpike.transcript.play { index ->
                if (index == 99) log.warn("TANDEM-SPIKE phase=mid ${monitor.summary()}")
            }
            probe.cancelAndJoin()
            log.warn("TANDEM-SPIKE phase=end ${monitor.summary()}")
        }
    }
}
