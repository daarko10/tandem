package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.Ndjson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import kotlin.io.path.createTempFile
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [SessionHandle] on its own, in a scope whose coroutines run only when the test lets them. */
class SessionHandleTest {
    @TempDir
    lateinit var tmp: Path

    /** Coroutines of the handle's scope wait here until [runQueued]; a process's threads may queue them too. */
    private val queued = ConcurrentLinkedQueue<Runnable>()
    private val scope = CoroutineScope(SupervisorJob() + Executor { queued.add(it) }.asCoroutineDispatcher())

    private fun runQueued() {
        while (true) (queued.poll() ?: return).run()
    }

    @Test
    fun `the title follows the first prompt in the same step as the chat`() {
        val id = "33333333-3333-4333-8333-333333333333"
        val handle = SessionHandle(id, scope, ChatState(), MutableStateFlow(emptyList()), createdAt = 0)
        runQueued()
        assertEquals(id, handle.title.value, "no transcript and no prompt yet")
        try {
            handle.pick(live = {}, stopped = SessionEvent.UserSent("u1", "Create the fixture file\nand check it"))
            // Nothing in the handle's scope has run since the chat changed. A status worked out from this chat comes later, so the
            // title must already be this chat's: a notification about that status names the session by it.
            assertEquals("Create the fixture file", handle.title.value)
            runQueued()
            assertEquals("Create the fixture file", handle.title.value)
        } finally {
            scope.cancel()
            runQueued()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `whoever sees the new title sees the chat it comes from`() {
        val id = "33333333-3333-4333-8333-333333333333"
        val handle = SessionHandle(id, scope, ChatState(), MutableStateFlow(emptyList()), createdAt = 0)
        runQueued()
        // Unconfined: the collector runs as the title is set, as a reader on another thread can, and reads the chat right then.
        val seen = ArrayList<Pair<String, String?>>()
        val following = GlobalScope.launch(Dispatchers.Unconfined) { handle.title.collect { seen += it to handle.state.value.firstPrompt() } }
        try {
            handle.pick(live = {}, stopped = SessionEvent.UserSent("u1", "Write a haiku"))
            // The session list pairs the title with the chat's first prompt: it must never see the one without the other.
            assertEquals(listOf(id to null, "Write a haiku" to "Write a haiku"), seen)
        } finally {
            following.cancel()
            scope.cancel()
            runQueued()
        }
    }

    @Test
    fun `a rename between the process's first init and the handle seeing it is the only title the CLI is told`() = runBlocking {
        val id = "33333333-3333-4333-8333-333333333333"
        val stdin = createTempFile(tmp, "stdin", ".ndjson")
        val init = """{"type":"system","subtype":"init","session_id":"$id","model":"claude-test"}"""
        // A CLI that reports its init at once, then records every frame the host writes.
        val launcher = ProcessLauncher { _, dir ->
            ProcessBuilder("sh", "-c", "printf '%s\\n' '$init'; cat > '$stdin'").directory(dir.toFile()).start()
        }
        val processes = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handle = SessionHandle(id, scope, ChatState(), MutableStateFlow(emptyList()), createdAt = 0, forkOf = "original", pendingTitle = "Pong (fork)")
        runQueued()
        try {
            var started: ClaudeSession? = null
            handle.attach(
                create = { chat -> ClaudeSession(processes, SessionConfig("claude", tmp, sessionId = id), launcher, chat).also { started = it } },
                onFirstInit = { session, _ -> handle.sendPendingTitle(session) },
            )
            val session = started!!
            withTimeout(10_000) { session.reportedSessionId.filterNotNull().first() }
            // The handle's coroutines have not run, so it has not seen the init yet: the fork's title is still pending.
            assertTrue(handle.rename("Mine"), "the process reported its init: told at once")
            runQueued() // the handle sees the init now
            session.interrupt() // a last frame, after anything the handle sent
            val frames = withTimeout(10_000) {
                while (stdin.readLines().none { "interrupt" in it }) delay(20)
                stdin.readLines().map { Json.parseToJsonElement(it).jsonObject }
            }
            val titles = frames.mapNotNull { it["request"]?.jsonObject }.filter { it["subtype"]?.jsonPrimitive?.content == "rename_session" }
                .map { it["title"]!!.jsonPrimitive.content }
            assertEquals(listOf("Mine"), titles)
        } finally {
            processes.coroutineContext.job.cancelAndJoin()
            scope.cancel()
            runQueued()
        }
    }

    /** A handle in a scope that runs its coroutines, for tests that close it or attach a process. */
    private fun liveHandle(scope: CoroutineScope): SessionHandle =
        SessionHandle("33333333-3333-4333-8333-333333333333", scope, ChatState(), MutableStateFlow(emptyList()), createdAt = 0)

    /** Attaches a process to [handle] that reads its input and says nothing, started from the chat [create] is handed. */
    private fun attachQuiet(
        handle: SessionHandle,
        processes: CoroutineScope,
        start: (ChatState) -> ChatState = { it.settled() },
    ): ClaudeSession {
        var started: ClaudeSession? = null
        handle.attach(
            create = { chat ->
                val process = ScriptedProcess.quiet()
                val config = SessionConfig("claude", tmp, sessionId = handle.id)
                ClaudeSession(processes, config, ProcessLauncher { _, _ -> process }, start(chat)).also { started = it }
            },
            onFirstInit = { _, _ -> },
        )
        return started!!
    }

    @Test
    fun `close cancels the shell job`() = runBlocking<Unit> {
        val handle = liveHandle(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val job = Job()
        assertTrue(handle.startShell { job })
        assertFalse(handle.startShell { Job() }, "one command at a time")
        handle.close()
        assertTrue(handle.shellJob!!.isCancelled)
        assertFalse(handle.startShell { Job() }, "a closed session runs nothing")
    }

    @Test
    fun `a session is not idle while a command runs or a quiet send waits`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processes = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val handle = liveHandle(scope)
            val session = attachQuiet(handle, processes)
            assertTrue(handle.isIdle())
            val job = Job()
            assertTrue(handle.startShell { job })
            assertFalse(handle.isIdle(), "a command runs")
            job.cancel()
            assertTrue(handle.isIdle())
            session.event(SessionEvent.ShellSent("shell-1", listOf("a"), null, 0))
            assertFalse(handle.isIdle(), "a quiet send waits for its result")
            val result = """{"type":"result","subtype":"success","num_turns":0,"user_message_uuids":["a"]}"""
            session.event(SessionEvent.Cli(Ndjson.decode(result)))
            assertTrue(handle.isIdle())
        } finally {
            processes.coroutineContext.job.cancelAndJoin()
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `a session whose process ended during a quiet send is idle on its next process`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processes = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val handle = liveHandle(scope)
            val first = attachQuiet(handle, processes)
            first.event(SessionEvent.ShellSent("shell-1", listOf("a"), null, 0))
            assertFalse(handle.isIdle())
            handle.stop() // the process goes before the quiet send's result
            assertTrue(handle.state.value.quietSends.isEmpty())
            attachQuiet(handle, processes) { it.settled() }
            assertTrue(handle.isIdle())
        } finally {
            processes.coroutineContext.job.cancelAndJoin()
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
