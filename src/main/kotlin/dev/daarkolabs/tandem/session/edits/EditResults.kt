package dev.daarkolabs.tandem.session.edits

import dev.daarkolabs.tandem.protocol.ToolReply

/** How an applied edit was accepted (spec 4.3): in the diff as proposed, in the diff after a hand change, by the mode, by `Accept all`. */
enum class AcceptMode { USER, USER_CHANGED, AUTO_MODE, ACCEPT_ALL }

/**
 * The texts the model gets from Tandem's tools: every row of spec 4.3's table, verbatim. `path` is the absolute path as the model gave
 * it. The whole file goes back while it has at most 2000 lines and 64 KB; past that the edited region with `Read <path> for the rest.`
 * (an error quotes the first 200 lines instead). No result is over 100 KB: the diff block shrinks first, then the file block (the
 * plan's order: the file is what the owner wants back), and a text still over is cut.
 */
object EditResults {
    private const val WHOLE_FILE_LINES = 2_000
    private const val WHOLE_FILE_BYTES = 64 * 1024
    private const val HEAD_LINES = 200
    private const val RESULT_BYTES = 100 * 1024
    private const val CUT_MARK = "\n… cut to stay under 100 KB"
    private val MORE_LINES = Regex("… (\\d+) more lines")

    /**
     * The edit landed and the file was saved. [file] is the file's text now, [ranges] where the edit sits in it (for the region past
     * the bound; the file's start when empty); for [AcceptMode.USER_CHANGED], [proposal] and [applied] are the whole texts the
     * model proposed and the user accepted, whose diff the model is shown (both required for that mode). When that diff is past
     * [EditMatcher.unifiedDiff]'s bound it is left out, with its line, and the file block follows alone.
     */
    fun accepted(path: String, file: String, ranges: List<IntRange>, proposal: String?, applied: String?, mode: AcceptMode): ToolReply {
        val lineCount = EditMatcher.lines(file).size
        val whole = fitsWhole(file)
        val region = if (whole) "" else region(file, ranges)
        val closing = if (whole) "" else "\nRead $path for the rest."
        val text = if (mode == AcceptMode.USER_CHANGED) {
            val proposed = requireNotNull(proposal) { "USER_CHANGED needs the proposal" }
            val accepted = requireNotNull(applied) { "USER_CHANGED needs the applied text" }
            val diff = EditMatcher.unifiedDiff(proposed, accepted, "proposal", "applied")
            val head = "Applied to $path and saved the file. The user changed your proposal in the diff before accepting it." +
                if (diff.isEmpty()) "" else "\nWhat changed between your proposal and what was applied:\n"
            val middle = if (whole) "\n${fileHeader(lineCount)}" else "\nThe file has $lineCount lines; the edited region:\n"
            fit(head, diff, middle, if (whole) EditMatcher.numbered(file) else region, closing)
        } else {
            val head = when (mode) {
                AcceptMode.AUTO_MODE -> "Applied the edit to $path and saved the file (auto-accepted by the user's mode)."
                AcceptMode.ACCEPT_ALL -> "Applied the edit to $path and saved the file (accepted with Accept all)."
                else -> "Applied the edit to $path and saved the file."
            }
            val middle = if (whole) {
                "\n${fileHeader(lineCount)}"
            } else {
                " The file has $lineCount lines; the edited region (line numbers as in the file):\n"
            }
            fit(head, "", middle, if (whole) EditMatcher.numbered(file) else region, closing)
        }
        return ok(text)
    }

    /** `write_file` created [file] at [path]; [changed] when the user changed the proposal in the diff. */
    fun created(path: String, file: String, changed: Boolean): ToolReply =
        ok(withChange("Created $path (${EditMatcher.lines(file).size} lines) and saved it", file, changed))

    /** `write_file` replaced [previous] with [file] at [path]; [changed] when the user changed the proposal in the diff. */
    fun replaced(path: String, file: String, previous: String, changed: Boolean): ToolReply {
        val (added, removed) = EditMatcher.lineChanges(previous, file)
        val lead = "Replaced the content of $path (${EditMatcher.lines(file).size} lines; +$added −$removed against the previous content) " +
            "and saved it"
        return ok(withChange(lead, file, changed))
    }

    fun rejected(reason: String?): ToolReply {
        val said = reason?.trim().orEmpty()
        val base = "The user rejected this edit in the IDE; nothing was changed."
        return error(if (said.isEmpty()) base else "$base\nThe user says: $said")
    }

    fun fileChanged(path: String, current: String): ToolReply =
        error("The file changed while the diff was open; nothing was applied. $path as open in the IDE now reads:\n${quote(current)}")

    fun notFound(path: String, current: String): ToolReply =
        error("old_string was not found in $path. The file as open in the IDE (unsaved changes included) reads:\n${quote(current)}")

    fun ambiguous(path: String, count: Int): ToolReply =
        error("old_string matches $count places in $path. Make it unique with more context, or pass replace_all: true.")

    fun same(): ToolReply = error("old_string and new_string are the same; nothing to change.")

    fun missing(path: String): ToolReply = error("$path does not exist. Use write_file to create it.")

    fun directory(path: String): ToolReply = error("$path is a directory.")

    fun outside(path: String): ToolReply = error("$path is outside the project and its added folders; Tandem edits files there only.")

    fun binary(path: String): ToolReply = error("$path is a binary file; Tandem edits text files only.")

    fun tooLarge(path: String, size: String): ToolReply = error("$path is too large for the IDE to open as text ($size).")

    fun readOnly(path: String): ToolReply = error("$path is read-only and could not be made writable in the IDE.")

    /** The IDE refused the write command. */
    fun refused(path: String, reason: String): ToolReply = error("The IDE could not apply the edit to $path: ${reason.removeSuffix(".")}.")

    /** The write command ran but the save to disk failed: the edit is in the buffer. */
    fun unsaved(path: String, reason: String): ToolReply =
        error("The edit is in the IDE's buffer but could not be saved to $path: ${reason.removeSuffix(".")}.")

    fun planMode(): ToolReply = error("The session is in plan mode: Tandem applies no edits until the plan is approved.")

    /** A call to [tool] whose arguments are missing or not strings. */
    fun malformed(tool: String): ToolReply = error(
        if (tool == "write_file") {
            "write_file needs file_path and content as strings."
        } else {
            "edit_file needs file_path, old_string and new_string as strings."
        },
    )

    private fun ok(text: String): ToolReply = ToolReply(cap(text), isError = false)

    private fun error(text: String): ToolReply = ToolReply(cap(text), isError = true)

    private fun fitsWhole(text: String): Boolean = EditMatcher.lines(text).size <= WHOLE_FILE_LINES && utf8Size(text) <= WHOLE_FILE_BYTES

    private fun fileHeader(lineCount: Int): String =
        if (lineCount == 0) "The file is now empty." else "The file now reads (lines 1-$lineCount of $lineCount):\n"

    /** The snippet of [ranges] in [file], or the file's first 200 lines numbered when no range is known. */
    private fun region(file: String, ranges: List<IntRange>): String =
        if (ranges.isEmpty()) EditMatcher.numbered(EditMatcher.lines(file).take(HEAD_LINES).joinToString("\n")) else EditMatcher.snippet(file, ranges)

    /** The whole file numbered within the bound, else its first 200 lines and how many there are (the errors' quote). */
    private fun quote(text: String): String {
        if (fitsWhole(text)) return EditMatcher.numbered(text)
        val lines = EditMatcher.lines(text)
        return EditMatcher.numbered(lines.take(HEAD_LINES).joinToString("\n")) + "\n… ${lines.size} lines in all; Read the file for the rest"
    }

    /** [lead] closed with a period, or, when the user [changed] the proposal, the whole file (the errors' quote past the bound). */
    private fun withChange(lead: String, file: String, changed: Boolean): String =
        if (changed) "$lead and the user changed your proposal before accepting it; the file now reads:\n${quote(file)}" else "$lead."

    /** `head + diff + middle + body + tail` under 100 KB: [diff] shrinks first, then [body], each cut at a line with `… <n> more lines`. */
    private fun fit(head: String, diff: String, middle: String, body: String, tail: String): String {
        val fixed = utf8Size(head) + utf8Size(middle) + utf8Size(tail)
        var shrunkDiff = diff
        var shrunkBody = body
        if (fixed + utf8Size(diff) + utf8Size(body) > RESULT_BYTES) {
            shrunkDiff = shrinkLines(diff, RESULT_BYTES - fixed - utf8Size(body))
            shrunkBody = shrinkLines(body, RESULT_BYTES - fixed - utf8Size(shrunkDiff))
        }
        return head + shrunkDiff + middle + shrunkBody + tail
    }

    /**
     * [block]'s first lines that fit [budget] UTF-8 bytes with a last line `… <n> more lines`; [block] itself when it fits. A block
     * already cut that way (the diff's 400 lines) has its own count added, so `<n>` stays the number of lines really left out.
     */
    private fun shrinkLines(block: String, budget: Int): String {
        if (utf8Size(block) <= budget) return block
        val all = block.split('\n')
        val earlierCut = MORE_LINES.matchEntire(all.last())?.groupValues?.get(1)?.toIntOrNull()
        val lines = if (earlierCut != null) all.dropLast(1) else all
        val total = lines.size + (earlierCut ?: 0)
        val markRoom = utf8Size("… $total more lines")
        var used = 0
        var kept = 0
        while (kept < lines.size) {
            val next = utf8Size(lines[kept]) + 1
            if (used + next + markRoom > budget) break
            used += next
            kept++
        }
        return (lines.subList(0, kept) + "… ${total - kept} more lines").joinToString("\n")
    }

    /** [text] cut, inside a line if need be and never inside a character, so that it and [CUT_MARK] stay under 100 KB. */
    private fun cap(text: String): String {
        if (utf8Size(text) <= RESULT_BYTES) return text
        val budget = RESULT_BYTES - utf8Size(CUT_MARK)
        var used = 0
        var end = 0
        while (end < text.length) {
            val c = text[end]
            val pair = Character.isHighSurrogate(c) && end + 1 < text.length && Character.isLowSurrogate(text[end + 1])
            val size = when {
                pair -> 4
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            if (used + size > budget) break
            used += size
            end += if (pair) 2 else 1
        }
        return text.substring(0, end) + CUT_MARK
    }

    private fun utf8Size(text: String): Int {
        var size = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            size += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return size
    }
}
