package dev.daarkolabs.tandem.session.transcript

import com.intellij.openapi.diagnostic.debug
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
import java.io.UncheckedIOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.ProviderMismatchException
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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

/**
 * Runs [block], which calls the file system, and gives [onFailure]'s result if the JDK reports a failure: an I/O error, a refused
 * access, or a path it cannot parse (the folder lookup builds paths from the config directory). Nothing else is caught, so that a
 * bug, or a cancellation, is never swallowed.
 */
private inline fun <T> tryFileSystem(onFailure: (Exception) -> T, block: () -> T): T =
    try {
        block()
    } catch (e: IOException) {
        onFailure(e)
    } catch (e: UncheckedIOException) {
        onFailure(e)
    } catch (e: SecurityException) {
        onFailure(e)
    } catch (e: InvalidPathException) {
        onFailure(e)
    }

/** [tryFileSystem], and what a watch service reports: a file system that cannot be watched, or a folder from another provider. */
private inline fun <T> tryWatching(onFailure: (Exception) -> T, block: () -> T): T =
    try {
        tryFileSystem(onFailure, block)
    } catch (e: UnsupportedOperationException) {
        onFailure(e)
    } catch (e: ProviderMismatchException) {
        onFailure(e)
    }

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
    /**
     * The transcript. [SessionIndex.list] always has one; it is null for an open session the CLI has not written a transcript for
     * yet, which the session manager lists before its first prompt.
     */
    val file: Path?,
)

/**
 * The sessions of one project: every `<session id>.jsonl` in the CLI's transcript [folders] (see [TranscriptFolder.candidates]).
 * Other files in them, such as a subagent's `agent-*.jsonl`, are no sessions and are skipped.
 *
 * A transcript's [TranscriptMeta] takes a pass over the whole file, so a file is read again only when its size or modification
 * time changed, or after [invalidate]. The CLI only appends to a transcript, so one that only grew is not read in full again: the
 * lines appended since are scanned for a new title ([TranscriptReader.withAppendedTitles]), and the rest of what was read stays.
 * It is read in full when it shrank, when it was read before it had a prompt, or when the part read before did not end a line.
 * When the file system fails on a folder or a transcript (an I/O error, a refused access), that one is left out of the listing and
 * a warning goes to the log; [list] does not throw for it. A transcript that cannot be read is tried again only once its size or
 * modification time changed, or after [invalidate]. [list] and [invalidate] may be called from several threads; [list]
 * blocks on file I/O, so call it off the EDT.
 *
 * The project's path is read on first use, by [list], and never by the constructor: a caller that has to ask the file system for it
 * (to resolve links) passes a function that does, and can make the index on the EDT. It is read once.
 */
class SessionIndex(
    private val folders: () -> List<Path>,
    projectPath: () -> String,
    private val meta: (Path) -> TranscriptMeta = TranscriptReader::meta,
) {
    /** An index for a project path known up front. */
    constructor(
        folders: () -> List<Path>,
        projectPath: String,
        meta: (Path) -> TranscriptMeta = TranscriptReader::meta,
    ) : this(folders, { projectPath }, meta)

    /** What [list] knows about a transcript, its [meta] and the row made of it, and the size and modification time it knew it at. */
    private class Entry(val size: Long, val modified: FileTime, val meta: TranscriptMeta, val summary: SessionSummary)

    private val cache = ConcurrentHashMap<Path, Entry>()

    /** The size and modification time a transcript had when it could not be read: it is not tried again until they change. */
    private data class Stamp(val size: Long, val modified: FileTime)

    private val unreadable = ConcurrentHashMap<Path, Stamp>()

    /** Counts [invalidate]s, so that a listing that read a transcript meanwhile does not cache what it read ([entryOf]). */
    private val generation = AtomicLong()

    /** The project's path, read from the constructor's function on first use. */
    private val projectPath: String by lazy(projectPath)

    /** Whether other projects' sessions can be in the folders: the key is cut at the CLI's limit, see [TranscriptFolder.candidates]. */
    private val sharesFolders: Boolean by lazy { TranscriptFolder.key(this.projectPath).length >= TranscriptFolder.MAX_KEY_LENGTH }

    /**
     * Every session in the folders, newest first, ties by id. A folder that does not exist has none: a project's first session
     * creates it.
     *
     * A project path whose folder key reaches the CLI's limit ([TranscriptFolder.MAX_KEY_LENGTH] characters) shares its folders with
     * every other project whose key starts the same (see [TranscriptFolder.candidates]), even when only one such folder exists. There
     * a session is dropped if its transcript names another working directory than [projectPath] (another string that the file system
     * does not resolve to the same directory); one that names none stays. With a shorter key nothing is dropped: the folder is the
     * one the CLI keeps for this project, and for any other path that gives the same key (every character outside `A–Z a–z 0–9`
     * becomes `-`, so `/a/b-c` and `/a/b/c` share one).
     */
    fun list(): List<SessionSummary> {
        val candidates: List<Path> = tryFileSystem(
            onFailure = { e ->
                LOG.warn("transcript folders not known (${e.javaClass.simpleName})")
                emptyList()
            },
        ) { folders() }
        val seen = HashSet<Path>()
        val listed = HashSet<Path>()
        val found = ArrayList<Entry>()
        for (folder in candidates) {
            for (file in transcriptsIn(folder)) {
                listed.add(file) // `add`, not `+=`: a Path is an Iterable<Path>, so `+=` would add its name elements instead
                val entry = entryOf(file) ?: continue
                seen.add(file)
                found.add(entry)
            }
        }
        cache.keys.retainAll(seen) // transcripts that are gone
        unreadable.keys.retainAll(listed)
        val own = if (sharesFolders) {
            val verdicts = HashMap<String, Boolean>() // transcripts of one other project name the same cwd
            found.filter { entry -> entry.meta.cwd.let { it == null || verdicts.getOrPut(it) { isProjectPath(it) } } }
        } else {
            found
        }
        return own.map { it.summary }.sortedWith(NEWEST_FIRST)
    }

    /**
     * Whether [cwd], a transcript's working directory, is [projectPath]: the same string, or the same directory as the file system
     * says, such as a path that differs in case only on a case-insensitive volume (macOS by default). One that cannot be looked up
     * (it was deleted, say) is not.
     */
    private fun isProjectPath(cwd: String): Boolean =
        cwd == projectPath || tryFileSystem(onFailure = { false }) { Files.isSameFile(Path.of(cwd), Path.of(projectPath)) }

    /** Forgets session [id], so that the next [list] reads its transcript again, even one a [list] in flight is reading now. */
    fun invalidate(id: String) {
        generation.incrementAndGet()
        cache.values.removeIf { it.summary.id == id }
        val file = id + TRANSCRIPT_SUFFIX
        unreadable.keys.removeIf { it.name == file }
    }

    /**
     * Session [id]'s transcript in the folders, or null while there is none: the CLI writes it with the session's first prompt,
     * not when it starts. Only looks whether `<id>.jsonl` exists, so it reads nothing and needs no cache; it still touches the
     * file system, so call it off the EDT. An [id] that is not a session id is never looked up, and a failure of the file system
     * counts as no transcript (and goes to the log).
     */
    fun transcript(id: String): Path? {
        val name = id + TRANSCRIPT_SUFFIX
        if (!TRANSCRIPT_NAME.matches(name)) return null
        return tryFileSystem(
            onFailure = { e ->
                LOG.warn("transcript $id not looked up (${e.javaClass.simpleName})")
                null
            },
        ) { folders().map { it.resolve(name) }.firstOrNull { Files.isRegularFile(it) } }
    }

    /**
     * A signal whenever something happens in the folders: a transcript is created, written to or deleted. Call [list] after one.
     *
     * The first signal comes as soon as the watching has started, whatever the folders hold, so a caller that lists first and
     * subscribes after misses nothing created in between. From then on every folder that exists is watched with a [WatchService];
     * the others are looked for every 2 s, since a project's first session creates its folder, and signalled once found because they
     * may hold transcripts already. A burst of events is one signal, sent when the folders have been quiet for 500 ms.
     *
     * Collecting runs on [Dispatchers.IO] and holds the watch service until it is cancelled. Where the file system cannot be
     * watched, or fails, the flow logs that and ends without a signal, and sessions then show up on the next [list] only.
     */
    @OptIn(FlowPreview::class)
    fun changes(): Flow<Unit> = watchFolders(folders).debounce(CHANGE_DEBOUNCE_MILLIS)

    /** The folder's transcripts; none for a folder that is missing or cannot be listed. */
    private fun transcriptsIn(folder: Path): List<Path> =
        tryFileSystem(
            onFailure = { e ->
                LOG.warn("transcript folder not listed (${e.javaClass.simpleName})")
                emptyList()
            },
        ) {
            try {
                Files.list(folder).use { entries -> entries.filter { TRANSCRIPT_NAME.matches(it.name) }.toList() }
            } catch (_: NoSuchFileException) {
                emptyList() // no session was ever started in this project
            }
        }

    /**
     * What is known about [file]: from the cache if it has not changed, else read afresh, from where it was read before if it only
     * grew. Null if it is no longer a readable file. A transcript that cannot be read is logged once and not read again until its
     * size or modification time changes, or [invalidate] asks for it.
     */
    private fun entryOf(file: Path): Entry? {
        val known = generation.get()
        return tryFileSystem(
            onFailure = { e ->
                LOG.warn("transcript ${file.name} not listed (${e.javaClass.simpleName})")
                null
            },
        ) {
            try {
                val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
                val stamp = Stamp(attributes.size(), attributes.lastModifiedTime())
                val cached = cache[file]
                when {
                    !attributes.isRegularFile -> null
                    cached != null && cached.size == stamp.size && cached.modified == stamp.modified -> cached
                    unreadable[file] == stamp -> null
                    else -> {
                        // Size and time are taken before the read: if the CLI appends meanwhile, the next listing sees a change.
                        val transcript = tryFileSystem(
                            onFailure = { e ->
                                LOG.warn("transcript ${file.name} not listed (${e.javaClass.simpleName}); tried again once it changes")
                                unreadable[file] = stamp
                                return null
                            },
                        ) { cached?.let { grown(file, it, stamp.size) } ?: meta(file) }
                        unreadable.remove(file)
                        val summary = SessionSummary(
                            file.name.removeSuffix(TRANSCRIPT_SUFFIX), transcript.title, stamp.modified.toMillis(), transcript.firstPrompt, file,
                        )
                        Entry(stamp.size, stamp.modified, transcript, summary).also { entry ->
                            cache[file] = entry
                            // An invalidate since this read began: what was read may be out of date, so the next listing reads again.
                            if (generation.get() != known) cache.remove(file, entry)
                        }
                    }
                }
            } catch (_: NoSuchFileException) {
                null // deleted since the folder was listed
            }
        }
    }

    /**
     * [cached]'s meta with the titles of the lines appended to [file] since, now that it is [size] bytes long; null when only a full
     * read can tell. That is when the file did not grow (it may have been written anew), when [cached] has no first prompt (it was
     * read before the prompt's line was written, and the prompt and the working directory may be in the new part), when the part read
     * before did not end a line, and when the file cannot be read now.
     */
    private fun grown(file: Path, cached: Entry, size: Long): TranscriptMeta? {
        if (size <= cached.size || cached.meta.firstPrompt == null) return null
        return try {
            TranscriptReader.withAppendedTitles(file, cached.size, cached.meta)
        } catch (e: IOException) {
            LOG.debug { "transcript ${file.name} not scanned from where it was read (${e.javaClass.simpleName})" }
            null
        }
    }
}

/**
 * One signal once the watching has started, then one per batch of events in the [folders], watched with the service that
 * [newWatchService] creates, each registered with the [modifiers] (see [watchModifiers]); the flow behind [SessionIndex.changes],
 * without its debounce. A folder that does not exist, or that was deleted, is looked for every [pollMillis] and signalled when it is
 * found. Internal so tests can pass a watch service that fails, a shorter poll and a modifier the watch service rejects.
 */
internal fun watchFolders(
    folders: () -> List<Path>,
    newWatchService: () -> WatchService = { FileSystems.getDefault().newWatchService() },
    pollMillis: Long = FOLDER_POLL_MILLIS,
    modifiers: List<WatchEvent.Modifier> = watchModifiers(),
): Flow<Unit> = callbackFlow<Unit> {
    val service = tryWatching(onFailure = { e ->
        logGivingUp(e)
        null
    }) { newWatchService() }
    if (service == null) {
        close()
        return@callbackFlow
    }
    launch {
        try {
            tryWatching(onFailure = ::logGivingUp) {
                val watch = FolderWatch(service, folders, modifiers)
                watch.registerNew()
                trySend(Unit) // Watched from here on: this makes a caller that listed earlier look again.
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
            }
        } catch (_: ClosedWatchServiceException) {
            // awaitClose closed the service under a blocked poll: the collector is gone.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
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

/**
 * The JDK's `com.sun.nio.file.SensitivityWatchEventModifier.HIGH`, looked up by name so that nothing depends on it at compile time,
 * or null where the JDK has none (it is deprecated for removal).
 */
private val HIGH_SENSITIVITY: WatchEvent.Modifier? by lazy {
    try {
        Class.forName("com.sun.nio.file.SensitivityWatchEventModifier").getField("HIGH").get(null) as? WatchEvent.Modifier
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    } catch (_: SecurityException) {
        null
    }
}

/**
 * The modifiers each folder is registered with: the highest sensitivity where the JDK has it. A watch service that polls, as the
 * JDK's on macOS does, then looks every 2 s instead of every 10 s; the others accept it and ignore it.
 */
internal fun watchModifiers(): List<WatchEvent.Modifier> = listOfNotNull(HIGH_SENSITIVITY)

private fun logGivingUp(e: Exception) {
    LOG.info("transcript folders cannot be watched (${e.javaClass.simpleName}); sessions show up when the list is refreshed")
}

/** The events a folder is watched for. */
private val KINDS: Array<WatchEvent.Kind<Path>> = arrayOf(ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)

/** The folders registered with a watch service, with the [modifiers] it accepts. Not thread-safe: one coroutine drives it. */
private class FolderWatch(
    private val service: WatchService,
    private val folders: () -> List<Path>,
    private val modifiers: List<WatchEvent.Modifier>,
) {
    private val watched = HashMap<Path, WatchKey>()

    /** Registers each folder that exists and is not watched yet. True if there was one. */
    fun registerNew(): Boolean {
        watched.values.removeIf { !it.isValid } // a deleted folder's watch is dead; the folder may be created again
        var registered = false
        for (folder in folders()) {
            if (folder in watched || !Files.isDirectory(folder)) continue
            watched[folder] = try {
                register(folder)
            } catch (_: NoSuchFileException) {
                continue // deleted again since it was found; the next round looks again
            }
            registered = true
        }
        return registered
    }

    /** Registers [folder] with the [modifiers], or without them if the watch service rejects one. */
    private fun register(folder: Path): WatchKey {
        if (modifiers.isNotEmpty()) {
            try {
                return folder.register(service, KINDS, *modifiers.toTypedArray())
            } catch (_: UnsupportedOperationException) {
                // Not every watch service knows a JDK's modifier: it watches as well without.
            }
        }
        return folder.register(service, *KINDS)
    }

    /** Waits up to [nanos] for the next batch of events in a watched folder. True if the batch holds events. */
    fun awaitEvents(nanos: Long): Boolean {
        val key = service.poll(nanos, TimeUnit.NANOSECONDS) ?: return false
        val events = key.pollEvents()
        key.reset() // false for a deleted folder, whose dead key registerNew drops
        return events.isNotEmpty()
    }
}
