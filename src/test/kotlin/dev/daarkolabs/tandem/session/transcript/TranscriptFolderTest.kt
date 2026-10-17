package dev.daarkolabs.tandem.session.transcript

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptFolderTest {
    @Test
    fun `replaces every character outside letters and digits`() {
        assertEquals("-home-u-my-proj-v2", TranscriptFolder.key("/home/u/my proj.v2"))
        // A Windows path must map to a key, not throw or come out empty.
        assertEquals("C--Users-dev-app", TranscriptFolder.key("C:\\Users\\dev\\app"))
        // Only ASCII letters and digits survive: an accented letter is replaced too.
        assertEquals("-home-jos-", TranscriptFolder.key("/home/jos\u00E9"))
    }

    @Test
    fun `a character outside the basic plane becomes one dash per UTF-16 unit, as in the CLI`() {
        // U+1F600 is a surrogate pair. The CLI runs a JavaScript regex over UTF-16 units, so it writes two dashes;
        // a Kotlin Regex would see one code point and write one, and Tandem would look in the wrong folder.
        assertEquals("-home-u-a--b", TranscriptFolder.key("/home/u/a\uD83D\uDE00b"))
    }

    @Test
    fun `an empty path has an empty key`() {
        assertEquals("", TranscriptFolder.key(""))
    }

    @Test
    fun `truncates keys longer than 200 characters`() {
        val key = TranscriptFolder.key("/" + "a".repeat(250))
        assertEquals(200, key.length)
        assertTrue(key.startsWith("-aaa"))
        // The limit itself: 200 characters are kept whole, 199 stay 199.
        assertEquals(200, TranscriptFolder.key("/" + "a".repeat(199)).length)
        assertEquals(199, TranscriptFolder.key("/" + "a".repeat(198)).length)
    }

    @Test
    fun `config dir comes from CLAUDE_CONFIG_DIR else home`() {
        assertEquals(Path.of("/cfg"), TranscriptFolder.configDir(mapOf("CLAUDE_CONFIG_DIR" to "/cfg"), Path.of("/h")))
        assertEquals(Path.of("/h/.claude"), TranscriptFolder.configDir(emptyMap(), Path.of("/h")))
    }

    @Test
    fun `a blank CLAUDE_CONFIG_DIR is ignored`() {
        assertEquals(Path.of("/h/.claude"), TranscriptFolder.configDir(mapOf("CLAUDE_CONFIG_DIR" to ""), Path.of("/h")))
        assertEquals(Path.of("/h/.claude"), TranscriptFolder.configDir(mapOf("CLAUDE_CONFIG_DIR" to "  "), Path.of("/h")))
    }

    @Test
    fun `a CLAUDE_CONFIG_DIR that is no valid path falls back to the home folder`() {
        assertEquals(Path.of("/h/.claude"), TranscriptFolder.configDir(mapOf("CLAUDE_CONFIG_DIR" to "/cfg\u0000x"), Path.of("/h")))
    }

    @Test
    fun `an empty key has no candidates, rather than the projects folder itself`(@TempDir projects: Path) {
        assertEquals(emptyList<Path>(), TranscriptFolder.candidates(projects, ""))
    }

    @Test
    fun `a 199-character key is still one exact folder`(@TempDir projects: Path) {
        val path = "/" + "h".repeat(198)
        val key = TranscriptFolder.key(path)
        assertEquals(199, key.length)
        projects.resolve("$key-other").createDirectories() // would be a candidate if the key were cut
        assertEquals(listOf(projects.resolve(key)), TranscriptFolder.candidates(projects, path))
    }

    @Test
    fun `a long key with no folder of its own has no candidates`(@TempDir projects: Path) {
        projects.resolve("-home-u-app").createDirectories()
        projects.resolve("-" + "i".repeat(150)).createDirectories() // a prefix of the key, not the other way round
        assertEquals(emptyList<Path>(), TranscriptFolder.candidates(projects, "/" + "i".repeat(300)))
    }

    @Test
    fun `a long key takes a linked folder as the folder it links to`(@TempDir root: Path) {
        val projects = root.resolve("projects").createDirectories()
        val long = "/" + "j".repeat(300)
        val key = TranscriptFolder.key(long)
        val target = root.resolve("elsewhere").createDirectories()
        projects.resolve("$key-abc").createSymbolicLinkPointingTo(target)
        projects.resolve("$key-dangling").createSymbolicLinkPointingTo(root.resolve("missing"))
        assertEquals(listOf(projects.resolve("$key-abc")), TranscriptFolder.candidates(projects, long))
    }

    @Test
    fun `transcript folders live under projects in the config dir`() {
        assertEquals(Path.of("/cfg/projects"), TranscriptFolder.projectsDir(Path.of("/cfg")))
    }

    @Test
    fun `candidates are the exact folder, or every folder with the long prefix`(@TempDir projects: Path) {
        // A short key names one folder, whether or not it exists yet.
        assertEquals(listOf(projects.resolve("-home-u-app")), TranscriptFolder.candidates(projects, "/home/u/app"))
        // A long key gets a hash from the CLI that Tandem does not reproduce, so every folder sharing the prefix is a candidate.
        val long = "/" + "b".repeat(300)
        val prefix = TranscriptFolder.key(long)
        projects.resolve("$prefix-abc").createDirectories()
        projects.resolve("$prefix-def").createDirectories()
        projects.resolve("other").createDirectories()
        assertEquals(
            setOf("$prefix-abc", "$prefix-def"),
            TranscriptFolder.candidates(projects, long).map { it.fileName.toString() }.toSet(),
        )
    }

    @Test
    fun `a short key never picks up the folder of a project whose path merely starts the same`(@TempDir projects: Path) {
        projects.resolve("-home-u-app").createDirectories()
        projects.resolve("-home-u-app-extra").createDirectories()
        assertEquals(listOf(projects.resolve("-home-u-app")), TranscriptFolder.candidates(projects, "/home/u/app"))
    }

    @Test
    fun `a long key lists the exact folder too, in name order, and skips files`(@TempDir projects: Path) {
        val long = "/" + "c".repeat(300)
        val key = TranscriptFolder.key(long)
        // Created out of order on purpose: the listing must not depend on the file system's order.
        listOf("-e", "-b", "", "-d", "-a", "-c").forEach { projects.resolve(key + it).createDirectories() }
        projects.resolve("$key-file").createFile()
        assertEquals(
            listOf(key, "$key-a", "$key-b", "$key-c", "$key-d", "$key-e"),
            TranscriptFolder.candidates(projects, long).map { it.fileName.toString() },
        )
    }

    @Test
    fun `a long key has no candidates when the projects folder is missing or not a directory`(@TempDir tmp: Path) {
        val long = "/" + "d".repeat(300)
        assertEquals(emptyList<Path>(), TranscriptFolder.candidates(tmp.resolve("missing"), long))
        assertEquals(emptyList<Path>(), TranscriptFolder.candidates(tmp.resolve("projects").createFile(), long))
    }

    @Test
    fun `a long key takes a folder that differs in case only when the file system says it is the same folder`(@TempDir projects: Path) {
        // The project as the IDE resolved it, and its folder as the CLI named it from a working directory spelt in other case.
        val long = "/" + "e".repeat(300)
        val key = TranscriptFolder.key(long)
        projects.resolve("${key.uppercase()}-abc").createDirectories()
        val caseInsensitive = { a: Path, b: Path -> a.toString().equals(b.toString(), ignoreCase = true) }
        assertEquals(
            listOf("${key.uppercase()}-abc"),
            TranscriptFolder.candidates(projects, long, sameFile = caseInsensitive).map { it.fileName.toString() },
            "on a case-insensitive volume (macOS) it is this project's folder",
        )
        assertEquals(
            emptyList<Path>(),
            TranscriptFolder.candidates(projects, long, sameFile = { _, _ -> false }),
            "on a case-sensitive one it is another project's",
        )
    }

    @Test
    fun `here a long key's folder in other case is taken only where the volume ignores case`(@TempDir projects: Path) {
        val long = "/" + "f".repeat(300)
        val key = TranscriptFolder.key(long)
        projects.resolve("${key.uppercase()}-abc").createDirectories()
        val found = TranscriptFolder.candidates(projects, long).map { it.fileName.toString() }
        if (ignoresCase(projects)) {
            assertEquals(listOf("${key.uppercase()}-abc"), found)
        } else {
            assertEquals(emptyList(), found)
        }
    }

    @Test
    fun `a long key's folder in other case is found on a case-insensitive volume`(@TempDir projects: Path) {
        assumeTrue(ignoresCase(projects), "needs a case-insensitive volume, as macOS has by default")
        val long = "/" + "g".repeat(300)
        projects.resolve("${TranscriptFolder.key(long).uppercase()}-abc").createDirectories()
        assertEquals(1, TranscriptFolder.candidates(projects, long).size)
    }

    /** Whether the volume of [dir] ignores case in names, as macOS's APFS does by default. */
    private fun ignoresCase(dir: Path): Boolean {
        val probe = dir.resolve("CaseProbe").createDirectories()
        return try {
            Files.exists(dir.resolve("caseprobe"))
        } finally {
            Files.delete(probe)
        }
    }
}
