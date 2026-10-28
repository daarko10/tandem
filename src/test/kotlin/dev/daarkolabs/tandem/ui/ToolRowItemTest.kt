@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import dev.daarkolabs.tandem.chat.RowAction
import dev.daarkolabs.tandem.chat.RowGap
import dev.daarkolabs.tandem.chat.RowLabel
import dev.daarkolabs.tandem.chat.ToolBody
import dev.daarkolabs.tandem.chat.ToolCall
import dev.daarkolabs.tandem.chat.ToolKind
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.chat.ToolState
import dev.daarkolabs.tandem.session.EditOutcome
import dev.daarkolabs.tandem.session.EditPhase
import dev.daarkolabs.tandem.session.EditReview
import dev.daarkolabs.tandem.ui.rows.CommandPart
import dev.daarkolabs.tandem.ui.rows.LocalShowDiff
import dev.daarkolabs.tandem.ui.rows.ToolRowItem
import dev.daarkolabs.tandem.ui.rows.Marker
import dev.daarkolabs.tandem.ui.rows.canExpand
import dev.daarkolabs.tandem.ui.rows.clampOwners
import dev.daarkolabs.tandem.ui.rows.commandHeader
import dev.daarkolabs.tandem.ui.rows.commandParts
import dev.daarkolabs.tandem.ui.rows.editRowTag
import dev.daarkolabs.tandem.ui.rows.headerMarker
import dev.daarkolabs.tandem.ui.rows.marker
import dev.daarkolabs.tandem.ui.rows.showDiffClick
import dev.daarkolabs.tandem.ui.rows.showDiffTag
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@TestApplication
class ToolRowItemTest {
    @Test
    fun `a running group can expand, a lone running call only with a body`() {
        assertTrue(canExpand(group(ToolState.RUNNING, call("a", ToolState.DONE, MONO), call("b", ToolState.RUNNING, ToolBody.None))))
        assertTrue(canExpand(group(ToolState.DONE, call("a", ToolState.DONE, MONO), call("b", ToolState.DONE, ToolBody.None))))
        assertTrue(canExpand(lone(call("a", ToolState.RUNNING, COMMAND))))
        assertFalse(canExpand(lone(call("a", ToolState.RUNNING, ToolBody.None))))
        assertFalse(canExpand(lone(call("a", ToolState.DONE, ToolBody.None))))
        assertTrue(canExpand(lone(call("a", ToolState.DENIED, MONO))))
    }

    @Test
    fun `a live sub-row is marked running or waiting`() {
        assertEquals(Marker.RUNNING, marker(call("a", ToolState.PENDING, ToolBody.None)))
        assertEquals(Marker.RUNNING, marker(call("a", ToolState.RUNNING, ToolBody.None)))
        assertEquals(Marker.WAITING, marker(call("a", ToolState.WAITING, ToolBody.None)))
        for (state in listOf(ToolState.DONE, ToolState.FAILED, ToolState.DENIED, ToolState.INTERRUPTED)) {
            assertEquals(Marker.NONE, marker(call("a", state, MONO)), state.name)
        }
    }

    @Test
    fun `a running lone row is marked, a group header is not`() {
        assertEquals(Marker.RUNNING, headerMarker(lone(call("a", ToolState.RUNNING, ToolBody.None))))
        assertEquals(
            Marker.NONE,
            headerMarker(group(ToolState.RUNNING, call("a", ToolState.DONE, MONO), call("b", ToolState.RUNNING, ToolBody.None))),
        )
    }

    @Test
    fun `a row that cannot open has no chevron, live or finished`() {
        assertFalse(canExpand(call("a", ToolState.RUNNING, ToolBody.None)))
        assertTrue(canExpand(call("a", ToolState.RUNNING, COMMAND)))
        assertFalse(canExpand(call("a", ToolState.DONE, ToolBody.None)))
    }

    @Test
    fun `a command body without output has no output part`() {
        assertEquals(
            listOf(CommandPart.HEADER, CommandPart.COMMAND),
            commandParts(ToolBody.Command("ls", persistentListOf(), 0, false), framed = true),
        )
        assertEquals(listOf(CommandPart.COMMAND), commandParts(ToolBody.Command("ls", persistentListOf(), 0, false), framed = false))
        assertEquals(
            listOf(CommandPart.HEADER, CommandPart.COMMAND, CommandPart.OUTPUT),
            commandParts(ToolBody.Command("ls", persistentListOf("a"), 1, false), framed = true),
        )
    }

    @Test
    fun `a group sub-row's Bash body has no header and no frame`() {
        assertEquals(
            listOf(CommandPart.COMMAND, CommandPart.OUTPUT),
            commandParts(ToolBody.Command("ls", persistentListOf("a"), 1, false), framed = false),
        )
        assertEquals(
            listOf(CommandPart.HEADER, CommandPart.COMMAND, CommandPart.OUTPUT),
            commandParts(ToolBody.Command("ls", persistentListOf("a"), 1, false), framed = true),
        )
    }

    @Test
    fun `a PowerShell body is headed PowerShell`() {
        assertEquals("PowerShell", commandHeader("PowerShell"))
        assertEquals("Bash", commandHeader("Bash"))
    }

    @Test
    fun `the clamp owners of a group are the row and its calls`() {
        assertEquals(listOf("c:a", "c:b"), clampOwners(group(ToolState.DONE, call("a", ToolState.DONE, MONO), call("b", ToolState.DONE, MONO))))
        val nested = lone(call("n", ToolState.DONE, MONO))
        val agent = ToolCall(
            "agent", "Agent", ToolKind.AGENT, ToolState.DONE, RowLabel("Agent", persistentListOf()), null, ToolBody.None,
            persistentListOf(nested), null,
        )
        assertEquals(listOf("c:agent", "c:n"), clampOwners(lone(agent)))
    }

    private fun call(id: String, state: ToolState, body: ToolBody): ToolCall =
        ToolCall(id, "Read", ToolKind.READ, state, RowLabel("Read $id", persistentListOf()), null, body, persistentListOf(), null)

    private fun group(state: ToolState, vararg calls: ToolCall): ToolRow =
        ToolRow("tool:${calls.first().id}", persistentListOf(*calls), RowLabel("Read 2 files", persistentListOf()), state, null, RowGap.ROW)

    private fun lone(call: ToolCall): ToolRow = ToolRow("tool:${call.id}", persistentListOf(call), call.label, call.state, null, RowGap.ROW)

    private companion object {
        val MONO: ToolBody = ToolBody.Mono(persistentListOf("The user denied this request."), totalLines = 1)
        val COMMAND: ToolBody = ToolBody.Command("ls", persistentListOf(), 0, firstLineError = false)
    }

    // spec: Show diff calls the local with the tool use id
    @Test
    fun `Show diff calls the local with the tool use id`() = timeoutRunBlocking(60.seconds) {
        // Skia's reference cleaner and Java2D's disposer start with the first rendered scene and live as long as the JVM.
        ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Reference Cleaner", "Java2D Disposer")
        val shown = ArrayList<String>()
        withContext(Dispatchers.EDT) {
            val open = edit("t1", EditReview("a.kt", EditPhase.OPEN), RowAction.ShowDiff("t1"))
            val queued = edit("t2", EditReview("b.kt", EditPhase.QUEUED), action = null)
            val done = edit("t3", EditReview("c.kt", EditPhase.DONE, EditOutcome.ACCEPTED), action = null)
            val ui = ChatUiState()
            val scene = ImageComposeScene(600, 300, Density(1f)) {
                SwingBridgeTheme {
                    CompositionLocalProvider(LocalShowDiff provides { shown += it }) {
                        Column {
                            for (call in listOf(open, queued, done)) ToolRowItem(RenderedRow(lone(call), null), ui)
                        }
                    }
                }
            }
            try {
                scene.render()
                scene.render(16_000_000L)
                val tags = nodes(scene).mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
                for (id in listOf("t1", "t2", "t3")) assertTrue(editRowTag(id) in tags, "the row of $id carries ${editRowTag(id)}: $tags")
                assertEquals(listOf(showDiffTag("t1")), tags.filter { it.startsWith("edit-show-diff-") }, "only the open review offers Show diff")
                assertEquals("edit-show-diff-t1", showDiffTag("t1"))
                assertEquals("edit-row-t1", editRowTag("t1"))

                val link = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == showDiffTag("t1") }
                val center = link.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Move, center)
                scene.sendPointerEvent(PointerEventType.Press, center)
                scene.sendPointerEvent(PointerEventType.Release, center)
                scene.render(100_000_000L)
                assertEquals(listOf("t1"), shown)
            } finally {
                scene.close()
            }
        }
        // The helper the row uses: no action, no click.
        assertNull(showDiffClick(call("t4", ToolState.RUNNING, ToolBody.None)) { shown += it })
        assertEquals(listOf("t1"), shown)
    }

    /** A Tandem edit call [id] under [review], waiting for its result, with [action]. */
    private fun edit(id: String, review: EditReview, action: RowAction?): ToolCall =
        call(id, ToolState.RUNNING, ToolBody.None).copy(name = "mcp__tandem__edit_file", kind = ToolKind.EDIT, review = review, action = action)

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner -> all(owner.rootSemanticsNode) }

    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
}
