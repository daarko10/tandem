package dev.daarkolabs.tandem.ide.edits

import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.ToolCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [EditQueue] alone (spec 9.5): proposals without files, told apart by their request ids. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditQueueTest {
    private fun proposal(id: Int, session: SessionId = "s1"): EditProposal {
        val call = ToolCall("edit_file", buildJsonObject { put("file_path", "/p/a$id.kt") }, "toolu_$id", JsonPrimitive(id), session)
        return EditProposal(call, null, Path.of("/p/a$id.kt"), null, "alpha", "beta", listOf(0..4), isNew = false, tool = "edit_file")
    }

    @Test
    fun `FIFO, one open at a time`() {
        val queue = EditQueue()
        assertNull(queue.open.value)
        val (a, b, c) = listOf(proposal(1), proposal(2, "s2"), proposal(3))
        val decisions = listOf(a, b, c).map(queue::enqueue)
        assertSame(a, queue.open.value)
        assertEquals(listOf(b, c), queue.queued.value)
        assertTrue(decisions.all { it.isActive })
    }

    @Test
    fun `cancel of a queued and of the open one`() {
        val queue = EditQueue()
        val (a, b, c) = listOf(proposal(1), proposal(2, "s2"), proposal(3))
        val (da, db, dc) = listOf(a, b, c).map(queue::enqueue)

        assertTrue(queue.cancel("s2", JsonPrimitive(2)))
        assertEquals(Decision.Stopped, db.getCompleted())
        assertSame(a, queue.open.value)
        assertEquals(listOf(c), queue.queued.value)

        assertTrue(queue.cancel("s1", JsonPrimitive(1)))
        assertEquals(Decision.Stopped, da.getCompleted())
        assertSame(c, queue.open.value)
        assertEquals(emptyList(), queue.queued.value)

        assertFalse(queue.cancel("s1", JsonPrimitive(1)), "a decided proposal is not cancelled again")
        assertFalse(queue.cancel("s2", JsonPrimitive(3)), "the request id of another session")
        assertTrue(dc.isActive)
        assertEquals(1, queue.cancelAll("s1"))
        assertEquals(Decision.Stopped, dc.getCompleted())
        assertNull(queue.open.value)
    }

    @Test
    fun `the next opens after a resolution`() {
        val queue = EditQueue()
        val (a, b) = listOf(proposal(1), proposal(2))
        val (da, db) = listOf(a, b).map(queue::enqueue)
        assertSame(da, queue.decisionOf(a))

        assertTrue(queue.resolve(a, Decision.Accepted("beta", all = false)))
        assertEquals(Decision.Accepted("beta", all = false), da.getCompleted())
        assertSame(b, queue.open.value)
        assertNull(queue.decisionOf(a))
        assertFalse(queue.resolve(a, Decision.Rejected(null)), "a decided proposal stays decided")

        val failure = DiffNotOpened("no window")
        assertTrue(queue.fail(b, failure))
        assertSame(failure, db.getCompletionExceptionOrNull())
        assertNull(queue.open.value)
        assertFalse(queue.fail(b, failure))
    }
}
