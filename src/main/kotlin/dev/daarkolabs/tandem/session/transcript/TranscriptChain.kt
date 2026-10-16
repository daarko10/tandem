package dev.daarkolabs.tandem.session.transcript

import dev.daarkolabs.tandem.protocol.array
import dev.daarkolabs.tandem.protocol.boolean
import dev.daarkolabs.tandem.protocol.obj
import dev.daarkolabs.tandem.protocol.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.BitSet

/**
 * The first pass over a transcript: for each line, only what places it in the conversation; then [abandoned] decides which
 * conversational lines (`user` or `assistant` lines that are neither sidechain nor meta) are off the current history.
 *
 * A transcript is only ever appended to, so it keeps the turns a rewind abandoned. The current history is the `parentUuid`
 * chain that ends at the last conversational line, passing through lines of any type. When that chain starts at a
 * compaction (a compact summary, or a `compact_boundary` line), the lines before the compaction add their own chain, found
 * the same way. Parallel tool calls branch off a chain, so lines of a kept message join it as well: an assistant line with
 * the `message.id` of a kept assistant line, and a user line holding only tool results whose parent is kept, until nothing
 * changes. A user line with text never joins this way: a rewound branch starts with one.
 */
internal class TranscriptChain {
    private val uuids = ArrayList<String?>()
    private val parents = ArrayList<String?>()
    private val messageIds = ArrayList<String?>()
    private val conversational = BitSet()
    private val toolResultsOnly = BitSet()
    private val compactionRoots = BitSet()

    /** The number of lines added. */
    val lineCount: Int
        get() = uuids.size

    /** Takes [line], the next line of the file. */
    fun add(line: String) {
        val index = lineCount
        val raw = parseObject(line)
        val type = raw?.string("type")
        val isConversational = raw != null && (type == "user" || type == "assistant") && !raw.isSet("isSidechain") && !raw.isSet("isMeta")
        uuids += raw?.string("uuid")
        parents += raw?.string("parentUuid")
        messageIds += raw?.takeIf { isConversational && type == "assistant" }?.obj("message")?.string("id")
        if (raw == null) return
        if (isConversational) conversational.set(index)
        if (isConversational && type == "user" && raw.holdsOnlyToolResults()) toolResultsOnly.set(index)
        if (type == "user" && raw.isSet("isCompactSummary") || type == "system" && raw.string("subtype") == "compact_boundary") {
            compactionRoots.set(index)
        }
    }

    /** The conversational lines off the current history. A line without a uuid is never one of them. */
    fun abandoned(): BitSet {
        val byUuid = HashMap<String, Int>()
        uuids.forEachIndexed { index, uuid -> if (uuid != null) byUuid[uuid] = index }
        val kept = chains(byUuid)
        joinToolCalls(kept, byUuid)
        val abandoned = BitSet()
        var line = conversational.nextSetBit(0)
        while (line >= 0) {
            if (uuids[line] != null && !kept[line]) abandoned.set(line)
            line = conversational.nextSetBit(line + 1)
        }
        return abandoned
    }

    /** Every line on the current chains: the one from the last conversational line, then one more before each compaction. */
    private fun chains(byUuid: Map<String, Int>): BitSet {
        val kept = BitSet()
        var below = lineCount
        while (true) {
            val leaf = conversational.previousSetBit(below - 1)
            if (leaf < 0) break
            var line = leaf
            while (true) {
                kept.set(line)
                val parent = parentOf(line, byUuid)
                if (parent == null || kept[parent]) break // the root, or a loop in a broken file
                line = parent
            }
            if (!compactionRoots[line]) break
            below = minOf(line, leaf) // lower on every walk, so even a broken file ends the loop
        }
        return kept
    }

    /** Adds to [kept] the lines of kept messages that parallel tool calls left off the chains, until nothing changes. */
    private fun joinToolCalls(kept: BitSet, byUuid: Map<String, Int>) {
        val keptMessages = HashSet<String>()
        var line = kept.nextSetBit(0)
        while (line >= 0) {
            messageIds[line]?.let(keptMessages::add)
            line = kept.nextSetBit(line + 1)
        }
        do {
            var changed = false
            var candidate = conversational.nextSetBit(0)
            while (candidate >= 0) {
                if (!kept[candidate] && joins(candidate, kept, keptMessages, byUuid)) {
                    kept.set(candidate)
                    changed = true
                }
                candidate = conversational.nextSetBit(candidate + 1)
            }
        } while (changed)
    }

    private fun joins(line: Int, kept: BitSet, keptMessages: Set<String>, byUuid: Map<String, Int>): Boolean {
        val messageId = messageIds[line]
        if (messageId != null) return messageId in keptMessages
        return toolResultsOnly[line] && parentOf(line, byUuid)?.let { kept[it] } == true
    }

    private fun parentOf(line: Int, byUuid: Map<String, Int>): Int? = parents[line]?.let(byUuid::get)
}

/** A flag that is present and true. */
internal fun JsonObject.isSet(flag: String): Boolean = boolean(flag) == true

/** [line] as a JSON object, or null when it is not one (such as a line the CLI is still writing). */
private fun parseObject(line: String): JsonObject? = try {
    Json.parseToJsonElement(line) as? JsonObject
} catch (_: IllegalArgumentException) { // SerializationException: not JSON
    null
}

/** A user line whose `message.content` is a non-empty list of `tool_result` blocks and nothing else. */
private fun JsonObject.holdsOnlyToolResults(): Boolean {
    val content = obj("message")?.array("content") ?: return false
    return content.isNotEmpty() && content.all { (it as? JsonObject)?.string("type") == "tool_result" }
}
