package dev.daarkolabs.tandem.ide.edits

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.vfs.VirtualFile
import dev.daarkolabs.tandem.session.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

/**
 * One `edit_file` or `write_file` call, checked and ready for review (spec 4.3): the [call] it answers, the [file] and its [document]
 * (null for a new file), the [path] it resolved to (real, inside a root), the [original] text the diff shows on the left (the
 * document's text when the proposal was built; empty for a new file), the [proposed] text on the right, the matched [ranges] in
 * [original] (empty for `write_file`), whether the file [isNew] and the [tool]'s name. [markers] follow [ranges] in the live
 * document while the diff is open (spec 3.1), read by [IdeEditTools.apply] inside the write command.
 */
data class EditProposal(
    val call: ToolCall,
    val file: VirtualFile?,
    val path: Path,
    val document: Document?,
    val original: String,
    val proposed: String,
    val ranges: List<IntRange>,
    val isNew: Boolean,
    val tool: String,
    val markers: List<RangeMarker> = emptyList(),
) {
    /** The file's name, as the write command, the diff and the notices show it. */
    val fileName: String get() = path.fileName?.toString() ?: path.toString()

    /** The path as the model gave it: what the result texts quote (spec 4.3). */
    val givenPath: String get() = (call.arguments["file_path"] as? JsonPrimitive)?.content ?: path.toString()
}

/** What the user decided in the diff (spec 3.2): Accept with the right side's [finalText] ([all] for `Accept all`), Reject, or Stop. */
sealed interface Decision {
    data class Accepted(val finalText: String, val all: Boolean) : Decision

    data class Rejected(val reason: String?) : Decision

    /** The CLI withdrew the call (Stop, `notifications/cancelled`): nothing is applied and no reply is sent. */
    data object Stopped : Decision
}

/**
 * The document no longer holds what the proposal was built on (spec 3.2's check); [currentText] is what it holds now. Not thrown:
 * [IdeEditTools.apply] finds the stale case inside its write command, writes nothing and reports it as `EditResults.fileChanged`
 * (with the notice `the file changed while the diff was open`), so a caller never catches this.
 */
class EditStale(val currentText: String) : RuntimeException("the file changed while the diff was open")
