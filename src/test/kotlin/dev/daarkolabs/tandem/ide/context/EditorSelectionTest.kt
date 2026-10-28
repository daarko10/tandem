package dev.daarkolabs.tandem.ide.context

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.daarkolabs.tandem.session.ContextBlocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The editor's selection as the composer's chip reads it (spec 3.5, 9.5), in a headless project opened on a temporary folder: the
 * selection of the editor the IDE reports as selected (`FileEditorManager.selectedTextEditor`), files opened through the
 * `FileEditorManager` as the user opens them.
 */
@TestApplication
class EditorSelectionTest {
    companion object {
        private val dir = tempPathFixture()
        private val project = projectFixture(dir, openAfterCreation = true)
    }

    /** Writes [text] to [name] under the project's folder and gives its `VirtualFile`. */
    private fun file(name: String, text: String): VirtualFile {
        val path = dir.get().resolve(name)
        path.writeText(text)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    /** Opens [file] in an editor tab and selects it, as a click on the tab does; gives that tab's text editor. */
    private suspend fun open(project: Project, file: VirtualFile): Editor = withContext(Dispatchers.EDT) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(file, true)
        assertNotNull(manager.selectedTextEditor, "the opened file's editor is the selected one").also {
            assertEquals(file, it.virtualFile)
        }
    }

    /** An editor of [kind] on [file]'s document, made on the EDT outside the tabs; released by the caller. */
    private suspend fun editor(project: Project, file: VirtualFile, kind: EditorKind): Editor = withContext(Dispatchers.EDT) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        EditorFactory.getInstance().createEditor(document, project, file, false, kind)
    }

    private suspend fun release(vararg editors: Editor) = withContext(Dispatchers.EDT) {
        editors.forEach(EditorFactory.getInstance()::releaseEditor)
    }

    @AfterEach
    fun closeTabs(): Unit = timeoutRunBlocking(30.seconds) {
        withContext(Dispatchers.EDT) {
            val manager = FileEditorManager.getInstance(project.get())
            manager.openFiles.forEach(manager::closeFile)
            // The history keeps a pointer to each file opened; the test framework counts one left over as a leak.
            EditorHistoryManager.getInstance(project.get()).removeAllFiles()
        }
    }

    // spec: the flow follows a selection and clears on a caret move
    @Test
    fun `the flow follows a selection and clears on a caret move`(): Unit = timeoutRunBlocking(30.seconds) {
        val project = project.get()
        val selection = EditorSelection.getInstance(project)
        // The other tests' tabs are closed by now: their selection goes first.
        selection.current.first { it == null }
        val file = file("a.txt", "one\ntwo\nthree\n")
        val editor = open(project, file)
        // Lines 2 and 3, the second's newline included: a selection that ends at a line's start ends on the line before.
        withContext(Dispatchers.EDT) { editor.selectionModel.setSelection(4, 14) }
        assertEquals(Selection("a.txt", "a.txt", 2, 3, "two\nthree\n"), selection.current.first { it != null })
        withContext(Dispatchers.EDT) { editor.selectionModel.setSelection(0, 2) }
        assertEquals(Selection("a.txt", "a.txt", 1, 1, "on"), selection.current.first { it?.endLine == 1 })
        // A caret move (the Right arrow's own handler, as the user's key press runs it) takes the selection away, and the chip with it.
        withContext(Dispatchers.EDT) {
            EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT)
                .execute(editor, editor.caretModel.currentCaret, EditorUtil.getEditorDataContext(editor))
            assertFalse(editor.selectionModel.hasSelection(), "the caret move removed the selection")
        }
        selection.current.first { it == null }
        // Selected again, then the tab closes: the selection goes with it.
        withContext(Dispatchers.EDT) { editor.selectionModel.setSelection(8, 13) }
        assertEquals("three", selection.current.first { it != null }!!.text)
        withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).closeFile(file) }
        selection.current.first { it == null }
    }

    // spec: a selection in a diff editor is ignored
    @Test
    fun `a selection in a diff editor is ignored`(): Unit = timeoutRunBlocking(30.seconds) {
        val project = project.get()
        val selection = EditorSelection.getInstance(project)
        selection.current.first { it == null }
        val main = open(project, file("c.txt", "main\n"))
        val diff = editor(project, file("b.txt", "diff side\n"), EditorKind.DIFF)
        val console = withContext(Dispatchers.EDT) {
            EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument("console text"), project, EditorKind.MAIN_EDITOR)
        }
        try {
            withContext(Dispatchers.EDT) { main.selectionModel.setSelection(0, 4) }
            assertEquals(Selection("c.txt", "c.txt", 1, 1, "main"), selection.current.first { it != null })
            // Each in its own EDT step, so that a counted change would be read before the next one replaces it.
            withContext(Dispatchers.EDT) { diff.selectionModel.setSelection(0, 4) }
            withContext(Dispatchers.EDT) { console.selectionModel.setSelection(0, 7) }
            withContext(Dispatchers.EDT) {
                assertFalse(selection.counts(diff), "a diff editor does not count")
                assertFalse(selection.counts(console), "a console (no file) does not count")
                assertTrue(selection.counts(main), "the selected tab's editor counts")
            }
            // The main editor still owns the selection: its removal clears the chip. Had the diff or the console taken it over, the
            // removal would be ignored and the flow would keep their text, and this bounded wait would fail.
            withContext(Dispatchers.EDT) { main.selectionModel.removeSelection() }
            selection.current.first { it == null }
            // Nor does the diff editor's removal clear a main selection.
            withContext(Dispatchers.EDT) { main.selectionModel.setSelection(0, 3) }
            assertEquals("mai", selection.current.first { it != null }?.text)
            withContext(Dispatchers.EDT) { diff.selectionModel.removeSelection() }
            withContext(Dispatchers.EDT) { console.selectionModel.removeSelection() }
            withContext(Dispatchers.EDT) { main.selectionModel.setSelection(1, 4) }
            assertEquals("ain", selection.current.first { it?.text != "mai" }?.text)
        } finally {
            release(diff, console)
        }
    }

    // review: a selection made before the tool window first opens shows (spec 3.5, the selected text editor)
    @Test
    fun `an existing selection shows when the service starts`(): Unit = timeoutRunBlocking(30.seconds) {
        val project = project.get()
        val editor = open(project, file("d.txt", "alpha\nbeta\n"))
        withContext(Dispatchers.EDT) { editor.selectionModel.setSelection(6, 10) }
        // A service of its own, started after the selection was made.
        val scope = CoroutineScope(SupervisorJob())
        val fresh = EditorSelection(project, scope)
        try {
            assertEquals(Selection("d.txt", "d.txt", 2, 2, "beta"), fresh.current.first { it != null })
        } finally {
            Disposer.dispose(fresh)
            scope.cancel()
        }
    }

    // review: the chip follows the selected tab
    @Test
    fun `switching to a tab without a selection clears the chip`(): Unit = timeoutRunBlocking(30.seconds) {
        val project = project.get()
        val selection = EditorSelection.getInstance(project)
        selection.current.first { it == null }
        val first = file("e.txt", "first file\n")
        val editor = open(project, first)
        withContext(Dispatchers.EDT) { editor.selectionModel.setSelection(0, 5) }
        assertEquals("first", selection.current.first { it != null }?.text)
        // Another tab, with no selection: the hidden tab's selection is no longer offered.
        open(project, file("f.txt", "second file\n"))
        selection.current.first { it == null }
        // Back to the first tab, whose selection is still there: it shows again. The tests' editor manager clears a reopened
        // file's selection unless told not to; the IDE's own keeps it.
        val key = "clear.selection.in.tests"
        val before = System.getProperty(key)
        System.setProperty(key, "disabled")
        try {
            open(project, first)
        } finally {
            if (before == null) System.clearProperty(key) else System.setProperty(key, before)
        }
        assertEquals(Selection("e.txt", "e.txt", 1, 1, "first"), selection.current.first { it != null })
    }

    // review: the selection carries at most the block's bound, and the block still says it was cut
    @Test
    fun `a large selection is read up to the block's bound`(): Unit = timeoutRunBlocking(30.seconds) {
        val project = project.get()
        val selection = EditorSelection.getInstance(project)
        selection.current.first { it == null }
        val line = "x".repeat(99) + "\n"
        val editor = open(project, file("g.txt", line.repeat(2000)))
        withContext(Dispatchers.EDT) { editor.selectionModel.setSelection(0, editor.document.textLength) }
        val read = selection.current.first { it != null }!!
        assertEquals(1 to 2000, read.startLine to read.endLine)
        assertEquals(ContextBlocks.MAX_BODY_BYTES + 1, read.text.length)
        val block = ContextBlocks.selectionBlock(read.path, read.startLine, read.endLine, read.text)
        assertTrue(block.body.endsWith("\n… truncated: 2000 lines in all"), "the cut names every line of the selection")
    }
}
