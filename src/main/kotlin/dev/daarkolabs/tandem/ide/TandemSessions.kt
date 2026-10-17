package dev.daarkolabs.tandem.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.session.DefaultProcessLauncher
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.ProcessLauncher
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionManager
import dev.daarkolabs.tandem.session.transcript.LoadedTranscript
import dev.daarkolabs.tandem.session.transcript.SessionIndex
import dev.daarkolabs.tandem.session.transcript.TranscriptFiles
import dev.daarkolabs.tandem.session.transcript.TranscriptFolder
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
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

    /** Reads a session's history when it is opened: tests make it fail for one session. */
    @Volatile
    @set:TestOnly
    internal var readTranscript: suspend (Path) -> LoadedTranscript = TranscriptReader::read

    /** Appends a rename's `custom-title` line to a transcript: tests make it fail. */
    @Volatile
    @set:TestOnly
    internal var appendTitle: (transcript: Path, id: SessionId, title: String, time: Instant) -> Unit = TranscriptFiles::appendCustomTitle

    /** Deletes a session's transcript and what goes with it: tests make it fail. */
    @Volatile
    @set:TestOnly
    internal var deleteTranscript: (transcript: Path) -> Unit = TranscriptFiles::delete

    /** The project's sessions. */
    val manager: SessionManager = SessionManager(
        scope = scope,
        index = index,
        // The login shell's environment, not the IDE's: launched from the Dock or Finder, the IDE has launchd's minimal PATH. With
        // claude's directory on the PATH, for a claude whose node is beside it. The launcher runs on a background thread, where
        // waiting for the platform to load the environment is fine.
        launcher = ProcessLauncher { argv, workingDirectory ->
            val environment = ClaudeBinary.environmentFor(argv.first(), ShellEnvironment.get()) + extraEnvironment
            DefaultProcessLauncher { environment }.launch(argv, workingDirectory)
        },
        binary = ClaudeBinary::find,
        workingDirectory = directory,
        maxLive = { TandemSettings.getInstance().maxLiveSessions() },
        clock = System::currentTimeMillis,
        readTranscript = { readTranscript(it) },
        appendTitle = { transcript, id, title, time -> appendTitle(transcript, id, title, time) },
        deleteFiles = { deleteTranscript(it) },
    )

    private val started = AtomicBoolean(false)

    /** The JVM shutdown hook [ensureStarted] added, until it is removed. */
    @Volatile
    private var shutdownHook: Thread? = null

    /** Whether the JVM shutdown hook for this project's sessions is registered. */
    @get:TestOnly
    internal val shutdownHookRegistered: Boolean
        get() = shutdownHook != null

    /**
     * Starts what the project's sessions need, once; later calls do nothing. Safe to call on the EDT: it adds a shutdown hook and
     * launches coroutines, and waits for nothing.
     * - Until every session's process has ended, a JVM shutdown hook kills them ([SessionManager.killAllNow]): when the IDE exits, the
     *   JVM can stop before the sessions' coroutines have ended their processes.
     * - The [SessionNotifier] starts.
     * - The sessions that were open when the project was last closed open again, without a process: the one that was selected
     *   first, and selected again at once, then the others in their order. One whose transcript is gone, or that fails to open, is
     *   left out; if that is the selected one, the first one opened is selected in its place. Nothing is selected if the user has
     *   selected a session meanwhile.
     * - From then on the open sessions and the selection go to the workspace file ([TandemWorkspaceState]) whenever they change, and
 *   so do the models a session's CLI lists.
     * - With `-Dtandem.autoPrompt=<prompt>`, a development hook, a new session is created, selected and sent the prompt.
     */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        killOnJvmExit()
        scope.launch(Dispatchers.IO + CoroutineName("TandemSessions.start")) {
            SessionNotifier(project, manager, scope).start()
            restore()
            rememberInWorkspace()
            autoPrompt()
        }
    }

    /**
     * Adds a JVM shutdown hook that kills every session's process, and removes it once this service's scope has completed: not when
     * it is cancelled, since the sessions end their processes after that, which takes up to a few seconds
     * ([dev.daarkolabs.tandem.session.ClaudeSession.close]), and closing the project does not wait for them. The scope completes only
     * once the last of them has ended.
     */
    private fun killOnJvmExit() {
        val hook = Thread(manager::killAllNow, "Tandem CLI shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        shutdownHook = hook
        scope.coroutineContext.job.invokeOnCompletion {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) } // it throws once the JVM shuts down, and the hook runs then
            shutdownHook = null
        }
    }

    /**
     * Opens the sessions the workspace file lists, without a process, and selects the one it names, which is opened first, so that
     * it shows before the others' transcripts are read; see [ensureStarted]. On IO.
     */
    private suspend fun restore() {
        val saved = TandemWorkspaceState.getInstance(project).state
        val ids = saved.openSessionIds.distinct()
        val wanted = saved.selectedSessionId
        val order = if (wanted != null && wanted in ids) listOf(wanted) + (ids - wanted) else ids
        var restored = 0
        for (id in order) {
            if (!reopen(id)) continue
            restored++
            // The saved selection, or, if it was left out, the first one opened in its place.
            if (restored == 1 && wanted != null && manager.selected.value == null) manager.select(id)
        }
        LOG.info("$restored session(s) restored")
    }

    /** Opens session [id] without a process; false if it has no transcript any more, or fails to open. */
    private suspend fun reopen(id: SessionId): Boolean =
        try {
            if (index.transcript(id) == null) {
                LOG.info("session $id not restored: it has no transcript")
                false
            } else {
                manager.open(id, spawn = false)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class only: a message can quote the transcript.
            LOG.warn("session $id not restored (${e.javaClass.name})")
            false
        }

    /**
     * Writes the open sessions and the selection to the workspace file whenever they change, and the models whenever a session's CLI
     * lists them: every session runs the same `claude`, so the last list stands for all of them.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
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
        scope.launch(CoroutineName("TandemSessions.models")) {
            manager.sessions.flatMapLatest { open -> open.map { handle -> handle.state.map { it.models } }.merge() }
                .filter { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect(workspace::rememberModels)
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

/**
 * The `tandem.configDir` test hook when it is set, else the CLI's config directory ([TranscriptFolder.configDir]) as the CLI sees it:
 * from the login shell's environment, which sessions start with, since a `CLAUDE_CONFIG_DIR` may be exported only in the shell
 * profile. May block while the platform loads that environment: call off the EDT.
 */
private fun configDir(): Path =
    System.getProperty(CONFIG_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: TranscriptFolder.configDir(ShellEnvironment.get())
