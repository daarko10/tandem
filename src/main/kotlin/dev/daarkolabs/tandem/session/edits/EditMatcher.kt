package dev.daarkolabs.tandem.session.edits

/** Where `old_string` is in a file's text (spec 4.3): the ranges to replace, nowhere, or more than once without `replace_all`. */
sealed interface Match {
    /** The occurrences to replace, in order, each `start until end` in the text that was matched. */
    data class Found(val ranges: List<IntRange>) : Match

    data object NotFound : Match

    /** `old_string` is in [count] places and `replace_all` was not asked for. */
    data class Ambiguous(val count: Int) : Match
}

/**
 * The pure core of `edit_file` (spec 4.3): exact matching as the CLI's `Edit` does (no quote or whitespace normalisation, 11 item 6),
 * applying, the `cat -n` numbering the CLI's tools use (`%6d\t`), and a small Myers line diff for the result texts.
 *
 * The text is a `Document`'s, whose lines end in `\n` only (the IDE restores the file's own separator on save), so the model's
 * `\r\n` and lone `\r` in `old_string` and `new_string` are read as `\n`.
 */
object EditMatcher {
    /**
     * Myers gives up when the lines between the common start and end (both sides together) are more than this: [unifiedDiff] is then
     * empty and the result shows the file block alone (spec 8's whole-file fallback), and [lineChanges] counts those lines.
     */
    private const val MAX_INPUT_LINES = 20_000

    /** The largest edit distance Myers searches; past it the lines between the common start and end are one block. */
    private const val MAX_EDIT_DISTANCE = 2_000

    /** Lines of context around each hunk, as `diff -u` and difflib. */
    private const val HUNK_CONTEXT = 3

    fun match(text: String, oldString: String, replaceAll: Boolean): Match {
        val old = normalizeNewlines(oldString)
        if (old.isEmpty()) return Match.NotFound
        val ranges = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = text.indexOf(old, from)
            if (at < 0) break
            ranges += at until at + old.length
            from = at + old.length
        }
        return when {
            ranges.isEmpty() -> Match.NotFound
            ranges.size > 1 && !replaceAll -> Match.Ambiguous(ranges.size)
            else -> Match.Found(ranges)
        }
    }

    /** [text] with every range of [match] replaced by [newString]. */
    fun apply(text: String, match: Match.Found, newString: String): String {
        val replacement = normalizeNewlines(newString)
        val out = StringBuilder(text.length + match.ranges.size * replacement.length)
        var from = 0
        for (range in match.ranges) {
            out.append(text, from, range.first).append(replacement)
            from = range.last + 1
        }
        return out.append(text, from, text.length).toString()
    }

    /** Where each replacement of [match] sits in [apply]'s result, in order (empty ranges for an empty [newString]). */
    fun appliedRanges(match: Match.Found, newString: String): List<IntRange> {
        val length = normalizeNewlines(newString).length
        var shift = 0
        return match.ranges.map { range ->
            val start = range.first + shift
            shift += length - (range.last + 1 - range.first)
            start until start + length
        }
    }

    /**
     * The lines of [text] that [ranges] touch, with [context] lines around each, numbered as in the file (`%6d\t`); windows that
     * meet are merged, and separate windows are joined by a line `…`. Empty when [text] has no lines or no range is given.
     */
    fun snippet(text: String, ranges: List<IntRange>, context: Int = 4): String {
        val lines = lines(text)
        if (lines.isEmpty() || ranges.isEmpty()) return ""
        val starts = lineStarts(text)
        val windows = ranges
            .map { range ->
                val first = lineOf(starts, range.first, lines.size)
                val last = if (range.isEmpty()) first else lineOf(starts, range.last, lines.size)
                maxOf(1, first - context)..minOf(lines.size, last + context)
            }
            .sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        for (window in windows) {
            val previous = merged.lastOrNull()
            if (previous != null && window.first <= previous.last + 1) {
                merged[merged.size - 1] = previous.first..maxOf(previous.last, window.last)
            } else {
                merged += window
            }
        }
        return merged.joinToString("\n…\n") { window -> window.joinToString("\n") { numberedLine(it, lines[it - 1]) } }
    }

    /** [text] as `cat -n` shows it: `%6d\t<line>` per line, joined by `\n`; a final newline ends the last line and adds none. */
    fun numbered(text: String): String = lines(text).withIndex().joinToString("\n") { (index, line) -> numberedLine(index + 1, line) }

    /**
     * The unified diff of [a] → [b] by line, in difflib's layout (`--- aName`, `+++ bName`, `@@ -s,l +s,l @@` hunks with 3 lines
     * of context, no trailing newline), empty when the two have the same lines. Longer than [maxLines] lines, it is cut to
     * `maxLines - 1` lines and a last line `… <n> more lines`. Empty too when more than 20 000 lines (both sides together) lie between
     * the common start and end: the caller then shows the file alone.
     */
    fun unifiedDiff(a: String, b: String, aName: String, bName: String, maxLines: Int = 400): String {
        val ops = lineOps(lines(a), lines(b)) ?: return ""
        if (ops.all { it.kind == ' ' }) return ""
        val out = ArrayList<String>()
        out += "--- $aName"
        out += "+++ $bName"
        for (hunk in hunks(ops)) {
            val first = hunk.first()
            val aLength = hunk.count { it.kind != '+' }
            val bLength = hunk.count { it.kind != '-' }
            out += "@@ -${hunkRange(first.aIndex, aLength)} +${hunkRange(first.bIndex, bLength)} @@"
            hunk.forEach { out += "${it.kind}${it.line}" }
        }
        return cutLines(out, maxLines).joinToString("\n")
    }

    /**
     * The lines [b] adds to [a] and the lines it removes, as `+<added> −<removed>` counts them (the same diff as [unifiedDiff]); past
     * the 20 000-line bound, the lines between the common start and end on each side, an upper bound.
     */
    fun lineChanges(a: String, b: String): Pair<Int, Int> {
        val aLines = lines(a)
        val bLines = lines(b)
        val ops = lineOps(aLines, bLines)
        if (ops == null) {
            val (prefix, suffix) = commonEnds(aLines, bLines)
            return (bLines.size - prefix - suffix) to (aLines.size - prefix - suffix)
        }
        return ops.count { it.kind == '+' } to ops.count { it.kind == '-' }
    }

    /** [lines] cut to [maxLines] with a last line `… <n> more lines` when longer. */
    internal fun cutLines(lines: List<String>, maxLines: Int): List<String> {
        if (lines.size <= maxLines) return lines
        val keep = maxOf(0, maxLines - 1)
        return lines.subList(0, keep) + "… ${lines.size - keep} more lines"
    }

    /** [text]'s lines: split on `\n`, a final `\n` ending the last line; none for an empty text. */
    internal fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n')
        return if (parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    /**
     * [text] with `\r\n` and a lone `\r` read as `\n`: what [match] looks for, what [apply] and [appliedRanges] measure, and the text
     * to write into the `Document` (which rejects `\r`) for each range of a [Match.Found].
     */
    fun normalizeNewlines(text: String): String =
        if ('\r' in text) text.replace("\r\n", "\n").replace('\r', '\n') else text

    private fun numberedLine(number: Int, line: String): String = number.toString().padStart(6) + "\t" + line

    private fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        text.forEachIndexed { index, c -> if (c == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    /** The 1-based line holding [offset], at most [lineCount] (an offset at the end of a text ending in `\n` is its last line). */
    private fun lineOf(starts: IntArray, offset: Int, lineCount: Int): Int {
        val found = starts.binarySearch(offset)
        val line = if (found >= 0) found + 1 else -found - 1
        return line.coerceIn(1, lineCount)
    }

    /** difflib's `_format_range_unified`: `start` alone for one line, `start-1,0` for none, else `start,length`. */
    private fun hunkRange(index: Int, length: Int): String = when (length) {
        1 -> "${index + 1}"
        0 -> "$index,0"
        else -> "${index + 1},$length"
    }

    /** One line of the edit script: [kind] ` ` kept, `-` removed, `+` added; [aIndex] and [bIndex] are the 0-based positions before it. */
    private class Op(val kind: Char, val line: String, val aIndex: Int, val bIndex: Int)

    /** The lines the two texts share at their start and, after those, at their end. */
    private fun commonEnds(a: List<String>, b: List<String>): Pair<Int, Int> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        return prefix to suffix
    }

    /** The edit script of [a] → [b]: the common start and end kept, Myers between; null when that middle is over [MAX_INPUT_LINES]. */
    private fun lineOps(a: List<String>, b: List<String>): List<Op>? {
        val (prefix, suffix) = commonEnds(a, b)
        val aEnd = a.size - suffix
        val bEnd = b.size - suffix
        if ((aEnd - prefix) + (bEnd - prefix) > MAX_INPUT_LINES) return null
        val ops = ArrayList<Op>(a.size + b.size)
        for (i in 0 until prefix) ops += Op(' ', a[i], i, i)
        if (!myers(ops, a, b, prefix, aEnd, prefix, bEnd)) block(ops, a, b, prefix, aEnd, prefix, bEnd)
        for (i in 0 until suffix) ops += Op(' ', a[aEnd + i], aEnd + i, bEnd + i)
        return ops
    }

    /** Every line of `a[aFrom, aTo)` removed, then every line of `b[bFrom, bTo)` added. */
    private fun block(ops: MutableList<Op>, a: List<String>, b: List<String>, aFrom: Int, aTo: Int, bFrom: Int, bTo: Int) {
        for (i in aFrom until aTo) ops += Op('-', a[i], i, bFrom)
        for (j in bFrom until bTo) ops += Op('+', b[j], aTo, j)
    }

    /**
     * Myers' shortest edit script for `a[aFrom, aTo)` → `b[bFrom, bTo)`, appended to [ops]; false (and nothing appended) when the
     * edit distance is over [MAX_EDIT_DISTANCE]. The trace keeps only each round's diagonals `-(d+1)..d+1`, so it costs about d².
     */
    private fun myers(ops: MutableList<Op>, a: List<String>, b: List<String>, aFrom: Int, aTo: Int, bFrom: Int, bTo: Int): Boolean {
        val n = aTo - aFrom
        val m = bTo - bFrom
        val max = n + m
        val limit = minOf(max, MAX_EDIT_DISTANCE)
        val offset = limit + 1
        val v = IntArray(2 * limit + 3)
        val trace = ArrayList<IntArray>()
        var found = -1
        search@ for (d in 0..limit) {
            trace += v.copyOfRange(offset - d - 1, offset + d + 2)
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[aFrom + x] == b[bFrom + y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    found = d
                    break@search
                }
            }
        }
        if (found < 0) return false
        val script = ArrayList<Op>()
        var x = n
        var y = m
        for (d in found downTo 0) {
            val previous = trace[d]
            fun at(k: Int): Int = previous[k + d + 1]
            val k = x - y
            val prevK = if (k == -d || (k != d && at(k - 1) < at(k + 1))) k + 1 else k - 1
            val prevX = if (d == 0) 0 else at(prevK)
            val prevY = if (d == 0) 0 else prevX - prevK
            while (x > prevX && y > prevY) {
                x--
                y--
                script += Op(' ', a[aFrom + x], aFrom + x, bFrom + y)
            }
            if (d > 0) {
                if (x == prevX) {
                    y--
                    script += Op('+', b[bFrom + y], aFrom + x, bFrom + y)
                } else {
                    x--
                    script += Op('-', a[aFrom + x], aFrom + x, bFrom + y)
                }
            }
        }
        script.reverse()
        ops += script
        return true
    }

    /** The edit script's changes grouped into hunks with [HUNK_CONTEXT] kept lines around them; changes closer than twice that share one. */
    private fun hunks(ops: List<Op>): List<List<Op>> {
        val changed = ops.indices.filter { ops[it].kind != ' ' }
        val result = ArrayList<List<Op>>()
        var start = 0
        while (start < changed.size) {
            var end = start
            while (end + 1 < changed.size && changed[end + 1] - changed[end] - 1 <= 2 * HUNK_CONTEXT) end++
            val from = maxOf(0, changed[start] - HUNK_CONTEXT)
            val to = minOf(ops.size, changed[end] + HUNK_CONTEXT + 1)
            result += ops.subList(from, to)
            start = end + 1
        }
        return result
    }
}
