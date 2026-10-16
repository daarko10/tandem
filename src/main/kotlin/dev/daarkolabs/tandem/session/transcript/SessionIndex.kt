package dev.daarkolabs.tandem.session.transcript

import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

private val LOG = logger<SessionIndex>()

/** A session's transcript: its id, a lower-case UUID, and `.jsonl`. The folder holds other files too (`agent-*.jsonl`, temporary files). */
private val TRANSCRIPT_NAME: Regex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jsonl$")

private const val TRANSCRIPT_SUFFIX: String = ".jsonl"

/** How long the folders must be quiet before [SessionIndex.changes] signals. */
private const val CHANGE_DEBOUNCE_MILLIS: Long = 500

/** How often the watcher looks for folders that do not exist yet. */
private const val FOLDER_POLL_MILLIS: Long = 2_000

private val NEWEST_FIRST: Comparator<SessionSummary> = compareByDescending<SessionSummary> { it.lastModified }.thenBy { it.id }

/** One session in a project's transcript folder, as the session list shows it. */
data class SessionSummary(
    /** The transcript's file name without `.jsonl`. */
    val id: String,
    /** [TranscriptMeta.title]: the custom title, else the generated one, else the first prompt, else the [id]. */
    val title: String,
    /** The transcript's modification time, in milliseconds since the epoch. */
    val lastModified: Long,
    /** [TranscriptMeta.firstPrompt]. */
    val firstPrompt: String?,
    /** The transcript. */
    val file: Path,
)

/**
 * The sessions of one project: every `<session id>.jsonl` in the CLI's transcript [folders] (see [TranscriptFolder.candidates]).
 * Other files in them, such as a subagent's `agent-*.jsonl`, are no sessions and are skipped.
 *
 * A transcript's [TranscriptMeta] takes two passes over the whole file, so a file is read again only when its size or modification
 * time changed, or after [invalidate]. A transcript that cannot be read is left out of the listing. [list] and [invalidate] never
 * throw and may be called from several threads; [list] blocks on file I/O, so call it off the EDT.
 */
class SessionIndex(
    private val folders: () -> List<Path>,
    private val projectPath: String,
    private val meta: (Path) -> TranscriptMeta = TranscriptReader::meta,
) {
    /** What [list] knows about a transcript, and the size and modification time it knew it at. */
    private class Entry(val size: Long, val modified: FileTime, val cwd: String?, val summary: SessionSummary)

    private val cache = ConcurrentHashMap<Path, Entry>()

    /**
     * Every session in the folders, newest first, ties by id. A folder that does not exist has none: a project's first session
     * creates it.
     *
     * With more than one folder (a project path longer than the CLI's folder key, see [TranscriptFolder.candidates]) the folders
     * may hold other projects' sessions too, so those whose transcript names another working directory than [projectPath] are
     * dropped. With one folder nothing is dropped.
     */
    fun list(): List<SessionSummary> {
        val candidates: List<Path> = try {
            folders()
        } catch (e: Exception) {
            LOG.warn("transcript folders not known (${e.javaClass.simpleName})")
            emptyList()
        }
        val seen = HashSet<Path>()
        val found = ArrayList<Entry>()
        for (folder in candidates) {
            for (file in transcriptsIn(folder)) {
                val entry = entryOf(file) ?: continue
                seen.add(file) // `add`, not `+=`: a Path is an Iterable<Path>, so `+=` would add its name elements instead
                found.add(entry)
            }
        }
        cache.keys.retainAll(seen) // transcripts that are gone
        val own = if (candidates.size > 1) found.filter { it.cwd == null || it.cwd == projectPath } else found
        return own.map { it.summary }.sortedWith(NEWEST_FIRST)
    }

    /** Forgets session [id], so that the next [list] reads its transcript again. */
    fun invalidate(id: String) {
        cache.values.removeIf { it.summary.id == id }
    }

    /**
     * A signal whenever something happens in the folders: a transcript is created, written to or deleted. Call [list] after one.
     *
     * Every folder that exists is watched with a [WatchService]; the others are looked for every 2 s, since a project's first
     * session creates its folder, and signalled once found because they may hold transcripts already. A burst of events is one
     * signal, sent when the folders have been quiet for 500 ms. What is there when the watching starts is not signalled: [list] it.
     *
     * Collecting runs on [Dispatchers.IO] and holds the watch service until it is cancelled. Where the file system cannot be
     * watched the flow logs that and ends without a signal, and sessions then show up on the next [list] only. It never throws.
     */
    @OptIn(FlowPreview::class)
    fun changes(): Flow<Unit> = watchFolders(folders).debounce(CHANGE_DEBOUNCE_MILLIS)

    /** The folder's transcripts; none for a folder that is missing or cannot be listed. */
    private fun transcriptsIn(folder: Path): List<Path> =
        try {
            Files.newDirectoryStream(folder).use { entries -> entries.filter { TRANSCRIPT_NAME.matches(it.name) } }
        } catch (_: NoSuchFileException) {
            emptyList()
        } catch (e: Exception) {
            LOG.warn("transcript folder not listed (${e.javaClass.simpleName})")
            emptyList()
        }

    /** What is known about [file]: from the cache if it has not changed, else read afresh. Null if it is no longer a readable file. */
    private fun entryOf(file: Path): Entry? =
        try {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            val size = attributes.size()
            val modified = attributes.lastModifiedTime()
            val cached = cache[file]
            when {
                !attributes.isRegularFile -> null
                cached != null && cached.size == size && cached.modified == modified -> cached
                else -> {
                    // Size and time are taken before the read: if the CLI appends meanwhile, the next listing sees a change.
                    val transcript = meta(file)
                    val summary = SessionSummary(
                        file.name.removeSuffix(TRANSCRIPT_SUFFIX), transcript.title, modified.toMillis(), transcript.firstPrompt, file,
                    )
                    Entry(size, modified, transcript.cwd, summary).also { cache[file] = it }
                }
            }
        } catch (_: NoSuchFileException) {
            null // deleted since the folder was listed
        } catch (e: Exception) {
            LOG.warn("transcript ${file.name} not listed (${e.javaClass.simpleName})")
            null
        }
}

/**
 * One signal per batch of events in the [folders], watched with the service that [newWatchService] creates; the flow behind
 * [SessionIndex.changes], without its debounce. A folder that does not exist, or that was deleted, is looked for every
 * [pollMillis] and signalled when it is found again. Internal so tests can pass a watch service that fails, and a shorter poll.
 */
internal fun watchFolders(
    folders: () -> List<Path>,
    newWatchService: () -> WatchService = { FileSystems.getDefault().newWatchService() },
    pollMillis: Long = FOLDER_POLL_MILLIS,
): Flow<Unit> = callbackFlow<Unit> {
    val service = try {
        newWatchService()
    } catch (e: Exception) { // UnsupportedOperationException or IOException: this file system cannot be watched
        logGivingUp(e)
        close()
        return@callbackFlow
    }
    launch {
        try {
            val watch = FolderWatch(service, folders)
            watch.registerNew()
            val pollNanos = TimeUnit.MILLISECONDS.toNanos(pollMillis)
            var nextCheck = System.nanoTime() + pollNanos
            // Waits for events until it is time to look for folders again, so that a busy folder cannot keep the others waiting.
            while (isActive) {
                val wait = nextCheck - System.nanoTime()
                if (wait > 0) {
                    if (watch.awaitEvents(wait)) trySend(Unit)
                } else {
                    nextCheck = System.nanoTime() + pollNanos
                    if (watch.registerNew()) trySend(Unit)
                }
            }
        } catch (_: ClosedWatchServiceException) {
            // awaitClose closed the service under a blocked poll: the collector is gone.
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            logGivingUp(e)
        } finally {
            close()
        }
    }
    awaitClose {
        try {
            service.close()
        } catch (_: IOException) {
            // Nothing left to release.
        }
    }
}.flowOn(Dispatchers.IO)

private fun logGivingUp(e: Exception) {
    LOG.info("transcript folders cannot be watched (${e.javaClass.simpleName}); sessions show up when the list is refreshed")
}

/** The folders registered with a watch service. Not thread-safe: one coroutine drives it. */
private class FolderWatch(private val service: WatchService, private val folders: () -> List<Path>) {
    private val watched = HashMap<Path, WatchKey>()

    /** Registers each folder that exists and is not watched yet. True if there was one. */
    fun registerNew(): Boolean {
        watched.values.removeIf { !it.isValid } // a deleted folder's watch is dead; the folder may be created again
        var registered = false
        for (folder in folders()) {
            if (folder in watched || !Files.isDirectory(folder)) continue
            watched[folder] = try {
                folder.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            } catch (_: NoSuchFileException) {
                continue // deleted again since it was found; the next round looks again
            }
            registered = true
        }
        return registered
    }

    /** Waits up to [nanos] for the next batch of events in a watched folder. True if the batch holds events. */
    fun awaitEvents(nanos: Long): Boolean {
        val key = service.poll(nanos, TimeUnit.NANOSECONDS) ?: return false
        val events = key.pollEvents()
        key.reset() // false for a deleted folder, whose dead key registerNew drops
        return events.isNotEmpty()
    }
}
