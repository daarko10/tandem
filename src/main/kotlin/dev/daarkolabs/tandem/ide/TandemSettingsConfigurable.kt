package dev.daarkolabs.tandem.ide

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.UI
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
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

/** The status line's text when the saved path cannot run. */
private const val NOT_EXECUTABLE_TEXT: String = "Configured path is not executable"

/**
 * Settings | Tools | Tandem: the `claude` to run, how many sessions may run a process at once and whether the chat shows thinking
 * rows ([TandemSettings]).
 *
 * The page never blocks the EDT. Once [ClaudeBinary.detect] has run on a background thread, it shows the executable Tandem finds by
 * itself ("Detecting…" until then) and where it found it (on the login shell's `PATH`, or in a usual install location), and says
 * in its status line when the saved path cannot run; both are checked again after Apply.
 * "Test" runs `<path> --version`, or the detected executable's when the field is empty, in the background as well, and shows the
 * answer in the status line. That work ends when the page closes.
 */
class TandemSettingsConfigurable : BoundConfigurable("Tandem") {
    /** The open page's background work, cancelled when the page closes ([disposeUIResources]). */
    private var pageScope: CoroutineScope? = null

    /** The last "Test" run; a new one replaces it, so that an older answer never shows over a newer one. */
    private var test: Job? = null

    /** The last [check]; a new one replaces it, so that an older detection never shows over a newer one. */
    private var checking: Job? = null

    /**
     * Counts the [check]s and "Test" runs asked for, on the EDT. A check's answer shows in the status line only if nothing was asked
     * after it, no Test ran when it was asked for and none runs when it answers: a Test's answer, which the user asked for, is never
     * overwritten by a check, whether the check was asked for before the Test (a slow one) or while it ran (Apply), and whenever it
     * answers. A Test's answer always shows, unless a newer Test replaced it.
     */
    private var statusRequests: Int = 0

    /** The open page's labels for the detected executable, where it was found, and for the status line. */
    private var detected: JLabel? = null
    private var detectedWhere: JLabel? = null
    private var status: JLabel? = null

    override fun createPanel(): DialogPanel {
        val settings = TandemSettings.getInstance()
        newPageScope()
        lateinit var path: TextFieldWithBrowseButton
        val page = panel {
            row("Detected:") {
                detected = label("Detecting…").component
                detectedWhere = label("").component
            }
            row("Path to claude:") {
                path = textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select Claude Executable"))
                    .align(AlignX.FILL)
                    .resizableColumn()
                    // Trimmed, as stored, so that a path pasted with spaces around it is not still "modified" once applied.
                    .bind(
                        { it.text.trim() },
                        { field, text -> field.text = text },
                        MutableProperty(
                            { settings.state.claudePath.orEmpty() },
                            { settings.state.claudePath = it }, // which stores it normalised
                        ),
                    )
                    .component
                button("Test") { runTest(path.text) }
            }.rowComment("Leave empty to use the detected one.")
            row("") {
                status = label("").component
            }
            row("Max live sessions:") {
                intTextField(TandemSettings.MAX_LIVE_SESSIONS)
                    .bindIntText({ settings.state.maxLiveSessions }, { settings.state.maxLiveSessions = it })
            }.rowComment("Beyond this, opening a session stops the process of the least recently used idle one.")
            row {
                checkBox("Show thinking rows")
                    .bindSelected({ settings.state.showThinking }, { settings.state.showThinking = it })
            }
            row {
                checkBox("Claude replies to ! command output")
                    .bindSelected({ settings.state.respondToShellOutput }, { settings.state.respondToShellOutput = it })
                    .comment("Off: the output is added to the conversation without a reply (the CLI's respondToBashCommands: false).")
            }
        }
        check()
        return page
    }

    override fun apply() {
        super.apply()
        // Open chats rebuild their rows with the new setting; a project whose sessions never started reads it when they do.
        ProjectManager.getInstance().openProjects.forEach { it.serviceIfCreated<TandemSessions>()?.settingsChanged() }
        check() // the saved path may have changed
    }

    override fun disposeUIResources() {
        pageScope?.cancel()
        pageScope = null
        test = null
        checking = null
        detected = null
        detectedWhere = null
        status = null
        super.disposeUIResources()
    }

    /** Makes [pageScope] for the page being created, a child of the application's scope ([TandemSettingsScope]); cancels the last one. */
    private fun newPageScope() {
        pageScope?.cancel()
        val parent = service<TandemSettingsScope>().scope.coroutineContext
        pageScope = CoroutineScope(parent + SupervisorJob(parent.job) + CoroutineName("TandemSettingsConfigurable"))
    }

    /**
     * Shows the executable Tandem detects by itself, and in the status line whether the saved path can run, once both are known: they
     * are worked out on a background thread. The status line keeps a "Test" answer asked for after this. Called on the EDT.
     */
    private fun check() {
        val scope = pageScope ?: return
        val detected = detected ?: return
        val detectedWhere = detectedWhere ?: return
        val status = status ?: return
        val request = ++statusRequests
        val duringTest = test?.isActive == true // its answer comes later: this check must not overwrite it
        checking?.cancel()
        checking = scope.launch {
            val (found, runs) = withContext(Dispatchers.IO) {
                ClaudeBinary.detect() to (ClaudeBinary.configured()?.let(ClaudeBinary::isExecutable) ?: true)
            }
            showOnEdt {
                detected.text = found?.path ?: "Not found"
                detectedWhere.text = found?.where?.description.orEmpty()
                if (request == statusRequests && !duringTest && test?.isActive != true) {
                    status.text = if (runs) "" else NOT_EXECUTABLE_TEXT
                }
            }
        }
    }

    /**
     * Runs `<[typed] path> --version`, or the detected executable's when [typed] is blank, on a background thread, and shows what came
     * of it in the status line. Called on the EDT, by the "Test" button.
     */
    private fun runTest(typed: String) {
        val scope = pageScope ?: return
        val status = status ?: return
        status.text = "Testing…"
        statusRequests++
        test?.cancel()
        test = scope.launch {
            val answer = withContext(Dispatchers.IO) {
                val binary = typed.trim().ifEmpty { null } ?: ClaudeBinary.detect()?.path
                if (binary == null) "No claude executable found" else ClaudeBinary.version(binary)
            }
            showOnEdt { status.text = answer }
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
