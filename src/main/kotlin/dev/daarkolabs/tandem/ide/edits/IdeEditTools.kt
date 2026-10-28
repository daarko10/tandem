package dev.daarkolabs.tandem.ide.edits

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import dev.daarkolabs.tandem.protocol.TandemTools
import dev.daarkolabs.tandem.protocol.ToolReply
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.ToolCall
import dev.daarkolabs.tandem.session.edits.AcceptMode
import dev.daarkolabs.tandem.session.edits.EditMatcher
import dev.daarkolabs.tandem.session.edits.EditResults
import dev.daarkolabs.tandem.session.edits.Match
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Tandem's `edit_file` and `write_file` in the IDE (spec 4.3): checks the call, builds the [EditProposal], has it reviewed ([review],
 * the diff) unless an auto-accept mode applies, and applies the accepted text to the file's `Document` in one write command, saved to
 * disk before the reply is returned (spec 3.2). Paths must resolve, after `toRealPath()`, under the project's base path or one of the
 * session's added folders ([roots]). Every failure the user did not choose is told in the session's chat through [notify]
 * (`Could not apply the edit to <file>: <reason>`, hard rule 5). Only the write command and the save run on the EDT; the file lookup
 * and the matching run on `Dispatchers.IO`, the result text on `Dispatchers.Default`.
 */
class IdeEditTools(
    private val project: Project,
    private val roots: (SessionId) -> List<Path>,
    private val review: suspend (EditProposal) -> Decision,
    private val notify: (SessionId, String) -> Unit,
) {
    /**
     * Answers [call]: the checks, the proposal, then [autoAccept] (the mode or `Accept all` applied: no diff) or [review], then
     * [apply]. [planMode] refuses every edit (spec 7.9). A [Decision.Stopped] review throws a [CancellationException]: the CLI
     * withdrew the call and no reply is sent.
     */
    suspend fun call(call: ToolCall, autoAccept: AcceptMode?, planMode: Boolean = false): ToolReply {
        LOG.debug { "tools/call ${call.name} request ${call.requestId} tool use ${call.toolUseId}" }
        val args = parse(call) ?: return EditResults.malformed(call.name)
        if (planMode) return EditResults.planMode()
        if (args is Args.Edit && EditMatcher.normalizeNewlines(args.oldString) == EditMatcher.normalizeNewlines(args.newString)) {
            return EditResults.same()
        }
        return try {
            when (val checked = propose(call, args)) {
                is Checked.Refusal -> checked.reply
                is Checked.Ready -> decide(checked.proposal, autoAccept)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("tools/call ${call.name} request ${call.requestId} failed: ${e.javaClass.simpleName}")
            val reason = reasonOf(e)
            notify(call.session, notice(fileNameOf(args.filePath), reason))
            EditResults.refused(args.filePath, reason)
        }
    }

    /**
     * Writes [decision]'s text into [proposal]'s file (spec 3.2): `ensureFilesWritable` first, then one write command named
     * `Tandem: edit <file name>` (`Tandem: create <file name>`) that checks the file still holds what the proposal was built on
     * (the range markers' text, else a re-match with the same count; the whole text for a changed proposal and `write_file`) and
     * writes nothing when it does not, then `commitDocument` and `saveDocument`, all on the EDT; the reply is built after the save.
     */
    suspend fun apply(proposal: EditProposal, decision: Decision.Accepted, mode: AcceptMode): ToolReply {
        val given = proposal.givenPath
        val session = proposal.call.session
        val finalText = EditMatcher.normalizeNewlines(decision.finalText)
        val changed = mode == AcceptMode.USER_CHANGED
        val written = withContext(Dispatchers.EDT) { write(proposal, finalText, changed) }
        LOG.debug { "tools/call ${proposal.tool} request ${proposal.call.requestId}: ${written.javaClass.simpleName}" }
        return withContext(Dispatchers.Default) {
            when (written) {
                is Written.ReadOnly -> EditResults.readOnly(given)
                is Written.Stale -> {
                    notify(session, notice(proposal.fileName, STALE_REASON))
                    EditResults.fileChanged(given, written.current)
                }
                is Written.Refused -> {
                    notify(session, notice(proposal.fileName, written.reason))
                    EditResults.refused(given, written.reason)
                }
                is Written.Unsaved -> {
                    notify(session, notice(proposal.fileName, written.reason))
                    EditResults.unsaved(given, written.reason)
                }
                is Written.Saved -> reply(proposal, written, finalText, mode)
            }
        }
    }

    private suspend fun decide(proposal: EditProposal, autoAccept: AcceptMode?): ToolReply {
        try {
            val decision = if (autoAccept != null) Decision.Accepted(proposal.proposed, all = false) else review(proposal)
            return when (decision) {
                is Decision.Accepted -> {
                    val mode = autoAccept ?: if (EditMatcher.normalizeNewlines(decision.finalText) == proposal.proposed) {
                        AcceptMode.USER
                    } else {
                        AcceptMode.USER_CHANGED
                    }
                    apply(proposal, decision, mode)
                }
                is Decision.Rejected -> EditResults.rejected(decision.reason)
                Decision.Stopped -> throw CancellationException("the CLI withdrew the call")
            }
        } finally {
            proposal.markers.forEach { it.dispose() }
        }
    }

    private fun reply(proposal: EditProposal, written: Written.Saved, finalText: String, mode: AcceptMode): ToolReply {
        val given = proposal.givenPath
        val changed = mode == AcceptMode.USER_CHANGED
        return when {
            proposal.tool == TandemTools.WRITE_FILE && proposal.isNew -> EditResults.created(given, written.text, changed)
            proposal.tool == TandemTools.WRITE_FILE -> EditResults.replaced(given, written.text, proposal.original, changed)
            changed -> EditResults.accepted(given, written.text, changedRegion(proposal.original, written.text), proposal.proposed, finalText, mode)
            else -> EditResults.accepted(given, written.text, written.ranges, null, null, mode)
        }
    }

    /** On the EDT: the writability check, the write command with the staleness check inside it, the commit and the save. */
    private fun write(proposal: EditProposal, finalText: String, changed: Boolean): Written {
        val file = proposal.file
        if (file != null && ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(file)).hasReadonlyFiles()) {
            return Written.ReadOnly
        }
        val name = (if (proposal.isNew) "Tandem: create " else "Tandem: edit ") + proposal.fileName
        val inCommand: InCommand = try {
            WriteCommandAction.writeCommandAction(project).withName(name).compute<InCommand, Exception> {
                when {
                    proposal.isNew -> create(proposal, finalText)
                    proposal.tool == TandemTools.EDIT_FILE && !changed -> replaceRanges(proposal)
                    else -> replaceWhole(proposal, finalText)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("tools/call ${proposal.tool} request ${proposal.call.requestId}: the write command failed: ${e.javaClass.simpleName}")
            return Written.Refused(reasonOf(e))
        }
        val (document, ranges) = when (inCommand) {
            is InCommand.Stale -> return Written.Stale(inCommand.current)
            is InCommand.Done -> inCommand.document to inCommand.ranges
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val manager = FileDocumentManager.getInstance()
        val saveError = try {
            manager.saveDocument(document)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("tools/call ${proposal.tool} request ${proposal.call.requestId}: the save failed: ${e.javaClass.simpleName}")
            reasonOf(e)
        }
        if (saveError != null || manager.isDocumentUnsaved(document)) return Written.Unsaved(saveError ?: NOT_SAVED_REASON)
        return Written.Saved(document.text, ranges)
    }

    /** `edit_file` as proposed: each range from its marker while it still holds `old_string`, else a re-match with the same count. */
    private fun replaceRanges(proposal: EditProposal): InCommand {
        val document = requireNotNull(proposal.document) { "an edit_file proposal has a document" }
        val args = proposal.call.arguments
        val oldString = (args["old_string"] as JsonPrimitive).content
        val newString = (args["new_string"] as JsonPrimitive).content
        val replaceAll = (args["replace_all"] as? JsonPrimitive)?.booleanOrNull ?: false
        val old = EditMatcher.normalizeNewlines(oldString)
        val current = document.immutableCharSequence
        val markers = proposal.markers
        val fromMarkers = markers.takeIf { list ->
            list.isNotEmpty() && list.size == proposal.ranges.size && list.all { marker ->
                marker.isValid && marker.endOffset - marker.startOffset == old.length &&
                    StringUtil.equals(current.subSequence(marker.startOffset, marker.endOffset), old)
            }
        }?.map { it.startOffset until it.endOffset }
        val ranges = fromMarkers
            ?: (EditMatcher.match(current.toString(), oldString, replaceAll) as? Match.Found)?.ranges?.takeIf { it.size == proposal.ranges.size }
            ?: return InCommand.Stale(current.toString())
        val sorted = ranges.sortedBy { it.first }
        val replacement = EditMatcher.normalizeNewlines(newString)
        for (range in sorted.asReversed()) document.replaceString(range.first, range.last + 1, replacement)
        return InCommand.Done(document, EditMatcher.appliedRanges(Match.Found(sorted), newString))
    }

    /** A changed proposal or `write_file` of an existing file: the whole text, only while the document still reads [EditProposal.original]. */
    private fun replaceWhole(proposal: EditProposal, finalText: String): InCommand {
        val document = requireNotNull(proposal.document) { "an existing file's proposal has a document" }
        val current = document.immutableCharSequence
        if (!StringUtil.equals(current, proposal.original)) return InCommand.Stale(current.toString())
        document.setText(finalText)
        return InCommand.Done(document, emptyList())
    }

    /**
     * `write_file` of a new file: its directories, the file, its text. A file that appeared meanwhile is a change while the diff was
     * open, found before any folder is created.
     */
    private fun create(proposal: EditProposal, finalText: String): InCommand {
        val parentPath = proposal.path.parent ?: throw IOException("the path has no parent folder")
        val name = proposal.path.fileName.toString()
        if (Files.exists(proposal.path)) {
            val existing = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(proposal.path)
            return InCommand.Stale(existing?.let { FileDocumentManager.getInstance().getDocument(it)?.text }.orEmpty())
        }
        val parent = VfsUtil.createDirectories(parentPath.toString())
        parent.findChild(name)?.let { existing ->
            val text = FileDocumentManager.getInstance().getDocument(existing)?.text.orEmpty()
            return InCommand.Stale(text)
        }
        val created = parent.createChildData(this, name)
        val document = FileDocumentManager.getInstance().getDocument(created) ?: throw IOException("the IDE has no text document for it")
        document.setText(finalText)
        return InCommand.Done(document, emptyList())
    }

    /** The checks of spec 4.3 off the EDT, then the proposal: the document read, matched and marked in one read action. */
    private suspend fun propose(call: ToolCall, args: Args): Checked = withContext(Dispatchers.IO) {
        val given = args.filePath
        if (args is Args.Write) {
            val size = args.content.toByteArray(Charsets.UTF_8).size.toLong()
            if (size > MAX_CONTENT_BYTES) return@withContext Checked.Refusal(EditResults.tooLarge(given, StringUtil.formatFileSize(size)))
        }
        val path = resolve(given, call.session) ?: return@withContext Checked.Refusal(EditResults.outside(given))
        if (Files.isDirectory(path)) return@withContext Checked.Refusal(EditResults.directory(given))
        if (!Files.exists(path)) {
            return@withContext when (args) {
                is Args.Edit -> Checked.Refusal(EditResults.missing(given))
                is Args.Write -> newFile(call, path, args)
            }
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: return@withContext Checked.Refusal(EditResults.missing(given))
        if (file.isDirectory) return@withContext Checked.Refusal(EditResults.directory(given))
        // FileUtilRt.isTooLarge (spec 4.3) is deprecated and FileSizeLimit internal in 2026.1: the same user limit, public.
        if (file.length > FileUtilRt.getUserContentLoadLimit()) {
            return@withContext Checked.Refusal(EditResults.tooLarge(given, StringUtil.formatFileSize(file.length)))
        }
        if (isBinary(file, path)) return@withContext Checked.Refusal(EditResults.binary(given))
        readAction { existingFile(call, path, file, args) }
    }

    private fun newFile(call: ToolCall, path: Path, args: Args.Write): Checked {
        val type = FileTypeRegistry.getInstance().getFileTypeByFileName(path.fileName.toString())
        val binary = if (type == UnknownFileType.INSTANCE) '\u0000' in args.content.take(BINARY_PROBE_BYTES) else type.isBinary
        if (binary) return Checked.Refusal(EditResults.binary(args.filePath))
        val proposed = EditMatcher.normalizeNewlines(args.content)
        return Checked.Ready(EditProposal(call, null, path, null, "", proposed, emptyList(), isNew = true, tool = TandemTools.WRITE_FILE))
    }

    /** In a read action: the document, the match and its range markers, or the refusal. */
    private fun existingFile(call: ToolCall, path: Path, file: VirtualFile, args: Args): Checked {
        val document: Document = FileDocumentManager.getInstance().getDocument(file)
            ?: return Checked.Refusal(EditResults.binary(args.filePath))
        val text = document.immutableCharSequence.toString()
        return when (args) {
            is Args.Write -> Checked.Ready(
                EditProposal(call, file, path, document, text, EditMatcher.normalizeNewlines(args.content), emptyList(), false, TandemTools.WRITE_FILE),
            )
            is Args.Edit -> when (val match = EditMatcher.match(text, args.oldString, args.replaceAll)) {
                Match.NotFound -> Checked.Refusal(EditResults.notFound(args.filePath, text))
                is Match.Ambiguous -> Checked.Refusal(EditResults.ambiguous(args.filePath, match.count))
                is Match.Found -> {
                    val markers = match.ranges.map { document.createRangeMarker(it.first, it.last + 1) }
                    val proposed = EditMatcher.apply(text, match, args.newString)
                    Checked.Ready(EditProposal(call, file, path, document, text, proposed, match.ranges, false, TandemTools.EDIT_FILE, markers))
                }
            }
        }
    }

    /** [given] as a real path under one of the roots (the project's base path, the session's added folders), or null. */
    private fun resolve(given: String, session: SessionId): Path? {
        val raw = try {
            Path.of(given)
        } catch (_: InvalidPathException) {
            return null
        }
        if (!raw.isAbsolute) return null
        val real = realPath(raw.normalize()) ?: return null
        val rootPaths = (listOfNotNull(project.basePath?.let { Path.of(it) }) + roots(session))
            .mapNotNull { runCatching { it.toRealPath() }.getOrNull() }
        return real.takeIf { candidate -> rootPaths.any { candidate.startsWith(it) } }
    }

    /** [path] with its links resolved: its deepest existing ancestor's real path and the names below it. */
    private fun realPath(path: Path): Path? {
        var existing: Path = path
        val missing = ArrayDeque<String>()
        while (!Files.exists(existing)) {
            missing.addFirst(existing.fileName?.toString() ?: return null)
            existing = existing.parent ?: return null
        }
        return try {
            missing.fold(existing.toRealPath()) { real, name -> real.resolve(name) }
        } catch (_: IOException) {
            null
        }
    }

    /** The file type says binary, or the type is unknown and the first 8 KB hold a NUL (spec 7.7). */
    private fun isBinary(file: VirtualFile, path: Path): Boolean {
        val type = file.fileType
        if (!FileTypeRegistry.getInstance().isFileOfType(file, UnknownFileType.INSTANCE)) return type.isBinary
        val head = ByteArray(BINARY_PROBE_BYTES)
        val read = Files.newInputStream(path).use { it.readNBytes(head, 0, head.size) }
        return (0 until read).any { head[it] == 0.toByte() }
    }

    private fun parse(call: ToolCall): Args? {
        val arguments = call.arguments
        fun string(key: String): String? = (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val filePath = string("file_path") ?: return null
        return when (call.name) {
            TandemTools.EDIT_FILE -> {
                val oldString = string("old_string") ?: return null
                val newString = string("new_string") ?: return null
                val replaceAll = when (val value = arguments["replace_all"]) {
                    null, JsonNull -> false
                    is JsonPrimitive -> value.takeIf { !it.isString }?.booleanOrNull ?: return null
                    else -> return null
                }
                Args.Edit(filePath, oldString, newString, replaceAll)
            }
            TandemTools.WRITE_FILE -> Args.Write(filePath, string("content") ?: return null)
            else -> null
        }
    }

    private sealed interface Args {
        val filePath: String

        data class Edit(override val filePath: String, val oldString: String, val newString: String, val replaceAll: Boolean) : Args

        data class Write(override val filePath: String, val content: String) : Args
    }

    private sealed interface Checked {
        data class Refusal(val reply: ToolReply) : Checked

        data class Ready(val proposal: EditProposal) : Checked
    }

    /** What the write command did: wrote [Done.document] (the edit at [Done.ranges] in its new text), or found it stale. */
    private sealed interface InCommand {
        class Done(val document: Document, val ranges: List<IntRange>) : InCommand

        class Stale(val current: String) : InCommand
    }

    private sealed interface Written {
        data object ReadOnly : Written

        class Stale(val current: String) : Written

        class Refused(val reason: String) : Written

        class Unsaved(val reason: String) : Written

        class Saved(val text: String, val ranges: List<IntRange>) : Written
    }

    private companion object {
        val LOG = logger<IdeEditTools>()
        const val MAX_CONTENT_BYTES = 10L * 1024 * 1024
        const val BINARY_PROBE_BYTES = 8 * 1024
        const val STALE_REASON = "the file changed while the diff was open"
        const val NOT_SAVED_REASON = "the IDE did not write it to disk"

        fun notice(fileName: String, reason: String): String = "Could not apply the edit to $fileName: $reason"

        fun fileNameOf(given: String): String = given.substringAfterLast('/').substringAfterLast('\\').ifEmpty { given }

        fun reasonOf(e: Throwable): String {
            val cause = if (e is RuntimeException && e.cause is IOException) e.cause!! else e
            return cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
        }

        /** Where [before] and [after] differ, in [after]'s offsets (empty when they are equal): the region a changed proposal shows. */
        fun changedRegion(before: String, after: String): List<IntRange> {
            val prefix = before.commonPrefixWith(after).length
            val maxSuffix = minOf(before.length, after.length) - prefix
            var suffix = 0
            while (suffix < maxSuffix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
            val end = after.length - suffix
            return if (end <= prefix && before.length == after.length) emptyList() else listOf(prefix until maxOf(prefix, end))
        }
    }
}
