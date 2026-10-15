package dev.daarkolabs.tandem.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
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

    /** Prompts sent before the session exists (it starts on a background thread). Guarded by `this`. */
    private val queued = ArrayList<String>()

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
                val hook = Thread(created::killNow, "Tandem CLI shutdown")
                Runtime.getRuntime().addShutdownHook(hook)
                scope.coroutineContext.job.invokeOnCompletion {
                    // Throws IllegalStateException once JVM shutdown has begun; the hook then runs anyway.
                    runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
                }
                session = created.also { it.start() }
                queued.forEach(created::sendUserMessage)
                queued.clear()
            }
            thisLogger().info("chat session started")
            val current = session ?: return@launch
            System.getProperty("tandem.autoPrompt")?.takeIf { it.isNotBlank() }?.let(::send)
            current.state.collect { _state.value = it }
        }
    }

    /** Never drops a prompt: before the session is up, it is queued and sent once the CLI starts. */
    fun send(text: String) {
        synchronized(this) {
            if (session == null) {
                queued += text
                return
            }
        }
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
