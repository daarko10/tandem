package dev.daarkolabs.tandem.ide.context

import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.daarkolabs.tandem.session.ContextBlocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.annotations.VisibleForTesting

/**
 * The editor's selection as the composer's chip shows it (spec 3.5): the file's [path] (relative to the project's base directory,
 * `/`-separated, or absolute outside it), its [name], the 1-based lines [startLine] to [endLine] and the selected [text], at most
 * `ContextBlocks.MAX_BODY_BYTES + 1` characters of it: more than the block carries, so the block still says it was cut, and the
 * line count the cut reports comes from [startLine] and [endLine].
 */
data class Selection(val path: String, val name: String, val startLine: Int, val endLine: Int, val text: String) {
    /** The chip's label: `<name>:<start>-<end>`, with an ASCII hyphen. */
    val label: String get() = "$name:$startLine-$endLine"
}

/**
 * The project's current selection (spec 3.5): the selection of the editor the IDE reports as `FileEditorManager.selectedTextEditor`,
 * while it has at least one character selected. It is read when the service starts (a selection made before the tool window opened
 * shows), again whenever another editor tab is selected (a tab without a selection clears it, a tab with one shows it), and on every
 * change of that editor's selection; it clears when that selection goes (a caret move) or the editor closes. Only the selected
 * tab's editor of a file counts ([counts]): a diff editor (`DiffUtil.isDiffEditor`), a console, a preview, an editor without a `VirtualFile` and any
 * editor but the selected one are ignored, so a selection there neither sets nor clears it.
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

    /** The editor the IDE reports as selected, read on the EDT; tests read the same. */
    private fun selectedEditor(): Editor? = FileEditorManager.getInstance(project).selectedTextEditor

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
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = seed()
            },
        )
        scope.launch(Dispatchers.Default) {
            for (change in changes) mutableCurrent.value = if (change is Change) read(change) else null
        }
        // A selection made before the service started (before the tool window first opened) shows too.
        scope.launch(Dispatchers.EDT) { seed() }
    }

    /** On the EDT: the selected editor's selection as it is now, or none when it has none or does not count. */
    private fun seed() {
        val editor = selectedEditor()
        if (editor == null || !counts(editor) || !editor.selectionModel.hasSelection()) {
            clear()
            return
        }
        val model = editor.selectionModel
        onSelection(editor, model.selectionStart, model.selectionEnd)
    }

    /**
     * Whether a selection in [editor] is the project's selection: the editor is this project's editor of a file, not a diff editor,
     * a console or a preview, and it is the one the IDE reports as selected. On the EDT.
     */
    @VisibleForTesting
    internal fun counts(editor: Editor): Boolean =
        editor.project === project && editor.editorKind !in IGNORED_KINDS && !DiffUtil.isDiffEditor(editor) &&
            fileOf(editor) != null && editor === selectedEditor()

    private fun fileOf(editor: Editor): VirtualFile? = editor.virtualFile ?: FileDocumentManager.getInstance().getFile(editor.document)

    /** On the EDT: a selection of [start] to [end] in [editor], noted when the editor counts (see the class). */
    private fun onSelection(editor: Editor, start: Int, end: Int) {
        if (!counts(editor)) return
        val file = fileOf(editor) ?: return
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
        // At most one character past the block's bound: the block is cut there anyway, and a select-all in a large file copies
        // no more than that on each change.
        val text = document.getText(TextRange(start, minOf(end, start + ContextBlocks.MAX_BODY_BYTES + 1)))
        Selection(pathOf(change.file), change.file.name, startLine, endLine, text)
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
        /** The kinds of editor whose selection is never the chip's (spec 3.5): the diff's sides, consoles and previews. */
        private val IGNORED_KINDS: Set<EditorKind> = setOf(EditorKind.DIFF, EditorKind.CONSOLE, EditorKind.PREVIEW)

        fun getInstance(project: Project): EditorSelection = project.service()
    }
}
