package dev.daarkolabs.tandem.ide

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.UI
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JLabel

/**
 * Settings | Tools | Tandem: the `claude` to run, and how many sessions may run a process at once ([TandemSettings]).
 *
 * The page never blocks the EDT: it shows the executable [ClaudeBinary.find] finds once that has run on a background thread
 * ("Detecting…" until then), and "Test" runs `<path> --version` in the background as well. That work ends when the page closes.
 */
class TandemSettingsConfigurable : BoundConfigurable("Tandem") {
    /** The open page's background work, cancelled when the page closes ([disposeUIResources]). */
    private var pageScope: CoroutineScope? = null

    /** The last "Test" run; a new one replaces it, so that an older answer never shows over a newer one. */
    private var test: Job? = null

    override fun createPanel(): DialogPanel {
        val settings = TandemSettings.getInstance()
        val scope = newPageScope()
        lateinit var detected: JLabel
        lateinit var path: TextFieldWithBrowseButton
        lateinit var outcome: JLabel
        val page = panel {
            row("Detected:") {
                detected = label("Detecting…").component
            }
            row("Path to claude:") {
                path = textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select Claude Executable"))
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .bindText({ settings.state.claudePath.orEmpty() }, { settings.state.claudePath = it.trim().ifEmpty { null } })
                    .component
                button("Test") { runTest(scope, path.text, outcome) }
            }.rowComment("Leave empty to use the detected one.")
            row("") {
                outcome = label("").component
            }
            row("Max live sessions:") {
                intTextField(TandemSettings.MAX_LIVE_SESSIONS)
                    .bindIntText({ settings.state.maxLiveSessions }, { settings.state.maxLiveSessions = it })
            }.rowComment("Beyond this, opening a session stops the process of the least recently used idle one.")
        }
        scope.launch {
            val found = withContext(Dispatchers.IO) { ClaudeBinary.find() }
            showOnEdt { detected.text = found ?: "Not found" }
        }
        return page
    }

    override fun disposeUIResources() {
        pageScope?.cancel()
        pageScope = null
        test = null
        super.disposeUIResources()
    }

    /** A scope for the page being created, a child of the application's ([TandemSettingsScope]); one an earlier page left is cancelled. */
    private fun newPageScope(): CoroutineScope {
        pageScope?.cancel()
        val parent = service<TandemSettingsScope>().scope.coroutineContext
        return CoroutineScope(parent + SupervisorJob(parent.job) + CoroutineName("TandemSettingsConfigurable")).also { pageScope = it }
    }

    /**
     * Runs `<[typed] path> --version`, or the detected executable's when [typed] is blank, on a background thread, and shows what came
     * of it in [outcome]. Called on the EDT, by the "Test" button.
     */
    private fun runTest(scope: CoroutineScope, typed: String, outcome: JLabel) {
        outcome.text = "Testing…"
        test?.cancel()
        test = scope.launch {
            val answer = withContext(Dispatchers.IO) {
                val binary = typed.trim().ifEmpty { null } ?: ClaudeBinary.find()
                if (binary == null) "No claude executable found" else ClaudeBinary.version(binary)
            }
            showOnEdt { outcome.text = answer }
        }
    }
}

/**
 * Runs [update] on the EDT. Under any modality: the page is inside the modal Settings dialog, and [update] only sets a label's text,
 * which is safe whatever dialog is open.
 */
private suspend fun showOnEdt(update: () -> Unit) {
    withContext(Dispatchers.UI + ModalityState.any().asContextElement()) { update() }
}

/** The application-level scope [TandemSettingsConfigurable] runs its background work in; the platform cancels it when the IDE exits. */
@Service(Service.Level.APP)
internal class TandemSettingsScope(val scope: CoroutineScope)
