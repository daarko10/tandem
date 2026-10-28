package dev.daarkolabs.tandem.ide.edits

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.ToolReply
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.EditOutcome
import dev.daarkolabs.tandem.session.EditPhase
import dev.daarkolabs.tandem.session.EditReview
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.ToolCall
import dev.daarkolabs.tandem.session.reduce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [TandemEdits] with a presenter that answers in the diff's place and sessions whose chats follow the events through the reducer
 * (spec 9.5). Files are real, under the project's base path, and every applied edit is saved by [IdeEditTools].
 */
@TestApplication
class TandemEditsTest {
    companion object {
        private val tempDir = tempPathFixture()
        private val projectFx = projectFixture(tempDir, openAfterCreation = true)
        private val folders = AtomicInteger()
        private const val HELLO = "alpha\nline two\n"
    }

    private val project get() = projectFx.get()

    private val dir: Path by lazy {
        Files.createDirectories(Path.of(project.basePath!!).toRealPath().resolve("e${folders.incrementAndGet()}"))
    }

    /** A diff on screen: the proposal and the decision the test gives. */
    private class Showing(val proposal: EditProposal, val answer: CompletableDeferred<Decision>, val closed: CompletableDeferred<Unit>)

    private val shows = Channel<Showing>(Channel.UNLIMITED)

    /** The proposals whose show throws this, as a diff that cannot be opened does. */
    private val failing = ConcurrentHashMap<String, Throwable>()

    private val presenter = object : DiffPresenter {
        override suspend fun show(proposal: EditProposal): Decision {
            failing[proposal.call.toolUseId]?.let { throw it }
            val showing = Showing(proposal, CompletableDeferred(), CompletableDeferred())
            shows.send(showing)
            try {
                return showing.answer.await()
            } finally {
                showing.closed.complete(Unit)
            }
        }

        override fun focus() = Unit

        override fun close() = Unit
    }

    private val chats = ConcurrentHashMap<SessionId, ChatState>()
    private val events = CopyOnWriteArrayList<Pair<SessionId, SessionEvent.EditEvent>>()
    private val notices = CopyOnWriteArrayList<Pair<SessionId, String>>()

    private val sessions = object : EditSessions {
        override fun state(id: SessionId): ChatState? = chats[id]

        override fun roots(id: SessionId): List<Path> = emptyList()

        override fun event(id: SessionId, event: SessionEvent.EditEvent) {
            events += id to event
            chats.computeIfPresent(id) { _, state -> reduce(state, event) }
        }

        override fun notice(id: SessionId, text: String) {
            notices += id to text
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val edits: TandemEdits by lazy {
        TandemEdits(project, scope).also {
            it.sessions = sessions
            it.presenter = presenter
        }
    }

    private val requests = AtomicInteger()

    @AfterEach
    fun tearDown() {
        edits.dispose()
        scope.cancel()
    }

    private fun hello(name: String = "hello.txt"): Path = dir.resolve(name).also { Files.writeString(it, HELLO) }

    private fun editCall(path: Path, toolUseId: String, session: SessionId = "s1"): ToolCall = ToolCall(
        "edit_file",
        buildJsonObject {
            put("file_path", path.toString())
            put("old_string", "alpha")
            put("new_string", "beta")
        },
        toolUseId,
        JsonPrimitive(requests.incrementAndGet()),
        session,
    )

    private fun CoroutineScope.start(call: ToolCall): Deferred<ToolReply> = async(Dispatchers.Default) { edits.call(call) }

    private suspend fun nextShow(): Showing = shows.receive()

    private fun phases(toolUseId: String): List<EditReview> =
        events.mapNotNull { (_, event) -> (event as? SessionEvent.EditPhase)?.takeIf { it.toolUseId == toolUseId }?.review }

    /** Waits, bounded by the test's timeout, until [condition] holds. */
    private suspend fun until(condition: () -> Boolean) {
        while (!condition()) delay(10)
    }

    @Test
    fun `the outcomes reach the session as EditPhase events`() = timeoutRunBlocking(30.seconds) {
        chats["s1"] = ChatState()
        chats["s2"] = ChatState()
        val first = hello("one.txt")
        val second = hello("two.txt")

        val a = start(editCall(first, "t1", "s1"))
        val shownA = nextShow()
        until { phases("t1").isNotEmpty() }
        val b = start(editCall(second, "t2", "s2"))
        until { phases("t2").isNotEmpty() }
        assertEquals(listOf(EditReview("one.txt", EditPhase.OPEN)), phases("t1").distinct())
        assertEquals(listOf(EditReview("two.txt", EditPhase.QUEUED)), phases("t2"))

        shownA.answer.complete(Decision.Accepted("beta, by hand\nline two\n", all = false))
        assertFalse(a.await().isError)
        assertEquals(EditReview("one.txt", EditPhase.DONE, EditOutcome.ACCEPTED_CHANGED), phases("t1").last())
        assertEquals("beta, by hand\nline two\n", Files.readString(first))

        val shownB = nextShow()
        assertEquals(second, shownB.proposal.path)
        until { phases("t2").size >= 2 }
        assertEquals(EditReview("two.txt", EditPhase.OPEN), phases("t2")[1])
        shownB.answer.complete(Decision.Rejected("not now"))
        val rejected = b.await()
        assertTrue(rejected.isError)
        assertEquals("The user rejected this edit in the IDE; nothing was changed.\nThe user says: not now", rejected.text)
        assertEquals(EditReview("two.txt", EditPhase.DONE, EditOutcome.REJECTED), phases("t2").last())
        assertEquals(HELLO, Files.readString(second))
        assertTrue(events.none { (session, event) -> session == "s2" && event is SessionEvent.EditPhase && event.toolUseId == "t1" })

        val c = start(editCall(hello("three.txt"), "t3"))
        nextShow().answer.complete(Decision.Accepted("beta\nline two\n", all = false))
        assertFalse(c.await().isError)
        assertEquals(EditReview("three.txt", EditPhase.DONE, EditOutcome.ACCEPTED), phases("t3").last())
    }

    @Test
    fun `Accept all applies the next call of the same turn without a diff and ends at the result, and the next turn's call opens a diff again`() =
        timeoutRunBlocking(30.seconds) {
            chats["s1"] = ChatState()
            val first = start(editCall(hello("a.txt"), "t1"))
            nextShow().answer.complete(Decision.Accepted("beta\nline two\n", all = true))
            assertFalse(first.await().isError)
            assertTrue(events.any { it.second == SessionEvent.AcceptAll(true) })
            assertEquals(true, edits.acceptAll["s1"])
            assertTrue(chats.getValue("s1").acceptAll)

            val secondPath = hello("b.txt")
            val second = start(editCall(secondPath, "t2")).await()
            assertFalse(second.isError, second.text)
            assertTrue(second.text.startsWith("Applied the edit to $secondPath and saved the file (accepted with Accept all)."), second.text)
            assertEquals("beta\nline two\n", Files.readString(secondPath))
            assertEquals(listOf(EditReview("b.txt", EditPhase.DONE, EditOutcome.AUTO)), phases("t2"))
            assertTrue(shows.isEmpty, "no diff for the second call")

            // Another session's call still opens a diff.
            chats["s2"] = ChatState()
            val other = start(editCall(hello("c.txt"), "t3", "s2"))
            nextShow().answer.complete(Decision.Rejected(null))
            assertTrue(other.await().isError)

            // The turn's result resets the chat's flag (the reducer's rule), and the next turn's call opens a diff again.
            val result = SessionEvent.Cli(Ndjson.decode(Fixtures.lines("ide-edit")[71 - 1]))
            chats.computeIfPresent("s1") { _, state -> reduce(state, result) }
            assertFalse(chats.getValue("s1").acceptAll, "the result line of ide-edit:71 ends it")
            val next = start(editCall(hello("d.txt"), "t4"))
            val shown = nextShow()
            assertEquals("t4", shown.proposal.call.toolUseId)
            assertEquals(null, edits.acceptAll["s1"])
            shown.answer.complete(Decision.Rejected(null))
            assertTrue(next.await().isError)
        }

    @Test
    fun `a stopped session's calls are dropped`() = timeoutRunBlocking(30.seconds) {
        chats["s1"] = ChatState()
        chats["s2"] = ChatState()
        val first = hello("one.txt")
        val a = start(editCall(first, "t1", "s1"))
        val shownA = nextShow()
        val b = start(editCall(hello("two.txt"), "t2", "s2"))
        val c = start(editCall(hello("three.txt"), "t3", "s1"))
        until { phases("t2").isNotEmpty() && phases("t3").isNotEmpty() }
        assertEquals(EditPhase.QUEUED, phases("t3").last().phase)

        edits.cancelAll("s1")
        assertFailsWith<CancellationException> { a.await() }
        assertFailsWith<CancellationException> { c.await() }
        shownA.closed.await()
        assertEquals(EditReview("one.txt", EditPhase.DONE, EditOutcome.STOPPED), phases("t1").last())
        assertEquals(EditReview("three.txt", EditPhase.DONE, EditOutcome.STOPPED), phases("t3").last())
        assertEquals(HELLO, Files.readString(first))

        // The other session's call opens next, and one withdrawn by the CLI (Stop) is dropped the same way.
        val shownB = nextShow()
        assertEquals("t2", shownB.proposal.call.toolUseId)
        edits.cancel("s2", shownB.proposal.call.requestId)
        assertFailsWith<CancellationException> { b.await() }
        shownB.closed.await()
        assertEquals(EditReview("two.txt", EditPhase.DONE, EditOutcome.STOPPED), phases("t2").last())
        assertEquals(null, edits.queue.open.value)
    }

    @Test
    fun `acceptEdits applies without a diff and reports auto`() = timeoutRunBlocking(30.seconds) {
        chats["s1"] = ChatState(permissionMode = PermissionMode.ACCEPT_EDITS)
        val path = hello()
        val reply = start(editCall(path, "t1")).await()
        assertFalse(reply.isError, reply.text)
        assertTrue(reply.text.startsWith("Applied the edit to $path and saved the file (auto-accepted by the user's mode)."), reply.text)
        assertEquals("beta\nline two\n", Files.readString(path))
        assertEquals(listOf(EditReview("hello.txt", EditPhase.DONE, EditOutcome.AUTO)), phases("t1"))
        assertTrue(shows.isEmpty)

        chats["s2"] = ChatState(permissionMode = PermissionMode.PLAN)
        val refused = start(editCall(hello("plan.txt"), "t2", "s2")).await()
        assertEquals("The session is in plan mode: Tandem applies no edits until the plan is approved.", refused.text)
        assertTrue(phases("t2").isEmpty())
    }

    @Test
    fun `a diff that cannot be opened refuses the call, says so and lets the next one open`() = timeoutRunBlocking(30.seconds) {
        chats["s1"] = ChatState()
        failing["t1"] = IllegalStateException("the IDE opened no editor for it")
        val first = hello("one.txt")
        val second = hello("two.txt")
        val a = start(editCall(first, "t1"))
        val refused = a.await()
        assertTrue(refused.isError, refused.text)
        assertEquals(EditReview("one.txt", EditPhase.DONE, null), phases("t1").last())
        assertEquals(listOf("s1" to "The diff could not be opened: the IDE opened no editor for it"), notices)
        assertEquals(HELLO, Files.readString(first))

        // A throwable that is not an Exception does not stop the diffs either.
        failing["t2"] = AssertionError("platform assertion")
        val b = start(editCall(second, "t2")).await()
        assertTrue(b.isError, b.text)
        assertEquals(EditReview("two.txt", EditPhase.DONE, null), phases("t2").last())

        val c = start(editCall(hello("three.txt"), "t3"))
        nextShow().answer.complete(Decision.Accepted("beta\nline two\n", all = false))
        assertFalse(c.await().isError)
        assertEquals(EditReview("three.txt", EditPhase.DONE, EditOutcome.ACCEPTED), phases("t3").last())
    }
}
