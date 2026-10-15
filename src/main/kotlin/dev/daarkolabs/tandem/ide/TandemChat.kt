package dev.daarkolabs.tandem.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.ShutDownTracker
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ClaudeSession
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.SessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * The project's chat. Starts one [ClaudeSession] on first use; the session lives in this service's scope,
 * which the platform cancels when the project closes, so the CLI process never outlives the project.
 */
@Service(Service.Level.PROJECT)
class TandemChat(private val project: Project, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    @Volatile
    private var session: ClaudeSession? = null

    /** Safe to call from the EDT: binary lookup and process start happen on a background thread. */
    fun start() {
        if (session != null) return
        scope.launch(Dispatchers.IO) {
            synchronized(this@TandemChat) {
                if (session != null) return@launch
                val binary = ClaudeBinary.find()
                if (binary == null) {
                    _state.value = ChatState(
                        entries = listOf(ChatEntry.Notice("no-cli", NoticeLevel.ERROR, NO_CLI_TEXT)),
                        ended = true,
                    )
                    return@launch
                }
                val dir = project.guessProjectDir()?.toNioPath() ?: Path.of(System.getProperty("user.home"))
                val model = System.getProperty("tandem.model")?.takeIf { it.isNotBlank() }
                val created = ClaudeSession(scope, SessionConfig(binary, dir, model = model))
                // On IDE exit the JVM can stop before the session's coroutines clean up: kill the CLI synchronously then.
                val shutdownTask = Runnable(created::killNow)
                ShutDownTracker.getInstance().registerShutdownTask(shutdownTask)
                scope.coroutineContext.job.invokeOnCompletion { ShutDownTracker.getInstance().unregisterShutdownTask(shutdownTask) }
                session = created.also { it.start() }
            }
            thisLogger().info("chat session started")
            val current = session ?: return@launch
            System.getProperty("tandem.autoPrompt")?.takeIf { it.isNotBlank() }?.let(current::sendUserMessage)
            current.state.collect { _state.value = it }
        }
    }

    fun send(text: String) {
        session?.sendUserMessage(text)
    }

    fun interrupt() {
        session?.interrupt()
    }

    fun allow(requestId: String, always: Boolean) {
        val s = session ?: return
        s.state.value.pendingPermissions.firstOrNull { it.requestId == requestId }?.let { s.allow(it, always) }
    }

    fun deny(requestId: String) {
        val s = session ?: return
        s.state.value.pendingPermissions.firstOrNull { it.requestId == requestId }?.let { s.deny(it) }
    }

    companion object {
        fun get(project: Project): TandemChat = project.service()

        private const val NO_CLI_TEXT =
            "Claude Code was not found. Install it from https://code.claude.com, sign in once with `claude` in a terminal, " +
                "then reopen this tool window."
    }
}
