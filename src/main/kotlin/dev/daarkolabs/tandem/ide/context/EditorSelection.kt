package dev.daarkolabs.tandem.ide.context

import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The editor's selection as the composer's chip shows it (spec 3.5): the file's [path] (relative to the project's base directory,
 * `/`-separated, or absolute outside it), its [name], the 1-based lines [startLine] to [endLine] and the selected [text].
 */
data class Selection(val path: String, val name: String, val startLine: Int, val endLine: Int, val text: String) {
    /** The chip's label: `<name>:<start>-<end>`, with an ASCII hyphen. */
    val label: String get() = "$name:$startLine-$endLine"
}

/**
 * The project's current selection (spec 3.5): set while a text editor of the project has a selection of at least one character, and
 * cleared when that editor's selection goes (a caret move) or the editor closes. Only the IDE's main editors count: a diff editor
 * (`DiffUtil.isDiffEditor`), a console, a preview and an editor without a `VirtualFile` are ignored, so a selection there neither
 * sets nor clears it.
 *
 * The listener runs on the EDT and only notes the editor's document, file and range; the lines and the text are read in a
 * `readAction` on [Dispatchers.Default], one change at a time and the latest only, so a drag that fires many events reads once.
 * Nothing is logged: a selection's text and its path stay out of the log.
 */
@Service(Service.Level.PROJECT)
class EditorSelection(private val project: Project, scope: CoroutineScope) : Disposable {
    /** A change to read: a selection's document, file and range, or [Cleared]. Conflated: only the latest is read. */
    private val changes = Channel<Any>(Channel.CONFLATED)

    private val mutableCurrent = MutableStateFlow<Selection?>(null)

    /** The selection the composer offers as a chip; null while no main editor of the project has one. */
    val current: StateFlow<Selection?> = mutableCurrent.asStateFlow()

    /** The editor whose selection [current] holds or will hold; read and written on the EDT only. */
    private var owner: Editor? = null

    private class Change(val document: Document, val file: VirtualFile, val start: Int, val end: Int)

    private object Cleared

    init {
        val factory = EditorFactory.getInstance()
        factory.eventMulticaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) = onSelection(e.editor, e.newRange.startOffset, e.newRange.endOffset)
            },
            this,
        )
        factory.addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) {
                    if (event.editor === owner) clear()
                }
            },
            this,
        )
        scope.launch(Dispatchers.Default) {
            for (change in changes) mutableCurrent.value = if (change is Change) read(change) else null
        }
    }

    /** On the EDT: a selection of [start] to [end] in [editor], noted when the editor counts (see the class). */
    private fun onSelection(editor: Editor, start: Int, end: Int) {
        if (editor.project !== project || editor.editorKind != EditorKind.MAIN_EDITOR || DiffUtil.isDiffEditor(editor)) return
        val file = editor.virtualFile ?: FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (end <= start) {
            if (editor === owner) clear()
            return
        }
        owner = editor
        changes.trySend(Change(editor.document, file, start, end))
    }

    private fun clear() {
        owner = null
        changes.trySend(Cleared)
    }

    /** The selection [change] names, read from its document as it is now; null when the document no longer holds any of it. */
    private suspend fun read(change: Change): Selection? = readAction {
        val document = change.document
        val end = change.end.coerceAtMost(document.textLength)
        val start = change.start.coerceAtMost(end)
        if (start == end) return@readAction null
        // A selection that ends at a line's start (whole lines picked) ends on the line before.
        val startLine = document.getLineNumber(start) + 1
        val endLine = document.getLineNumber(end - 1) + 1
        Selection(pathOf(change.file), change.file.name, startLine, endLine, document.getText(TextRange(start, end)))
    }

    /** [file]'s path relative to the project's base directory, or its absolute path outside it. */
    private fun pathOf(file: VirtualFile): String {
        val base = project.guessProjectDir() ?: return file.path
        return VfsUtilCore.getRelativePath(file, base, '/') ?: file.path
    }

    override fun dispose() {
        changes.close()
    }

    companion object {
        fun getInstance(project: Project): EditorSelection = project.service()
    }
}
