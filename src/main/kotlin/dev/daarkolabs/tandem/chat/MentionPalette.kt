package dev.daarkolabs.tandem.chat

/** The mention palette lists at most this many files (spec 3.5). */
const val MENTION_ROWS: Int = 40

/** The `@…` being typed under the caret: where its `@` is in the draft ([start]) and what follows it up to the caret ([text]). */
data class MentionQuery(val start: Int, val text: String)

/**
 * The mention [draft] has under the [caret] (spec 3.5): an `@` at the start of the draft or after whitespace, and what follows it up
 * to the caret, with no whitespace in between. An `@` inside a word (`a@b`) opens nothing, and neither does a mention a space already
 * follows (the caret after `@path `). A caret outside the draft gives null.
 */
fun mentionQuery(draft: String, caret: Int): MentionQuery? {
    if (caret < 0 || caret > draft.length) return null
    var start = caret
    while (start > 0 && !draft[start - 1].isWhitespace()) start--
    if (start == caret || draft[start] != '@') return null
    return MentionQuery(start, draft.substring(start + 1, caret))
}

/**
 * The mention open on [draft] with its caret at [caret], unless Esc closed the palette on this very draft ([dismissedFor]): it stays
 * closed until the draft changes (spec 3.5, as the slash palette's Esc).
 */
fun visibleMention(draft: String, caret: Int, dismissedFor: String?): MentionQuery? =
    if (draft == dismissedFor) null else mentionQuery(draft, caret)

/** The palette of [query]: its file [rows] (at most [MENTION_ROWS]) and the [selected] row's index. */
data class MentionPalette(val query: MentionQuery, val rows: List<String>, val selected: Int) {
    /** This palette with the selection moved by [delta] rows, wrapping around; the same palette when it has no rows. */
    fun moved(delta: Int): MentionPalette =
        if (rows.isEmpty()) this else copy(selected = Math.floorMod(selected + delta, rows.size))

    /** The selected row's path, or null when the palette lists nothing. */
    val pick: String? get() = rows.getOrNull(selected)
}

/**
 * The palette for [query] over [candidates], project-relative paths in the order the matcher ranked them: the open editors' files
 * ([openFirst]) first, then the rest, each path once, at most [MENTION_ROWS], every one matching [query] ([matchesMention]). The
 * first row is selected.
 */
fun mentionPalette(query: MentionQuery, candidates: List<String>, openFirst: List<String>): MentionPalette {
    val rows = LinkedHashSet<String>()
    for (path in openFirst.asSequence() + candidates.asSequence()) {
        if (rows.size == MENTION_ROWS) break
        if (matchesMention(path, query.text)) rows += path
    }
    return MentionPalette(query, rows.toList(), 0)
}

/**
 * Whether [path] matches [text]: its characters appear in the path in order, case ignored (what the IDE's own matcher, which ranks
 * the candidates, finds as well). An empty [text] matches every path.
 */
fun matchesMention(path: String, text: String): Boolean {
    var i = 0
    for (c in path) {
        if (i == text.length) break
        if (c.equals(text[i], ignoreCase = true)) i++
    }
    return i == text.length
}

/**
 * [draft] with [query]'s `@…` replaced by `@<path> `, and the caret after that space (spec 3.5). A space already after the typed text
 * is kept, not doubled.
 */
fun insertMention(draft: String, query: MentionQuery, path: String): Pair<String, Int> {
    val end = query.start + 1 + query.text.length
    val mention = "@$path"
    val spaced = end < draft.length && draft[end] == ' '
    val text = draft.substring(0, query.start) + mention + (if (spaced) "" else " ") + draft.substring(end)
    return text to query.start + mention.length + 1
}

/** What ends a sentence rather than a path: left out of a mention's path when it closes the word (`see @a.kt,`). */
private const val TRAILING_PUNCTUATION: String = ".,;:!?)\"'"

/**
 * The `@path` mentions in [text], in order: an `@` at the start or after whitespace, then the path up to the next whitespace, less
 * any [TRAILING_PUNCTUATION] that closes the word.
 */
fun mentionsIn(text: String): List<MentionSpan> {
    val spans = ArrayList<MentionSpan>()
    var i = 0
    while (i < text.length) {
        if (text[i] == '@' && (i == 0 || text[i - 1].isWhitespace())) {
            var end = i + 1
            while (end < text.length && !text[end].isWhitespace()) end++
            val next = end
            while (end > i + 1 && text[end - 1] in TRAILING_PUNCTUATION) end--
            if (end > i + 1) spans += MentionSpan(i, end, text.substring(i + 1, end))
            i = next
        } else {
            i++
        }
    }
    return spans
}

/** One `@path` in a text: the `@` at [start], the path's end at [end] (exclusive), and the [path] itself. */
data class MentionSpan(val start: Int, val end: Int, val path: String)

/**
 * The palette shown for [query] (null: none is open) over [ranked], the last lookup's rows, best first: those still matching the
 * query ([mentionPalette]), with the row [selection] chose when it was made on this very query, else the first. Null when no row
 * matches, so that Enter then sends as usual.
 */
fun shownMention(query: MentionQuery?, ranked: List<String>, selection: Pair<MentionQuery, Int>?): MentionPalette? {
    if (query == null) return null
    val palette = mentionPalette(query, ranked, emptyList())
    if (palette.rows.isEmpty()) return null
    val selected = selection?.takeIf { it.first == query }?.second?.coerceIn(0, palette.rows.size - 1) ?: 0
    return palette.copy(selected = selected)
}
