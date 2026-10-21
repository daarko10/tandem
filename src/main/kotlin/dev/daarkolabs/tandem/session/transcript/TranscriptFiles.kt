package dev.daarkolabs.tandem.session.transcript

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The only writes Tandem makes to the CLI's transcript folders: a rename's `custom-title` line, and deleting a session. Both block
 * on file I/O, so call them off the EDT, and both throw the [java.io.IOException] of a write that fails.
 */
internal object TranscriptFiles {
    /** ISO-8601 in UTC with milliseconds, as JavaScript's `Date.toISOString()` writes the CLI's own timestamps. */
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /**
     * Appends to [transcript], which must exist, the line the SDK's `renameSession` appends:
     * `{"type":"custom-title","customTitle":<title>,"sessionId":<sessionId>,"uuid":<random UUID>,"timestamp":<time>}`. The last
     * such line is the session's title, for [TranscriptReader] as for the CLI. If the file ends inside a line (a CLI that stopped
     * while writing), that line is ended first, so that both stay whole.
     */
    fun appendCustomTitle(transcript: Path, sessionId: String, title: String, time: Instant) {
        val line = buildJsonObject {
            put("type", "custom-title")
            put("customTitle", title)
            put("sessionId", sessionId)
            put("uuid", UUID.randomUUID().toString())
            put("timestamp", TIMESTAMP.format(time))
        }
        val text = (if (endsInsideLine(transcript)) "\n" else "") + line + "\n"
        Files.write(transcript, text.toByteArray(Charsets.UTF_8), StandardOpenOption.WRITE, StandardOpenOption.APPEND)
    }

    /**
     * Deletes a session's [transcript], `<id>.jsonl`, and what the CLI keeps beside it in `<id>/` (subagent transcripts, tool
     * results, a `custom-title.json`), as the SDK's `deleteSession` does. Symbolic links are deleted, never followed: nothing outside
     * the folder is touched, even when `<id>` itself is a link.
     */
    fun delete(transcript: Path) {
        Files.deleteIfExists(transcript)
        val sidecar = transcript.resolveSibling(transcript.fileName.toString().removeSuffix(".jsonl"))
        if (!Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) return
        // Files.walk follows no links: a link is listed, and so deleted, as itself. Reversed, every entry comes before its folder.
        val entries = Files.walk(sidecar).use { paths -> paths.sorted(Comparator.reverseOrder()).toList() }
        entries.forEach { Files.deleteIfExists(it) }
    }

    /** Whether [file]'s last byte is not a line feed. An empty file ends no line. */
    private fun endsInsideLine(file: Path): Boolean =
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            if (size == 0L) return false
            val last = ByteBuffer.allocate(1)
            channel.read(last, size - 1)
            last.get(0) != '\n'.code.toByte()
        }
}
