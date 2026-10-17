package dev.daarkolabs.tandem.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.session.DefaultProcessLauncher
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionManager
import dev.daarkolabs.tandem.session.transcript.SessionIndex
import dev.daarkolabs.tandem.session.transcript.TranscriptFolder
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<TandemSessions>()

/** Test hook: the CLI's config directory to find transcripts in, in place of `$CLAUDE_CONFIG_DIR` or `~/.claude`. */
private const val CONFIG_DIR_PROPERTY: String = "tandem.configDir"

/** Development hook: a prompt that a new session is sent once the sessions start (`./gradlew runIde -Ptandem.autoPrompt=…`). */
private const val AUTO_PROMPT_PROPERTY: String = "tandem.autoPrompt"

/**
 * The project's sessions: owns the [SessionManager], which runs every session's `claude` in this service's scope, so the platform
 * ends them all when the project closes. [ensureStarted] opens the sessions that were open last time, keeps the workspace file up to
 * date and starts the [SessionNotifier].
 *
 * Sessions run in the project's base path, the user's home for a project without one. Their transcripts are in the CLI's folder for
 * that directory as the file system resolves it, since the CLI keys the folder on its physical working directory (`/private/tmp/…`
 * for `/tmp/…` on macOS). Resolving it touches the file system, so it happens on first use, on a background thread (the index lists
 * or watches the folders), never in the constructor: [get], [manager] and [ensureStarted] are safe to call on the EDT.
 */
@Service(Service.Level.PROJECT)
class TandemSessions(private val project: Project, private val scope: CoroutineScope) {
    /** The project's base path, absolute and as the platform gives it; the user's home for a project without one. Sessions run in it. */
    private val directory: Path = baseDirectory(project)

    /** [directory] with its links resolved, native separators: the path the CLI keys its transcript folder on. Read off the EDT. */
    private val projectPath: Lazy<String> = lazy { physical(directory).toString() }

    /** Where the CLI keeps its transcript folders, one per project. Read off the EDT, like [projectPath]. */
    private val projectsDir: Lazy<Path> = lazy { TranscriptFolder.projectsDir(configDir()) }

    private val index: SessionIndex =
        SessionIndex({ TranscriptFolder.candidates(projectsDir.value, projectPath.value) }, { projectPath.value })

    /** Variables added to the environment of each `claude` this service starts, read at each start: tests point the fake CLI here. */
    @Volatile
    @set:TestOnly
    internal var extraEnvironment: Map<String, String> = emptyMap()

    /** The project's sessions. */
    val manager: SessionManager = SessionManager(
        scope = scope,
        index = index,
        launcher = DefaultProcessLauncher { System.getenv() + extraEnvironment },
        binary = ClaudeBinary::find,
        workingDirectory = directory,
        maxLive = { TandemSettings.getInstance().maxLiveSessions() },
    )

    private val started = AtomicBoolean(false)

    /**
     * Starts what the project's sessions need, once; later calls do nothing. Safe to call on the EDT: it only launches coroutines.
     * - Until the project closes, a JVM shutdown hook kills every session's process ([SessionManager.killAllNow]): when the IDE exits,
     *   the JVM can stop before the sessions' coroutines have ended their processes.
     * - The [SessionNotifier] starts.
     * - The sessions that were open when the project was last closed open again, in their order, without a process; one whose
     *   transcript is gone is left out. The one that was selected is selected again, or, if it was left out, the first one opened, as
     *   long as the user has selected none meanwhile.
     * - From then on the open sessions and the selection go to the workspace file ([TandemWorkspaceState]) whenever they change.
     * - With `-Dtandem.autoPrompt=<prompt>`, a development hook, a new session is created, selected and sent the prompt.
     */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        // Undispatched, so that the hook is added before this returns, and removed again even if the project is closing already.
        scope.launch(CoroutineName("TandemSessions.shutdownHook"), start = CoroutineStart.UNDISPATCHED) { killOnJvmExit() }
        scope.launch(Dispatchers.IO + CoroutineName("TandemSessions.start")) {
            SessionNotifier(project, manager, scope).start()
            restore()
            rememberInWorkspace()
            autoPrompt()
        }
    }

    /** Adds a JVM shutdown hook that kills every session's process, and removes it when this service's scope ends. */
    private suspend fun killOnJvmExit() {
        val hook = Thread(manager::killAllNow, "Tandem CLI shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            awaitCancellation()
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (_: IllegalStateException) {
                // The JVM is shutting down already, and runs the hook anyway.
            }
        }
    }

    /** Opens the sessions the workspace file lists, without a process, and selects the one it names; see [ensureStarted]. On IO. */
    private suspend fun restore() {
        val saved = TandemWorkspaceState.getInstance(project).state
        val selected = saved.selectedSessionId
        val restored = ArrayList<SessionId>()
        for (id in saved.openSessionIds.distinct()) {
            if (index.transcript(id) == null) {
                LOG.info("session $id not restored: it has no transcript")
                continue
            }
            manager.open(id, spawn = false)
            restored.add(id)
        }
        val selection = when (selected) {
            null -> null
            in restored -> selected
            else -> restored.firstOrNull() // the selected one was left out
        }
        if (selection != null && manager.selected.value == null) manager.select(selection)
        LOG.info("${restored.size} session(s) restored")
    }

    /** Writes the open sessions and the selection to the workspace file whenever they change. */
    private fun rememberInWorkspace() {
        val workspace = TandemWorkspaceState.getInstance(project)
        scope.launch(CoroutineName("TandemSessions.workspace")) {
            val ids = manager.sessions.map { open -> open.map { it.id } }.distinctUntilChanged()
            combine(ids, manager.selected) { open, selected -> open to selected }.collect { (open, selected) ->
                val state = workspace.state
                state.openSessionIds = open.toMutableList()
                state.selectedSessionId = selected
            }
        }
    }

    /** `-Dtandem.autoPrompt=<prompt>`: a new session, selected, that is sent the prompt. */
    private fun autoPrompt() {
        val prompt = System.getProperty(AUTO_PROMPT_PROPERTY)?.takeIf { it.isNotBlank() } ?: return
        val session = manager.create(NewSessionOptions())
        manager.select(session.id)
        manager.send(session.id, prompt)
        LOG.info("session ${session.id}: sent the autoPrompt")
    }

    companion object {
        /** The project's sessions. Safe to call on the EDT: making the service touches no file. */
        fun get(project: Project): TandemSessions = project.service()
    }
}

/** The project's base path, absolute; the user's home for a project without one. Touches no file. */
private fun baseDirectory(project: Project): Path {
    val base = project.basePath?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
    return Path.of(base).toAbsolutePath()
}

/** [path] with its links resolved, as the file system knows it; [path] itself if it cannot be resolved (it does not exist, say). */
private fun physical(path: Path): Path =
    try {
        path.toRealPath()
    } catch (_: IOException) {
        path
    }

/** The `tandem.configDir` test hook when it is set, else the CLI's config directory ([TranscriptFolder.configDir]). */
private fun configDir(): Path =
    System.getProperty(CONFIG_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: TranscriptFolder.configDir()
