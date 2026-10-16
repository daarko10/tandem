package dev.daarkolabs.tandem.session.transcript

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Where the CLI keeps a project's transcripts: `<config dir>/projects/<key>/<session id>.jsonl`.
 * Only [candidates] touches the file system, and only for long paths: call it off the EDT.
 */
object TranscriptFolder {
    /** The CLI cuts a key to this many characters; for a longer path it then appends `-<hash>`. */
    const val MAX_KEY_LENGTH: Int = 200

    /**
     * The folder name the CLI uses for [projectPath]: every character outside `A–Z a–z 0–9` becomes `-`, cut to [MAX_KEY_LENGTH].
     * The `-<hash>` the CLI adds after a cut is not reproduced here; see [candidates].
     */
    fun key(projectPath: String): String =
        // One dash per UTF-16 unit, as the CLI's JavaScript `replace(/[^a-zA-Z0-9]/g, "-")` does. A Kotlin Regex
        // works on code points and would turn an emoji into a single dash, pointing Tandem at the wrong folder.
        projectPath.map { if (it.isKeyCharacter()) it else '-' }.joinToString("").take(MAX_KEY_LENGTH)

    /** `$CLAUDE_CONFIG_DIR` when it is set and not blank, else `.claude` in [home]. */
    fun configDir(
        env: Map<String, String> = System.getenv(),
        home: Path = Path.of(System.getProperty("user.home")),
    ): Path {
        val configured = env["CLAUDE_CONFIG_DIR"]
        return if (configured.isNullOrBlank()) home.resolve(".claude") else Path.of(configured)
    }

    /** The folder inside [configDir] that holds one transcript folder per project. */
    fun projectsDir(configDir: Path): Path = configDir.resolve("projects")

    /**
     * The folders that can hold this project's transcripts: the exact key, or every folder sharing the 200-char prefix.
     *
     * A key shorter than [MAX_KEY_LENGTH] names one folder, returned whether or not it exists yet. A key at the limit
     * may have had a hash appended, so every directory in [projectsDir] whose name starts with it is returned instead,
     * sorted by name (the exact folder included, if present). A missing or unreadable [projectsDir] then gives an empty list.
     */
    fun candidates(projectsDir: Path, projectPath: String): List<Path> {
        val key = key(projectPath)
        if (key.length < MAX_KEY_LENGTH) return listOf(projectsDir.resolve(key))
        return try {
            Files.list(projectsDir).use { entries ->
                entries.filter { it.name.startsWith(key) && it.isDirectory() }.toList().sortedBy { it.name }
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: UncheckedIOException) { // Files.list reports a failure while it iterates this way
            emptyList()
        }
    }

    private fun Char.isKeyCharacter(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
}
