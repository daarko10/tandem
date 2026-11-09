package dev.daarkolabs.tandem.session.transcript

import dev.daarkolabs.tandem.chat.ChatRows
import dev.daarkolabs.tandem.chat.ChatRowsBuilder
import dev.daarkolabs.tandem.chat.RowOptions
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.Processes
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize

/**
 * The "open a long conversation" benchmark's input, read as the sidebar and the chat read it: `scripts/bench/make_long_session.py`
 * writes a 400-turn transcript, [SessionIndex] lists it, [TranscriptReader] loads it and [ChatRowsBuilder] builds its rows.
 * Prints the wall time of each step; fails only if one takes more than 30 s (CI machines vary too much for a tighter bound).
 */
class LongSessionTest {
    @Test
    fun `a 400-turn session lists, loads and builds its rows`(@TempDir dir: Path): Unit = runBlocking {
        val project: Path = dir.resolve("long project").createDirectories().toRealPath()
        val config: Path = dir.resolve("config")
        val generator = ProcessBuilder(
            Processes.python + listOf(
                "-I", "scripts/bench/make_long_session.py",
                "--turns", "$TURNS", "--project", project.toString(), "--config", config.toString(),
            ),
        ).redirectErrorStream(true).start()
        val output: String = generator.inputStream.bufferedReader().readText()
        assertTrue(generator.waitFor(2, TimeUnit.MINUTES), "the generator did not finish")
        assertEquals(0, generator.exitValue(), output)
        val file: Path = Path.of(output.lines().first())

        val folders: List<Path> = TranscriptFolder.candidates(TranscriptFolder.projectsDir(config), project.toString())
        val listStart: Long = System.nanoTime()
        val sessions: List<SessionSummary> = SessionIndex({ folders }, project.toString()).list()
        val listMillis: Long = elapsedMillis(listStart)
        assertEquals(listOf(file), sessions.map { it.file })
        assertEquals("Orders module review", sessions.single().title)

        val readStart: Long = System.nanoTime()
        val loaded: LoadedTranscript = TranscriptReader.read(file)
        val readMillis: Long = elapsedMillis(readStart)
        assertNull(loaded.readError)
        val prompts: List<ChatEntry.User> = loaded.state.entries.filterIsInstance<ChatEntry.User>()
        assertEquals(TURNS, prompts.size)
        assertTrue(prompts.first().text.startsWith("Let us start with src/app/Orders.kt"))

        val buildStart: Long = System.nanoTime()
        val rows: ChatRows = ChatRowsBuilder(RowOptions(showThinking = true)).build(loaded.state)
        val buildMillis: Long = elapsedMillis(buildStart)
        assertTrue(rows.rows.size >= 2 * TURNS, "rows: ${rows.rows.size}")

        println(
            "long-session: turns=$TURNS bytes=${file.fileSize()} entries=${loaded.state.entries.size} rows=${rows.rows.size} " +
                "list=${listMillis}ms read=${readMillis}ms build=${buildMillis}ms",
        )
        for ((step, millis) in listOf("list" to listMillis, "read" to readMillis, "build" to buildMillis)) {
            assertTrue(millis < LIMIT_MILLIS, "$step took ${millis}ms")
        }
    }

    private fun elapsedMillis(start: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

    private companion object {
        const val TURNS: Int = 400
        const val LIMIT_MILLIS: Long = 30_000
    }
}
