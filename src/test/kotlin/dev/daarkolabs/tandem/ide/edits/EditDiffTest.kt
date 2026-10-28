package dev.daarkolabs.tandem.ide.edits

import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.fileEditorManagerFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import dev.daarkolabs.tandem.session.ToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.awt.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JComponent
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** [EditDiff] on a headless project with the IDE's own `FileEditorManager` (spec 9.5): the diff as the user gets it, in a tab. */
@TestApplication
class EditDiffTest {
    companion object {
        private val tempDir = tempPathFixture()
        private val projectFx = projectFixture(tempDir, openAfterCreation = true)

        /** The IDE's own `FileEditorManager`, which the diff tab files manager opens the diff with (the test framework's is not it). */
        private val editorManagerFx = projectFx.fileEditorManagerFixture()
        private val folders = AtomicInteger()
        private const val HELLO = "alpha\nline two\n"
    }

    private val project get() = projectFx.get()

    private val dir: Path by lazy {
        Files.createDirectories(Path.of(project.basePath!!).toRealPath().resolve("d${folders.incrementAndGet()}"))
    }

    private val diff: EditDiff by lazy {
        editorManagerFx.get()
        EditDiff(project)
    }

    private fun call(path: Path, tool: String = "edit_file"): ToolCall =
        ToolCall(tool, buildJsonObject { put("file_path", path.toString()) }, "toolu_1", JsonPrimitive(1), "s1")

    /** An `edit_file` proposal of `alpha` → `beta` in a `hello.txt` written to disk. */
    private suspend fun editProposal(): Pair<EditProposal, Document> {
        val path = dir.resolve("hello.txt")
        Files.writeString(path, HELLO)
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        val document = readAction { requireNotNull(FileDocumentManager.getInstance().getDocument(file)) }
        val original = readAction { document.text }
        val proposal = EditProposal(call(path), file, path, document, original, "beta\nline two\n", listOf(0..4), false, "edit_file")
        return proposal to document
    }

    private fun newFileProposal(): EditProposal {
        val path = dir.resolve("sub/new.txt")
        return EditProposal(call(path, "write_file"), null, path, null, "", "fresh\n", emptyList(), isNew = true, tool = "write_file")
    }

    /** Shows [proposal] and hands the shown diff and the pending decision to [block]. */
    private fun withDiff(
        proposal: suspend () -> EditProposal,
        block: suspend CoroutineScope.(EditDiff.Shown, Deferred<Decision>) -> Unit,
    ) = timeoutRunBlocking(30.seconds) {
        val decision = async { diff.show(proposal()) }
        var shown = diff.shown
        while (shown == null) {
            delay(10)
            shown = diff.shown
        }
        block(shown, decision)
    }

    private fun <T : Component> find(root: JComponent, type: Class<T>, name: String): T =
        UIUtil.uiTraverser(root).filter(type).first { it.name == name } ?: error("no $name")

    private fun button(shown: EditDiff.Shown, name: String): JButton = find(shown.bar, JButton::class.java, name)

    private suspend fun click(button: JButton) = withContext(Dispatchers.EDT) { button.doClick(0) }

    @Test
    fun `the request has FORCE_READ_ONLY_CONTENTS true then false and the titles`() = withDiff({ editProposal().first }) { shown, decision ->
        val request = shown.request
        assertContentEquals(booleanArrayOf(true, false), request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS))
        assertEquals(Side.RIGHT, request.getUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE))
        assertEquals(true, request.getUserData(DiffUserDataKeys.DO_NOT_CHANGE_WINDOW_TITLE))
        assertEquals("Tandem: hello.txt", request.title)
        assertEquals(listOf("Current", "Proposed by Claude"), request.contentTitles)
        assertEquals("beta\nline two\n", readAction { shown.right.document.text })
        click(button(shown, "tandem.edit.reject"))
        assertEquals(Decision.Rejected(null), decision.await())
    }

    @Test
    fun `a dirty document reads Current (unsaved changes)`() = withDiff({
        val (proposal, document) = editProposal()
        withContext(Dispatchers.EDT) { WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "zero\n") } }
        proposal
    }) { shown, decision ->
        assertEquals(listOf("Current (unsaved changes)", "Proposed by Claude"), shown.request.contentTitles)
        click(button(shown, "tandem.edit.reject"))
        decision.await()
    }

    @Test
    fun `a new file reads New file`() = withDiff({ newFileProposal() }) { shown, decision ->
        assertEquals(listOf("New file", "Proposed by Claude"), shown.request.contentTitles)
        assertIs<EmptyContent>(shown.request.contents[0])
        assertEquals("Tandem: new.txt", shown.request.title)
        click(button(shown, "tandem.edit.reject"))
        decision.await()
    }

    @Test
    fun `the bottom panel has three buttons and the field with their names`() = withDiff({ editProposal().first }) { shown, decision ->
        val bar = shown.request.getUserData(DiffUserDataKeysEx.BOTTOM_PANEL)
        assertEquals(shown.bar, bar)
        val buttons = UIUtil.uiTraverser(shown.bar).filter(JButton::class.java).toList()
        assertEquals(listOf("Accept", "Accept all", "Reject"), buttons.map { it.text })
        assertEquals(listOf("tandem.edit.accept", "tandem.edit.acceptAll", "tandem.edit.reject"), buttons.map { it.name })
        assertEquals(listOf("Accept", "Accept all", "Reject"), buttons.map { it.accessibleContext.accessibleName })
        assertEquals("Apply the right side to hello.txt and tell Claude (Ctrl+S)".takeUnless { com.intellij.openapi.util.SystemInfo.isMac }
            ?: "Apply the right side to hello.txt and tell Claude (⌘S)", buttons[0].toolTipText)
        assertEquals("Accept this and every other edit Claude makes in this turn without a diff", buttons[1].toolTipText)
        assertEquals("Change nothing and tell Claude why (Esc)", buttons[2].toolTipText)
        val reason = find(shown.bar, JBTextField::class.java, "tandem.edit.reason")
        assertEquals("Reason for rejecting", reason.accessibleContext.accessibleName)
        assertEquals("Reason (optional): goes back to Claude with a rejection", reason.emptyText.text)
        val text = UIUtil.uiTraverser(shown.bar).filter(javax.swing.JLabel::class.java).map { it.text }.toList()
        assertTrue("Claude proposes an edit to hello.txt. Change the right side as you like, then accept or reject." in text, "$text")
        click(button(shown, "tandem.edit.reject"))
        decision.await()
    }

    @Test
    fun `typing into the right document and Accept resolves Accepted with the changed text`() = withDiff({ editProposal().first }) { shown, decision ->
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) { shown.right.document.setText("gamma\nline two\n") }
        }
        click(button(shown, "tandem.edit.accept"))
        assertEquals(Decision.Accepted("gamma\nline two\n", all = false), decision.await())
        assertFalse(withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).isFileOpen(shown.file) }, "the diff closed")
        assertEquals(null, diff.shown)
    }

    @Test
    fun `Accept all resolves Accepted for all`() = withDiff({ editProposal().first }) { shown, decision ->
        click(button(shown, "tandem.edit.acceptAll"))
        assertEquals(Decision.Accepted("beta\nline two\n", all = true), decision.await())
    }

    @Test
    fun `Reject with a reason resolves Rejected(reason)`() = withDiff({ editProposal().first }) { shown, decision ->
        withContext(Dispatchers.EDT) { find(shown.bar, JBTextField::class.java, "tandem.edit.reason").text = "  keep alpha " }
        click(button(shown, "tandem.edit.reject"))
        assertEquals(Decision.Rejected("keep alpha"), decision.await())
    }

    @Test
    fun `closing the tab rejects`() = withDiff({ editProposal().first }) { shown, decision ->
        withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).closeFile(shown.file) }
        assertEquals(Decision.Rejected(null), decision.await())
    }

    @Test
    fun `switching the viewer does not reject`() = withDiff({ editProposal().first }) { shown, decision ->
        withContext(Dispatchers.EDT) {
            // What DiffRequestProcessor does on a re-apply (side-by-side ↔ unified, a refresh).
            shown.request.onAssigned(false)
            shown.request.onAssigned(true)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
        assertTrue(decision.isActive, "the decision stays open")
        assertTrue(withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).isFileOpen(shown.file) })
        click(button(shown, "tandem.edit.accept"))
        assertIs<Decision.Accepted>(decision.await())
    }

    @Test
    fun `the Accept action carries the SaveAll shortcut set and is registered on the file editor's component`() =
        withDiff({ editProposal().first }) { shown, decision ->
            val saveAll = ActionManager.getInstance().getAction("SaveAll").shortcutSet.shortcuts.toList()
            assertTrue(saveAll.isNotEmpty(), "the keymap gives SaveAll a shortcut")
            val accept = shown.accept.shortcutSet.shortcuts.toList()
            assertTrue(accept.containsAll(saveAll), "$accept")
            assertTrue(shown.accept in ActionUtil.getActions(shown.editors[0].component))
            assertTrue(shown.accept in ActionUtil.getActions(shown.bar))
            assertTrue(shown.reject in ActionUtil.getActions(shown.editors[0].component))
            // The shortcut's action decides as the button does.
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project) { shown.right.document.setText("delta\n") }
                shown.accept.actionPerformed(TestActionEvent.createTestEvent(shown.accept))
            }
            assertEquals(Decision.Accepted("delta\n", all = false), decision.await())
        }
}
