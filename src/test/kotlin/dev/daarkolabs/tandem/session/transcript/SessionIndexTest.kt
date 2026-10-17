package dev.daarkolabs.tandem.session.transcript

import dev.daarkolabs.tandem.protocol.Fixtures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
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
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionIndexTest {
    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"
    private val third = "33333333-3333-4333-8333-333333333333"

    // 250 characters: its folder key is cut at the CLI's limit of 200, so other projects' keys can start the same.
    private val longProject = "/" + "a".repeat(250)

    private fun write(dir: Path, name: String, mtime: Long, text: String = "{}\n"): Path =
        dir.resolve(name).also {
            it.writeText(text)
            Files.setLastModifiedTime(it, FileTime.fromMillis(mtime))
        }

    private fun newTranscript(dir: Path): Path = write(dir, "${UUID.randomUUID()}.jsonl", mtime = 1_000)

    /** What a meta reader that never opens the file says about it; [cwd] is where the session was started. */
    private fun stubMeta(file: Path, cwd: String? = null): TranscriptMeta =
        TranscriptMeta(file.name.removeSuffix(".jsonl"), null, null, null, null, null, null, cwd, 0)

    /** [TranscriptReader.meta], counted in [reads]: each call reads the whole file. */
    private fun countingMeta(reads: AtomicInteger): (Path) -> TranscriptMeta = { file ->
        reads.incrementAndGet()
        TranscriptReader.meta(file)
    }

    /** The recorded `history-reply` transcript, titled "Pong" by its `ai-title` line, as session [id]'s. */
    private fun seedReply(dir: Path, id: String): Path =
        dir.resolve("$id.jsonl").also { Files.copy(Fixtures.transcript("history-reply"), it) }

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
        // The file grows. Its modification time is put back, so the size alone says that it changed. (Read in full: it has no
        // prompt, see `a transcript read before its first prompt was written is read in full when it grows`.)
        file.appendText("b\n")
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000))
        index.list()
        assertEquals(2, reads.get())
    }

    @Test
    fun `a transcript that only grew is scanned from where it was read, for its titles`(@TempDir dir: Path) {
        val file = seedReply(dir, first)
        val reads = AtomicInteger()
        val index = SessionIndex({ listOf(dir) }, "/p", meta = countingMeta(reads))
        assertEquals("Pong", index.list().single().title)
        assertEquals(1, reads.get())

        file.appendText("""{"type":"custom-title","customTitle":"Renamed","sessionId":"$first"}""" + "\n")
        val renamed = index.list().single()
        assertEquals("Renamed", renamed.title)
        assertEquals("Reply with the single word: pong", renamed.firstPrompt, "kept from the full read")
        assertEquals(Files.getLastModifiedTime(file).toMillis(), renamed.lastModified)
        // A generated title after it does not beat it, as in a full read, and a turn's lines in between change nothing.
        file.appendText(
            """{"type":"user","uuid":"u9","parentUuid":null,"message":{"role":"user","content":"more"}}""" + "\n" +
                """{"type":"ai-title","aiTitle":"Generated later","sessionId":"$first"}""" + "\n",
        )
        assertEquals("Renamed", index.list().single().title)
        assertEquals(1, reads.get(), "never read in full again")
        assertEquals(TranscriptReader.meta(file).title, index.list().single().title, "what a full read makes of it")
    }

    @Test
    fun `a transcript that shrank, or was invalidated, is read in full again`(@TempDir dir: Path) {
        val file = seedReply(dir, first)
        val reads = AtomicInteger()
        val index = SessionIndex({ listOf(dir) }, "/p", meta = countingMeta(reads))
        index.list()
        // Written anew and shorter: what was read before is not there any more.
        val shorter = Files.readAllLines(file).take(5) + """{"type":"custom-title","customTitle":"Shorter","sessionId":"$first"}"""
        file.writeText(shorter.joinToString("\n", postfix = "\n"))
        assertEquals("Shorter", index.list().single().title)
        assertEquals(2, reads.get())
        // After invalidate, the next listing reads it in full although it only grew.
        index.invalidate(first)
        file.appendText("""{"type":"custom-title","customTitle":"Renamed","sessionId":"$first"}""" + "\n")
        assertEquals("Renamed", index.list().single().title)
        assertEquals(3, reads.get())
    }

    @Test
    fun `a transcript read while its last line was being written is read in full once it grows`(@TempDir dir: Path) {
        val file = seedReply(dir, first)
        file.appendText("""{"type":"custom-title","customTitle":"Half""") // the CLI is still writing this line
        val reads = AtomicInteger()
        val index = SessionIndex({ listOf(dir) }, "/p", meta = countingMeta(reads))
        assertEquals("Pong", index.list().single().title, "a line that is not whole yet is skipped")
        file.appendText(""" way","sessionId":"$first"}""" + "\n")
        assertEquals("Half way", index.list().single().title)
        assertEquals(2, reads.get(), "read in full: what was appended does not start a line")
    }

    @Test
    fun `a transcript read before its first prompt was written is read in full when it grows`(@TempDir dir: Path) {
        val file = dir.resolve("$first.jsonl")
        file.writeText("""{"type":"queue-operation","operation":"enqueue","sessionId":"$first"}""" + "\n")
        val reads = AtomicInteger()
        val index = SessionIndex({ listOf(dir) }, "/p", meta = countingMeta(reads))
        assertEquals(first, index.list().single().title, "nothing to title it with yet")
        // Its first prompt, and the working directory the cwd filter needs, come with the lines appended now.
        val prompt = """{"type":"user","uuid":"u1","parentUuid":null,"cwd":"/p","message":{"role":"user","content":"Hello there"}}"""
        file.appendText(prompt + "\n")
        val listed = index.list().single()
        assertEquals("Hello there", listed.title)
        assertEquals("Hello there", listed.firstPrompt)
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
    fun `an invalidate while a listing reads that transcript still counts for the next listing`(@TempDir dir: Path) {
        write(dir, "$first.jsonl", mtime = 1_000)
        val reading = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        val reads = AtomicInteger()
        val index = SessionIndex({ listOf(dir) }, "/p", meta = {
            if (reads.incrementAndGet() == 1) {
                reading.countDown()
                invalidated.await(10, TimeUnit.SECONDS)
            }
            stubMeta(it)
        })
        val listing = thread { index.list() }
        assertTrue(reading.await(10, TimeUnit.SECONDS))
        index.invalidate(first) // as a rename does once it wrote the title: what the listing in flight read is out of date
        invalidated.countDown()
        listing.join(10_000)
        index.list()
        assertEquals(2, reads.get(), "the listing after the invalidate reads the transcript again")
    }

    @Test
    fun `a transcript deleted while it is listed is left out quietly and read again if it comes back`(@TempDir dir: Path) {
        write(dir, "$first.jsonl", mtime = 1_000)
        val reads = AtomicInteger()
        // Deleted between the look at its size and the read (the real reader is pointed at a path that is gone), then written
        // again, as it was.
        val index = SessionIndex({ listOf(dir) }, "/p", meta = {
            TranscriptReader.meta(if (reads.incrementAndGet() == 1) dir.resolve("gone").resolve(it.name) else it)
        })
        assertEquals(emptyList(), index.list())
        assertEquals(listOf(first), index.list().map { it.id })
    }

    @Test
    fun `the real reader lists a transcript it cannot open by its id and does not read it again until it changes`(@TempDir dir: Path) {
        val file = write(dir, "$first.jsonl", mtime = 1_000)
        val reads = AtomicInteger()
        // As root, a test cannot make a file unreadable: the reader opens a path below a plain file in its place.
        val blocker = write(dir, "not-a-folder", mtime = 1_000)
        val index = SessionIndex({ listOf(dir) }, "/p", meta = {
            reads.incrementAndGet()
            TranscriptReader.meta(blocker.resolve(it.name))
        })
        assertEquals(listOf(first to first), index.list().map { it.id to it.title })
        index.list()
        assertEquals(1, reads.get())
        Files.setLastModifiedTime(file, FileTime.fromMillis(3_000))
        index.list()
        assertEquals(2, reads.get())
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
    fun `transcript finds a session's file in whichever folder holds it`(@TempDir root: Path) {
        val one = root.resolve("one").createDirectories()
        val two = root.resolve("two").createDirectories()
        val file = write(two, "$first.jsonl", mtime = 1_000)
        two.resolve("$second.jsonl").createDirectory() // a directory with a transcript's name is no transcript
        val index = SessionIndex({ listOf(one, root.resolve("missing"), two) }, "/p")
        assertEquals(file, index.transcript(first))
        assertNull(index.transcript(second))
        assertNull(index.transcript(third)) // not written yet: the CLI writes it with the first prompt
        // Only a session id is looked up, so an id can never name a file outside the folders.
        assertNull(index.transcript("../two/$first"))
        assertNull(SessionIndex({ throw InvalidPathException("bad", "a config path that cannot be parsed") }, "/p").transcript(first))
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
        // Something that is not a folder, and a folder lookup that fails (here on a config directory that is no valid path).
        assertEquals(emptyList(), SessionIndex({ listOf(dir.resolve("$first.jsonl")) }, "/p").list())
        assertEquals(emptyList(), SessionIndex({ throw InvalidPathException("", "no config dir") }, "/p").list())
    }

    @Test
    fun `only the file system's failures are absorbed, a cancellation or a bug passes through`(@TempDir dir: Path) {
        write(dir, "$first.jsonl", mtime = 1_000)
        for (failure in listOf(CancellationException("cancelled"), IllegalStateException("bug"))) {
            val index = SessionIndex({ listOf(dir) }, "/p", meta = { throw failure })
            assertSame(failure, assertFailsWith<RuntimeException> { index.list() })
        }
    }

    @Test
    fun `with several candidate folders, sessions of another project are dropped`(@TempDir root: Path) {
        val a = root.resolve("a").createDirectories()
        val b = root.resolve("b").createDirectories()
        write(a, "$first.jsonl", mtime = 3_000)
        write(b, "$second.jsonl", mtime = 2_000)
        write(b, "$third.jsonl", mtime = 1_000)
        val cwds = mapOf(first to longProject, second to "/other", third to null)
        val meta = { file: Path -> stubMeta(file, cwds.getValue(file.name.removeSuffix(".jsonl"))) }
        // The session of another project goes; one whose cwd is unknown stays.
        assertEquals(listOf(first, third), SessionIndex({ listOf(a, b) }, longProject, meta).list().map { it.id })
    }

    @Test
    fun `with a long project path, a cwd spelt with a trailing slash or dot segments is ours though it does not exist`(@TempDir dir: Path) {
        write(dir, "$first.jsonl", mtime = 3_000)
        write(dir, "$second.jsonl", mtime = 2_000)
        write(dir, "$third.jsonl", mtime = 1_000)
        // longProject does not exist here, so the file system cannot say these are the same directory.
        val cwds = mapOf(first to "$longProject/", second to "/x/..$longProject/./", third to "$longProject-other")
        val meta = { file: Path -> stubMeta(file, cwds.getValue(file.name.removeSuffix(".jsonl"))) }
        assertEquals(listOf(first, second), SessionIndex({ listOf(dir) }, longProject, meta).list().map { it.id })
    }

    @Test
    fun `with a long project path, the only folder of a sibling project does not make its sessions ours`(@TempDir root: Path) {
        // This project has no folder yet. The one directory that shares its key prefix is another project's.
        val sibling = root.resolve("sibling").createDirectories()
        write(sibling, "$first.jsonl", mtime = 1_000)
        val index = SessionIndex({ listOf(sibling) }, longProject, meta = { stubMeta(it, cwd = "/other") })
        assertEquals(emptyList(), index.list())
    }

    @Test
    fun `with a long project path, a session whose cwd is the project by another name is ours`(@TempDir root: Path) {
        // A real directory whose key reaches the CLI's limit, and a link to it: the file system says both name one directory, as a
        // case-insensitive volume (macOS) does for a path that differs in case only.
        val project = root.resolve("a".repeat(120)).resolve("b".repeat(120)).createDirectories()
        val link = Files.createSymbolicLink(root.resolve("link"), project)
        val elsewhere = root.resolve("elsewhere").createDirectories()
        val folder = root.resolve("folder").createDirectories()
        write(folder, "$first.jsonl", mtime = 4_000)
        write(folder, "$second.jsonl", mtime = 3_000)
        write(folder, "$third.jsonl", mtime = 2_000)
        val gone = "44444444-4444-4444-8444-444444444444"
        write(folder, "$gone.jsonl", mtime = 1_000)
        val cwds = mapOf(first to project.toString(), second to link.toString(), third to elsewhere.toString(), gone to "$root/gone")
        val meta = { file: Path -> stubMeta(file, cwds.getValue(file.name.removeSuffix(".jsonl"))) }
        val index = SessionIndex({ listOf(folder) }, project.toString(), meta)
        // Another existing directory, or one that cannot be looked up, is another project's.
        assertEquals(listOf(first, second), index.list().map { it.id })
    }

    @Test
    fun `with a long project path on a case-insensitive volume, a cwd that differs in case only is ours`(@TempDir root: Path) {
        val project = root.resolve("Code".repeat(30)).resolve("App".repeat(40)).createDirectories()
        assumeTrue(Files.exists(Path.of(project.toString().lowercase())), "needs a case-insensitive volume, as macOS has by default")
        val folder = root.resolve("folder").createDirectories()
        write(folder, "$first.jsonl", mtime = 1_000)
        val index = SessionIndex({ listOf(folder) }, project.toString(), meta = { stubMeta(it, cwd = project.toString().lowercase()) })
        assertEquals(listOf(first), index.list().map { it.id })
    }

    @Test
    fun `with a shorter key nothing is dropped, however many folders there are`(@TempDir root: Path) {
        val a = root.resolve("a").createDirectories()
        val b = root.resolve("b").createDirectories()
        write(a, "$first.jsonl", mtime = 2_000)
        write(b, "$second.jsonl", mtime = 1_000)
        // The sessions name another working directory, but a short key names the project's own folder.
        val foreign = { file: Path -> stubMeta(file, cwd = "/other") }
        assertEquals(listOf(first, second), SessionIndex({ listOf(a, b) }, "/p", foreign).list().map { it.id })
        assertEquals(listOf(second), SessionIndex({ listOf(b) }, "/p", foreign).list().map { it.id })
    }

    @Test
    fun `the project path is read when the folders are listed, not when the index is made`(@TempDir dir: Path) {
        // A path the file system has to resolve can be passed as a function, so that an index can be made on the EDT.
        write(dir, "$first.jsonl", mtime = 2_000)
        write(dir, "$second.jsonl", mtime = 1_000)
        val cwds = mapOf(first to longProject, second to "/other")
        val reads = AtomicInteger()
        val index = SessionIndex(
            { listOf(dir) },
            {
                reads.incrementAndGet()
                longProject
            },
            meta = { stubMeta(it, cwds.getValue(it.name.removeSuffix(".jsonl"))) },
        )
        assertEquals(0, reads.get())
        assertEquals(listOf(first), index.list().map { it.id }, "the cwd filter compares with the path read")
        index.list()
        assertEquals(1, reads.get(), "read once")
    }

    @Test
    fun `the cwd filter starts where the CLI cuts the key`(@TempDir dir: Path) {
        write(dir, "$first.jsonl", mtime = 1_000)
        val foreign = { file: Path -> stubMeta(file, cwd = "/other") }
        // "/" and 198 letters make a key of 199 characters, which the CLI keeps whole. One letter more reaches the limit.
        assertEquals(listOf(first), SessionIndex({ listOf(dir) }, "/" + "a".repeat(198), foreign).list().map { it.id })
        assertEquals(emptyList(), SessionIndex({ listOf(dir) }, "/" + "a".repeat(199), foreign).list())
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
    fun `changes signals once as soon as the watching has started`(@TempDir dir: Path) = runBlocking<Unit> {
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val collecting = launch { SessionIndex({ listOf(dir) }, "/p").changes().collect { signals.send(it) } }
        try {
            withTimeout(15_000) { signals.receive() }
            delay(1_500)
            assertTrue(signals.tryReceive().isFailure, "a second signal although nothing was written")
        } finally {
            collecting.cancelAndJoin()
        }
    }

    @Test
    fun `changes emits after a file is written`(@TempDir dir: Path) = runBlocking<Unit> {
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val collecting = launch { SessionIndex({ listOf(dir) }, "/p").changes().collect { signals.send(it) } }
        try {
            // The first signal says that the watching has started, so a file written from now on is reported. (A polling watch
            // service, as on macOS, takes up to 10 s to notice it.)
            withTimeout(15_000) { signals.receive() }
            newTranscript(dir)
            withTimeout(15_000) { signals.receive() }
        } finally {
            collecting.cancelAndJoin()
        }
    }

    @Test
    fun `the watcher asks the JDK for its highest polling sensitivity`() {
        // The tests run on the IDE's runtime (JBR), which has com.sun.nio.file.SensitivityWatchEventModifier as of JBR 25; it is
        // deprecated for removal. With it, a polling watch service (macOS) looks every 2 s instead of every 10 s; the others accept
        // it and ignore it. A runtime without it registers no modifier, which the other watch tests then exercise.
        assumeTrue(
            runCatching { Class.forName("com.sun.nio.file.SensitivityWatchEventModifier") }.isSuccess,
            "this runtime has no SensitivityWatchEventModifier",
        )
        assertEquals(listOf("HIGH"), watchModifiers().map { it.name() })
    }

    @Test
    fun `a modifier the watch service rejects is dropped and the folder is watched without it`(@TempDir dir: Path) = runBlocking<Unit> {
        val unknown = WatchEvent.Modifier { "UNKNOWN" }
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val collecting = launch { watchFolders({ listOf(dir) }, modifiers = listOf(unknown)).collect { signals.send(it) } }
        try {
            withTimeout(15_000) { signals.receive() } // the folder is watched
            newTranscript(dir)
            withTimeout(15_000) { signals.receive() }
        } finally {
            collecting.cancelAndJoin()
        }
    }

    @Test
    fun `a transcript written into a watched folder is signalled within a few seconds`(@TempDir dir: Path) = runBlocking<Unit> {
        // Immediate where the file system notifies (Linux, Windows). Where the watch service polls (macOS), only the high
        // sensitivity keeps this within the bound: the JDK's default is to look every 10 s.
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val collecting = launch { watchFolders({ listOf(dir) }).collect { signals.send(it) } }
        try {
            withTimeout(15_000) { signals.receive() } // the folder is watched
            newTranscript(dir)
            withTimeout(6_000) { signals.receive() }
        } finally {
            collecting.cancelAndJoin()
        }
    }

    @Test
    fun `a burst of writes is reported once`(@TempDir dir: Path) = runBlocking<Unit> {
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val collecting = launch { SessionIndex({ listOf(dir) }, "/p").changes().collect { signals.send(it) } }
        try {
            withTimeout(15_000) { signals.receive() } // the watching has started
            repeat(10) { newTranscript(dir) }
            withTimeout(15_000) { signals.receive() } // the burst is reported
            delay(1_500)
            var more = 0
            while (signals.tryReceive().isSuccess) more++
            // Not once more for each write. One more allows for the test machine pausing in the middle of the burst.
            assertTrue(more <= 1, "$more signals after the first, for one burst of writes")
        } finally {
            collecting.cancelAndJoin()
        }
    }

    @Test
    fun `a folder that does not exist yet is watched once it appears`(@TempDir root: Path) = runBlocking<Unit> {
        val folder = root.resolve("projects").resolve("-p")
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val collecting = launch { watchFolders({ listOf(folder) }, pollMillis = 100).collect { signals.send(it) } }
        try {
            withTimeout(15_000) {
                signals.receive() // the watching has started, with the folder missing
                folder.createDirectories()
                // Written before the watcher can register the folder, so no event will tell of it: finding the folder must.
                newTranscript(folder)
                signals.receive()
            }
        } finally {
            collecting.cancelAndJoin()
        }
    }

    @Test
    fun `a folder that is deleted and created again is watched again`(@TempDir root: Path) {
        // A polling watch service (macOS) cancels the key of a vanished folder without any event, so there is nothing to wait for.
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"), "needs a watch service that reports deletions")
        runBlocking<Unit> {
            val folder = root.resolve("-p").createDirectories()
            newTranscript(folder)
            val checks = AtomicInteger()
            val signals = Channel<Unit>(Channel.UNLIMITED)
            val collecting = launch {
                watchFolders({ checks.incrementAndGet(); listOf(folder) }, pollMillis = 100).collect { signals.send(it) }
            }
            try {
                withTimeout(15_000) {
                    signals.receive() // the folder is registered
                    folder.toFile().deleteRecursively()
                    signals.receive() // the deletion is reported
                    val seen = checks.get()
                    while (checks.get() < seen + 3) delay(20) // and the watcher has dropped the dead watch
                    while (signals.tryReceive().isSuccess) continue
                    folder.createDirectories()
                    newTranscript(folder)
                    signals.receive() // the folder is watched again
                }
            } finally {
                collecting.cancelAndJoin()
            }
        }
    }

    @Test
    fun `a file system that cannot watch gives a flow that ends without a signal`(@TempDir dir: Path) = runBlocking<Unit> {
        for (failure in listOf(UnsupportedOperationException(), IOException("too many watches"))) {
            assertEquals(emptyList(), withTimeout(15_000) { watchFolders({ listOf(dir) }, { throw failure }).toList() })
        }
    }

    @Test
    fun `a folder that cannot be registered, or looked up, ends the flow without a signal`(@TempDir dir: Path) = runBlocking<Unit> {
        // A folder of this file system cannot be registered with a watch service that no provider made: ProviderMismatchException.
        assertEquals(emptyList(), withTimeout(15_000) { watchFolders({ listOf(dir) }, { ForeignWatchService() }).toList() })
        assertEquals(emptyList(), withTimeout(15_000) { watchFolders({ throw InvalidPathException("", "no config dir") }).toList() })
    }

    @Test
    fun `the watch service is closed as soon as the collector stops`(@TempDir dir: Path) = runBlocking<Unit> {
        val created = CompletableDeferred<WatchService>()
        val newService = { FileSystems.getDefault().newWatchService().also { created.complete(it) } }
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val collecting = launch { watchFolders({ listOf(dir) }, newService, pollMillis = 60_000).collect { signals.send(it) } }
        val service = withTimeout(15_000) { created.await() }
        // The signal follows the first look at the folders. The watcher then waits in a poll that would last a minute, so only
        // closing the service can end it within the timeout below.
        withTimeout(15_000) { signals.receive() }
        withTimeout(10_000) { collecting.cancelAndJoin() }
        assertFailsWith<ClosedWatchServiceException> { service.poll() }
    }
}

/** A watch service that no file system provider made: a folder cannot be registered with it. */
private class ForeignWatchService : WatchService {
    override fun close() = Unit

    override fun poll(): WatchKey? = null

    override fun poll(timeout: Long, unit: TimeUnit): WatchKey? = null

    override fun take(): WatchKey = throw ClosedWatchServiceException()
}
