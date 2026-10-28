package dev.daarkolabs.tandem.ide.context

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The editor's selection as the composer's chip reads it (spec 3.5, 9.5), in a headless project opened on a temporary folder. */
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

    /** An editor of [kind] on [file]'s document, made on the EDT; released by the caller. */
    private suspend fun editor(project: Project, file: VirtualFile, kind: EditorKind): Editor = withContext(Dispatchers.EDT) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        EditorFactory.getInstance().createEditor(document, project, file, false, kind)
    }

    private suspend fun release(vararg editors: Editor) = withContext(Dispatchers.EDT) {
        editors.forEach(EditorFactory.getInstance()::releaseEditor)
    }

    // spec: the flow follows a selection and clears on a caret move
    @Test
    fun `the flow follows a selection and clears on a caret move`(): Unit = timeoutRunBlocking(30.seconds) {
        val project = project.get()
        val selection = EditorSelection.getInstance(project)
        // The other test's editors are released by now: their selection goes first.
        selection.current.first { it == null }
        val editor = editor(project, file("a.txt", "one\ntwo\nthree\n"), EditorKind.MAIN_EDITOR)
        try {
            // Lines 2 and 3, the second's newline included: a selection that ends at a line's start ends on the line before.
            withContext(Dispatchers.EDT) { editor.selectionModel.setSelection(4, 14) }
            assertEquals(Selection("a.txt", "a.txt", 2, 3, "two\nthree\n"), selection.current.first { it != null })
            withContext(Dispatchers.EDT) { editor.selectionModel.setSelection(0, 2) }
            assertEquals(Selection("a.txt", "a.txt", 1, 1, "on"), selection.current.first { it?.endLine == 1 })
            // A caret move takes the selection away, and the chip with it.
            withContext(Dispatchers.EDT) {
                editor.caretModel.moveToOffset(9)
                editor.selectionModel.removeSelection()
            }
            selection.current.first { it == null }
            // Selected again, then the editor closes: the selection goes with it.
            withContext(Dispatchers.EDT) { editor.selectionModel.setSelection(8, 13) }
            assertEquals("three", selection.current.first { it != null }!!.text)
        } finally {
            release(editor)
        }
        selection.current.first { it == null }
    }

    // spec: a selection in a diff editor is ignored
    @Test
    fun `a selection in a diff editor is ignored`(): Unit = timeoutRunBlocking(30.seconds) {
        val project = project.get()
        val selection = EditorSelection.getInstance(project)
        selection.current.first { it == null }
        val diff = editor(project, file("b.txt", "diff side\n"), EditorKind.DIFF)
        val console = withContext(Dispatchers.EDT) {
            EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument("console text"), project, EditorKind.MAIN_EDITOR)
        }
        val main = editor(project, file("c.txt", "main\n"), EditorKind.MAIN_EDITOR)
        try {
            withContext(Dispatchers.EDT) {
                diff.selectionModel.setSelection(0, 4)
                console.selectionModel.setSelection(0, 7)
                main.selectionModel.setSelection(0, 4)
            }
            // One change at a time, in order: the first selection the flow shows is the main editor's, and it stays.
            assertEquals(Selection("c.txt", "c.txt", 1, 1, "main"), selection.current.first { it != null })
            // Nor does a caret move in the diff editor clear it.
            withContext(Dispatchers.EDT) {
                diff.selectionModel.removeSelection()
                console.selectionModel.removeSelection()
            }
            withContext(Dispatchers.EDT) { main.selectionModel.setSelection(0, 3) }
            assertEquals("mai", selection.current.first { it?.text != "main" }?.text)
        } finally {
            release(diff, console, main)
        }
    }
}
