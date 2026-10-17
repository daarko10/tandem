package dev.daarkolabs.tandem.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.DelicateCoroutinesApi
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor
import kotlin.test.assertEquals

/** [SessionHandle] on its own, in a scope whose coroutines run only when the test lets them. */
class SessionHandleTest {
    /** Coroutines of the handle's scope wait here until [runQueued]. */
    private val queued = ArrayDeque<Runnable>()
    private val scope = CoroutineScope(SupervisorJob() + Executor { queued.addLast(it) }.asCoroutineDispatcher())

    private fun runQueued() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
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
}
