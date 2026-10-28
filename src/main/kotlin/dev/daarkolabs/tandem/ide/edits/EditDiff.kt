package dev.daarkolabs.tandem.ide.edits

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ClientProperty
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

private val LOG = logger<EditDiff>()

/**
 * The editable diff of one proposal (spec 3.1, 3.2): the file's own `Document` read-only on the left (empty for a new file), the
 * proposal editable on the right, and the bar under it with `Accept`, `Accept all`, `Reject` and the reason field. [show] opens it
 * through the IDE's diff tab files manager (the IDE's preference picks a tab or a window) and returns the user's [Decision]:
 * `Accepted` with the right side's text as it is when pressed, or `Rejected` with the typed reason; closing the diff without a
 * choice (the tab's or window's close, the IDE's close all) is `Rejected(null)`, learnt from the file editor's disposal and from
 * `fileClosed`, never from `DiffRequest.onAssigned`, which a viewer switch calls too. One diff at a time ([TandemEdits]'s queue).
 */
class EditDiff(private val project: Project) : DiffPresenter {
    /** A diff on screen: what tests look at, and what [close] and [focus] act on. */
    internal class Shown(
        val proposal: EditProposal,
        val request: SimpleDiffRequest,
        val file: ChainDiffVirtualFile,
        val editors: Array<out FileEditor>,
        val bar: JComponent,
        val accept: AnAction,
        val reject: AnAction,
        val right: DocumentContent,
        val result: CompletableDeferred<Decision>,
        val disposable: Disposable,
    )

    /** The diff on screen, null when none is. Set and cleared on the EDT. */
    @Volatile
    internal var shown: Shown? = null
        private set

    /**
     * Opens the diff of [proposal] on the EDT and waits for the user's decision; the diff closes when it returns, also when the
     * caller is cancelled (Stop). Throws when the diff cannot be opened.
     */
    override suspend fun show(proposal: EditProposal): Decision {
        val result = CompletableDeferred<Decision>()
        val opened = withContext(Dispatchers.EDT) { open(proposal, result) }
        try {
            return result.await()
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) { dismiss(opened) }
        }
    }

    /** Brings the open diff to the front (the row's `Show diff`). Safe on any thread. */
    override fun focus() {
        val current = shown ?: return
        ApplicationManager.getApplication().invokeLater(
            { if (shown === current && !project.isDisposed) showDiffFile(current.file) },
            ModalityState.defaultModalityState(),
        )
    }

    /** Closes the open diff, if any; one that had no decision resolves `Rejected(null)`. Safe on any thread. */
    override fun close() {
        val current = shown ?: return
        ApplicationManager.getApplication().invokeLater({ dismiss(current) }, ModalityState.any())
    }

    private fun open(proposal: EditProposal, result: CompletableDeferred<Decision>): Shown {
        val name = proposal.fileName
        val factory = DiffContentFactory.getInstance()
        val document = proposal.document
        val left = when {
            proposal.isNew || document == null -> factory.createEmpty()
            proposal.file != null -> factory.create(project, document, proposal.file)
            else -> factory.create(project, document)
        }
        val fileType = proposal.file?.fileType ?: FileTypeManager.getInstance().getFileTypeByFileName(name)
        val right = factory.createEditable(project, proposal.proposed, fileType)
        val leftTitle = when {
            proposal.isNew || document == null -> NEW_FILE
            FileDocumentManager.getInstance().isDocumentUnsaved(document) -> CURRENT_UNSAVED
            else -> CURRENT
        }
        val title = "Tandem: $name"
        val request = SimpleDiffRequest(title, left, right, leftTitle, PROPOSED)
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, false))
        request.putUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE, Side.RIGHT)
        request.putUserData(DiffUserDataKeys.DO_NOT_CHANGE_WINDOW_TITLE, true)

        val reason = JBTextField().apply {
            this.name = REASON_NAME
            emptyText.text = REASON_PLACEHOLDER
            accessibleContext.accessibleName = REASON_ACCESSIBLE_NAME
        }
        // The right side's text when the button is pressed: read on the EDT, where the buttons and shortcuts run.
        val acceptOnce = { all: Boolean -> result.complete(Decision.Accepted(right.document.text, all)) }
        val rejectOnce = { result.complete(Decision.Rejected(reason.text.trim().ifEmpty { null })) }
        val acceptButton = button(ACCEPT, ACCEPT_NAME, acceptTooltip(name)) { acceptOnce(false) }
        ClientProperty.put(acceptButton, DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        val acceptAllButton = button(ACCEPT_ALL, ACCEPT_ALL_NAME, ACCEPT_ALL_TOOLTIP) { acceptOnce(true) }
        val rejectButton = button(REJECT, REJECT_NAME, REJECT_TOOLTIP) { rejectOnce() }
        val bar = bar(name, reason, acceptButton, acceptAllButton, rejectButton)
        request.putUserData(DiffUserDataKeysEx.BOTTOM_PANEL, bar)

        val accept = action { acceptOnce(false) }
        val reject = action { rejectOnce() }
        // On the bar before the diff opens, so the shortcuts work before focus moves to the right side; this also gives each action
        // its shortcut set, which DiffUtil.registerAction then puts on the file editor's component.
        accept.registerCustomShortcutSet(acceptShortcuts(), bar)
        reject.registerCustomShortcutSet(CommonShortcuts.ESCAPE, bar)

        val file = ChainDiffVirtualFile(SimpleDiffRequestChain(request), title)
        val disposable = Disposer.newDisposable("Tandem edit diff")
        project.messageBus.connect(disposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, closed: VirtualFile) {
                    if (closed == file) result.complete(Decision.Rejected(null))
                }
            },
        )
        val editors = try {
            showDiffFile(file).also { check(it.isNotEmpty()) { "the IDE opened no editor for it" } }
        } catch (e: Throwable) {
            Disposer.dispose(disposable)
            throw e
        }
        for (editor in editors) {
            DiffUtil.registerAction(accept, editor.component)
            DiffUtil.registerAction(reject, editor.component)
        }
        // The file editor goes away with its tab or window, in either placement: no choice made by then is a rejection.
        Disposer.register(editors[0]) { result.complete(Decision.Rejected(null)) }
        LOG.debug("diff opened for request ${proposal.call.requestId}")
        return Shown(proposal, request, file, editors, bar, accept, reject, right, result, disposable).also { shown = it }
    }

    /** Opens (or brings to the front) the diff's [file] where the IDE's preference says: an editor tab or a window. On the EDT. */
    private fun showDiffFile(file: VirtualFile): Array<out FileEditor> = DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)

    /** On the EDT: closes [current]'s diff and lets go of what it held. */
    private fun dismiss(current: Shown) {
        if (shown === current) shown = null
        if (!project.isDisposed) FileEditorManager.getInstance(project).closeFile(current.file)
        Disposer.dispose(current.disposable)
    }

    private fun bar(name: String, reason: JBTextField, vararg buttons: JButton): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(6, 8)
        val first = JPanel(BorderLayout()).apply { add(JBLabel(barText(name)), BorderLayout.WEST) }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { buttons.forEach(::add) }
        val second = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.emptyTop(4)
            add(reason, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }
        panel.add(first)
        panel.add(second)
        return panel
    }

    private fun button(text: String, swingName: String, tooltip: String, onClick: () -> Unit): JButton =
        JButton(text).apply {
            name = swingName
            toolTipText = tooltip
            accessibleContext.accessibleName = text
            addActionListener { onClick() }
        }

    private fun action(run: () -> Unit): AnAction =
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    internal companion object {
        const val CURRENT: String = "Current"
        const val CURRENT_UNSAVED: String = "Current (unsaved changes)"
        const val NEW_FILE: String = "New file"
        const val PROPOSED: String = "Proposed by Claude"
        const val ACCEPT: String = "Accept"
        const val ACCEPT_ALL: String = "Accept all"
        const val REJECT: String = "Reject"
        const val ACCEPT_NAME: String = "tandem.edit.accept"
        const val ACCEPT_ALL_NAME: String = "tandem.edit.acceptAll"
        const val REJECT_NAME: String = "tandem.edit.reject"
        const val REASON_NAME: String = "tandem.edit.reason"
        const val REASON_PLACEHOLDER: String = "Reason (optional): goes back to Claude with a rejection"
        const val REASON_ACCESSIBLE_NAME: String = "Reason for rejecting"
        const val ACCEPT_ALL_TOOLTIP: String = "Accept this and every other edit Claude makes in this turn without a diff"
        const val REJECT_TOOLTIP: String = "Change nothing and tell Claude why (Esc)"

        fun barText(name: String): String =
            "Claude proposes an edit to $name. Change the right side as you like, then accept or reject."

        fun acceptTooltip(name: String): String =
            "Apply the right side to $name and tell Claude (${if (SystemInfo.isMac) "⌘S" else "Ctrl+S"})"

        /** The IDE's `SaveAll` shortcuts (Ctrl+S, ⌘S by default) and Ctrl+Enter (⌘↵): Save inside the diff is Accept. */
        fun acceptShortcuts(): ShortcutSet {
            val saveAll = ActionManager.getInstance().getAction("SaveAll")?.shortcutSet?.shortcuts.orEmpty()
            return CustomShortcutSet(*saveAll, *CommonShortcuts.getCtrlEnter().shortcuts)
        }
    }
}
