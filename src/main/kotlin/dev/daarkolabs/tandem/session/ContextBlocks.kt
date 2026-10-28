package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.ContextBlock

/**
 * The context blocks a prompt carries before its own text (spec 3.5), and their chips: one form, so that the block sent, the chip
 * the bubble shows and the chip a reloaded transcript gives back match. Pure.
 */
object ContextBlocks {
    /** A block's body is at most this many bytes (UTF-8); a longer buffer is cut there and says how many lines it had. */
    const val MAX_BODY_BYTES: Int = 64 * 1024

    private val UNSAVED_FIRST_LINE = Regex("""^(.+) as open in the IDE, with unsaved changes \((\d+) lines\):$""")

    /** The first line of an unsaved file's block: `<path> as open in the IDE, with unsaved changes (N lines):`. */
    fun unsavedFirstLine(path: String, lines: Int): String = "$path as open in the IDE, with unsaved changes ($lines lines):"

    /**
     * The block for the mentioned file [path] whose buffer [text] has unsaved changes: its first line counts the buffer's lines, and
     * its body is the buffer, cut at [MAX_BODY_BYTES] with the line `… truncated: <T> lines in all` after the cut.
     */
    fun unsavedBlock(path: String, text: String): ContextBlock {
        val lines = lineCount(text)
        return ContextBlock(unsavedFirstLine(path, lines), bounded(text, lines))
    }

    /** The chip of an unsaved file's block: `<name> (unsaved)`. */
    fun unsavedChip(path: String): ContextChip = ContextChip(ContextChipKind.UNSAVED_FILE, "${fileName(path)} (unsaved)", path)

    /** The chip of a sent block whose text is [blockText] (its first line, then the fenced body), or null when it is no context block. */
    fun chipOf(blockText: String): ContextChip? {
        val first = blockText.lineSequence().first()
        UNSAVED_FIRST_LINE.matchEntire(first)?.let { return unsavedChip(it.groupValues[1]) }
        return null
    }

    /**
     * [texts], a user line's text blocks in order, split into the chips of its leading context blocks and the texts after them: the
     * blocks Tandem put first come back as chips, never as typed text.
     */
    fun split(texts: List<String>): Pair<List<ContextChip>, List<String>> {
        val chips = ArrayList<ContextChip>()
        var i = 0
        while (i < texts.size) {
            chips += chipOf(texts[i]) ?: break
            i++
        }
        return chips to texts.subList(i, texts.size)
    }

    /** The last segment of [path], `/` or `\` separated. */
    fun fileName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

    /** How many lines [text] has: none when empty, and a last line without its newline counts. */
    fun lineCount(text: String): Int = when {
        text.isEmpty() -> 0
        else -> text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1
    }

    private fun bounded(text: String, lines: Int): String {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val width = when {
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> 4
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            if (bytes + width > MAX_BODY_BYTES) return text.substring(0, i) + "\n… truncated: $lines lines in all"
            bytes += width
            i += if (width == 4) 2 else 1
        }
        return text
    }
}
