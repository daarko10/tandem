package dev.daarkolabs.tandem.session.transcript

import dev.daarkolabs.tandem.protocol.Fixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.WatchService
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionIndexTest {
    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"
    private val third = "33333333-3333-4333-8333-333333333333"

    private fun write(dir: Path, name: String, mtime: Long, text: String = "{}\n"): Path =
        dir.resolve(name).also {
            it.writeText(text)
            Files.setLastModifiedTime(it, FileTime.fromMillis(mtime))
        }

    private fun newTranscript(dir: Path): Path = write(dir, "${UUID.randomUUID()}.jsonl", mtime = 1_000)

    /** What a meta reader that never opens the file says about it; [cwd] is where the session was started. */
    private fun stubMeta(file: Path, cwd: String? = null): TranscriptMeta =
        TranscriptMeta(file.name.removeSuffix(".jsonl"), null, null, null, null, null, null, cwd, 0)

    @Test
    fun `lists only session transcripts, newest first`(@TempDir dir: Path) {
        write(dir, "11111111-1111-4111-8111-111111111111.jsonl", mtime = 1_000)
        write(dir, "22222222-2222-4222-8222-222222222222.jsonl", mtime = 2_000)
        write(dir, "agent-1234.jsonl", mtime = 3_000)
        write(dir, "notes.txt", mtime = 3_000)
        write(dir, "33333333-3333-4333-8333-333333333333.jsonl.tmp", mtime = 3_000)
        assertEquals(
            listOf("22222222-2222-4222-8222-222222222222", "11111111-1111-4111-8111-111111111111"),
            SessionIndex({ listOf(dir) }, "/p").list().map { it.id },
        )
    }

    @Test
    fun `names and entries that only look like transcripts are skipped`(@TempDir dir: Path) {
        write(dir, "$first.jsonl", mtime = 1_000)
        dir.resolve(second).createDirectory() // the directory beside a transcript, for tool results and subagents
        dir.resolve("$third.jsonl").createDirectory() // a directory with a transcript's name is no transcript
        write(dir, "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA.jsonl", mtime = 3_000) // the CLI writes lower-case ids
        write(dir, "1111111-1111-4111-8111-111111111111.jsonl", mtime = 3_000) // one digit short
        write(dir, "11111111-1111-4111-8111-11111111111g.jsonl", mtime = 3_000) // not hexadecimal
        assertEquals(listOf(first), SessionIndex({ listOf(dir) }, "/p").list().map { it.id })
    }

    @Test
    fun `files with the same modification time are ordered by id`(@TempDir dir: Path) {
        val ids = listOf(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
        )
        // Written out of order: the listing must not depend on the file system's order.
        listOf(ids[2], ids[0], ids[3], ids[1]).forEach { write(dir, "$it.jsonl", mtime = 1_000) }
        write(dir, "$first.jsonl", mtime = 2_000)
        assertEquals(listOf(first) + ids, SessionIndex({ listOf(dir) }, "/p").list().map { it.id })
    }

    @Test
    fun `a recorded transcript is listed with its title, first prompt and modification time`(@TempDir dir: Path) {
        val file = dir.resolve("$first.jsonl")
        Files.copy(Fixtures.transcript("history-reply"), file)
        Files.setLastModifiedTime(file, FileTime.fromMillis(5_000))
        assertEquals(
            // The CLI's own title ("ai-title") wins over the first prompt.
            listOf(SessionSummary(first, "Pong", 5_000, "Reply with the single word: pong", file)),
            SessionIndex({ listOf(dir) }, "/p").list(),
        )
    }

    @Test
    fun `unchanged files are not read again`(@TempDir dir: Path) {
        val file = write(dir, "$first.jsonl", mtime = 1_000, text = "a\n")
        val reads = AtomicInteger()
        val index = SessionIndex({ listOf(dir) }, "/p", meta = { reads.incrementAndGet(); stubMeta(it) })
        index.list()
        index.list()
        assertEquals(1, reads.get())
        // The file grows. Its modification time is put back, so the size alone says that it changed.
        file.appendText("b\n")
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000))
        index.list()
        assertEquals(2, reads.get())
    }

    @Test
    fun `a changed modification time alone also reads the file again`(@TempDir dir: Path) {
        write(dir, "$first.jsonl", mtime = 1_000, text = "a\n")
        val reads = AtomicInteger()
        val index = SessionIndex({ listOf(dir) }, "/p", meta = { reads.incrementAndGet(); stubMeta(it) })
        index.list()
        write(dir, "$first.jsonl", mtime = 2_000, text = "b\n") // the same size
        assertEquals(2_000, index.list().single().lastModified)
        assertEquals(2, reads.get())
    }

    @Test
    fun `invalidate makes the next listing read that transcript again`(@TempDir dir: Path) {
        write(dir, "$first.jsonl", mtime = 1_000)
        write(dir, "$second.jsonl", mtime = 2_000)
        val reads = ConcurrentLinkedQueue<String>()
        val index = SessionIndex({ listOf(dir) }, "/p", meta = { reads += it.name; stubMeta(it) })
        index.list()
        assertEquals(2, reads.size)
        index.invalidate(first)
        index.invalidate("not-a-session") // unknown ids are ignored
        index.list()
        assertEquals(listOf("$first.jsonl"), reads.drop(2))
    }

    @Test
    fun `a deleted transcript leaves the listing, and a new file under its name is read afresh`(@TempDir dir: Path) {
        val file = write(dir, "$first.jsonl", mtime = 1_000, text = "aaa\n")
        val reads = AtomicInteger()
        val index = SessionIndex({ listOf(dir) }, "/p", meta = { reads.incrementAndGet(); stubMeta(it) })
        assertEquals(listOf(first), index.list().map { it.id })
        file.deleteExisting()
        assertEquals(emptyList(), index.list())
        // The same name, size and modification time as the deleted file: a cache that kept its entry would answer from it.
        write(dir, "$first.jsonl", mtime = 1_000, text = "bbb\n")
        assertEquals(listOf(first), index.list().map { it.id })
        assertEquals(2, reads.get())
    }

    @Test
    fun `a transcript written after a listing shows on the next one`(@TempDir dir: Path) {
        write(dir, "$first.jsonl", mtime = 1_000)
        val index = SessionIndex({ listOf(dir) }, "/p")
        assertEquals(listOf(first), index.list().map { it.id })
        write(dir, "$second.jsonl", mtime = 2_000)
        assertEquals(listOf(second, first), index.list().map { it.id })
    }

    @Test
    fun `a missing folder is an empty list`(@TempDir dir: Path) {
        assertEquals(emptyList(), SessionIndex({ listOf(dir.resolve("missing")) }, "/p").list())
        assertEquals(emptyList(), SessionIndex({ emptyList() }, "/p").list())
    }

    @Test
    fun `a transcript that is gone by the time it is read is left out`(@TempDir dir: Path) {
        write(dir, "$first.jsonl", mtime = 1_000)
        // A link to nothing is in the folder but has no file behind it: what a transcript deleted just after the listing looks like.
        try {
            Files.createSymbolicLink(dir.resolve("$second.jsonl"), dir.resolve("nowhere"))
        } catch (_: Exception) {
            assumeTrue(false, "this file system has no symbolic links")
        }
        assertEquals(listOf(first), SessionIndex({ listOf(dir) }, "/p").list().map { it.id })
    }

    @Test
    fun `a listing that goes wrong gives what could be read and never throws`(@TempDir dir: Path) {
        write(dir, "$first.jsonl", mtime = 1_000)
        write(dir, "$second.jsonl", mtime = 2_000)
        // A transcript whose reader fails is left out; the others are listed.
        val failing = SessionIndex(
            { listOf(dir) },
            "/p",
            meta = { if (it.name.startsWith("1")) throw IOException("unreadable") else stubMeta(it) },
        )
        assertEquals(listOf(second), failing.list().map { it.id })
        // Something that is not a folder, and a folder lookup that fails.
        assertEquals(emptyList(), SessionIndex({ listOf(dir.resolve("$first.jsonl")) }, "/p").list())
        assertEquals(emptyList(), SessionIndex({ throw IllegalStateException("no config dir") }, "/p").list())
    }

    @Test
    fun `with several candidate folders, sessions of another project are dropped`(@TempDir root: Path) {
        val a = root.resolve("a").createDirectories()
        val b = root.resolve("b").createDirectories()
        write(a, "$first.jsonl", mtime = 3_000)
        write(b, "$second.jsonl", mtime = 2_000)
        write(b, "$third.jsonl", mtime = 1_000)
        val cwds = mapOf(first to "/p", second to "/other", third to null)
        val meta = { file: Path -> stubMeta(file, cwds.getValue(file.name.removeSuffix(".jsonl"))) }
        // Of two folders, the session of another project goes; one whose cwd is unknown stays.
        assertEquals(listOf(first, third), SessionIndex({ listOf(a, b) }, "/p", meta).list().map { it.id })
        // A single folder is the project's own, whatever the sessions say.
        assertEquals(listOf(second, third), SessionIndex({ listOf(b) }, "/p", meta).list().map { it.id })
    }

    @Test
    fun `listing and invalidating at the same time stay consistent`(@TempDir dir: Path) = runBlocking<Unit> {
        val ids = (1..20).map { "%08x-0000-4000-8000-000000000000".format(it) }
        ids.forEach { write(dir, "$it.jsonl", mtime = 1_000) }
        val index = SessionIndex({ listOf(dir) }, "/p", meta = { stubMeta(it) })
        coroutineScope {
            repeat(8) { worker ->
                launch(Dispatchers.Default) {
                    repeat(300) { round ->
                        if (worker % 2 == 0) assertEquals(ids.size, index.list().size) else index.invalidate(ids[round % ids.size])
                    }
                }
            }
        }
    }

    @Test
    fun `changes emits after a file is written`(@TempDir dir: Path) = runBlocking<Unit> {
        val changed = async { SessionIndex({ listOf(dir) }, "/p").changes().first() }
        try {
            withTimeout(15_000) {
                // The watch is registered a moment after collection starts, and a write before that goes unseen: keep writing
                // until one is noticed. The writes are further apart than the 500 ms debounce, so each can be reported.
                while (!changed.isCompleted) {
                    newTranscript(dir)
                    delay(700)
                }
                changed.await()
            }
        } finally {
            changed.cancelAndJoin()
        }
    }

    @Test
    fun `a burst of writes is reported once`(@TempDir dir: Path) = runBlocking<Unit> {
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val collecting = launch { SessionIndex({ listOf(dir) }, "/p").changes().collect { signals.send(it) } }
        try {
            withTimeout(15_000) {
                // Write until the first signal shows that the watch is live.
                val writing = launch {
                    while (isActive) {
                        newTranscript(dir)
                        delay(700)
                    }
                }
                signals.receive()
                writing.cancelAndJoin()
            }
            repeat(10) { newTranscript(dir) }
            delay(1_500)
            var count = 0
            while (signals.tryReceive().isSuccess) count++
            // Ten writes with no pause between them are one signal; two allow for a pause of the test machine in the middle.
            assertTrue(count in 1..2, "$count signals for one burst of writes")
        } finally {
            collecting.cancelAndJoin()
        }
    }

    @Test
    fun `a folder that does not exist yet is watched once it appears`(@TempDir root: Path) = runBlocking<Unit> {
        val folder = root.resolve("projects").resolve("-p")
        val checks = AtomicInteger()
        val signal = async { watchFolders({ checks.incrementAndGet(); listOf(folder) }, pollMillis = 100).first() }
        try {
            withTimeout(15_000) {
                // A second check means that the first one found the folder missing, and the watcher is polling for it.
                while (checks.get() < 2) delay(20)
                folder.createDirectories()
                // Written before the watcher can register the folder, so no event will tell of it: finding the folder must.
                newTranscript(folder)
                signal.await()
            }
        } finally {
            signal.cancelAndJoin()
        }
    }

    @Test
    fun `a folder that is deleted and created again is watched again`(@TempDir root: Path) = runBlocking<Unit> {
        val folder = root.resolve("-p").createDirectories()
        newTranscript(folder)
        val checks = AtomicInteger()
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val collecting = launch {
            watchFolders({ checks.incrementAndGet(); listOf(folder) }, pollMillis = 100).collect { signals.send(it) }
        }
        suspend fun awaitChecks(count: Int) {
            val until = checks.get() + count
            while (checks.get() < until) delay(20)
        }
        try {
            withTimeout(15_000) {
                awaitChecks(2) // the folder is registered
                folder.toFile().deleteRecursively()
                signals.receive() // the deletion is reported
                awaitChecks(3) // and the watcher has dropped the dead watch
                while (signals.tryReceive().isSuccess) continue
                folder.createDirectories()
                newTranscript(folder)
                signals.receive() // the folder is watched again
            }
        } finally {
            collecting.cancelAndJoin()
        }
    }

    @Test
    fun `a file system that cannot watch gives a flow that ends without a signal`(@TempDir dir: Path) = runBlocking<Unit> {
        for (failure in listOf(UnsupportedOperationException(), IOException("too many watches"))) {
            assertEquals(emptyList(), withTimeout(15_000) { watchFolders({ listOf(dir) }, { throw failure }).toList() })
        }
    }

    @Test
    fun `a folder that cannot be watched, or cannot be looked up, ends the flow without a signal`(@TempDir tmp: Path) = runBlocking<Unit> {
        // The paths of a zip file system refuse to register with a watch service.
        FileSystems.newFileSystem(tmp.resolve("empty.zip"), mapOf("create" to "true")).use { zip ->
            assertEquals(emptyList(), withTimeout(15_000) { watchFolders({ listOf(zip.getPath("/")) }).toList() })
        }
        assertEquals(emptyList(), withTimeout(15_000) { watchFolders({ throw IllegalStateException("no config dir") }).toList() })
    }

    @Test
    fun `the watch service is closed as soon as the collector stops`(@TempDir dir: Path) = runBlocking<Unit> {
        val created = CompletableDeferred<WatchService>()
        val newService = { FileSystems.getDefault().newWatchService().also { created.complete(it) } }
        val collecting = launch { watchFolders({ listOf(dir) }, newService, pollMillis = 60_000).collect {} }
        val service = withTimeout(15_000) { created.await() }
        // The watcher waits in a poll that would last a minute: only closing the service can end it within the timeout.
        withTimeout(10_000) { collecting.cancelAndJoin() }
        assertFailsWith<ClosedWatchServiceException> { service.poll() }
    }
}
