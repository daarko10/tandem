package dev.daarkolabs.tandem.session.transcript

import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private val LOG = logger<TranscriptFolder>()

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

    /**
     * `$CLAUDE_CONFIG_DIR` when it is set and not blank, else `.claude` in [home]. A `$CLAUDE_CONFIG_DIR` that is no valid path
     * here also gives `.claude` in [home], with a warning in the log.
     */
    fun configDir(
        env: Map<String, String> = System.getenv(),
        home: Path = Path.of(System.getProperty("user.home")),
    ): Path {
        val configured = env["CLAUDE_CONFIG_DIR"]
        if (configured.isNullOrBlank()) return home.resolve(".claude")
        return try {
            Path.of(configured)
        } catch (e: InvalidPathException) {
            LOG.warn("CLAUDE_CONFIG_DIR is no valid path (${e.javaClass.simpleName}); looking for transcripts in ~/.claude")
            home.resolve(".claude")
        }
    }

    /** The folder inside [configDir] that holds one transcript folder per project. */
    fun projectsDir(configDir: Path): Path = configDir.resolve("projects")

    /**
     * The folders that can hold this project's transcripts: the exact key, or every folder sharing the 200-char prefix. None for
     * an empty key, which names no project's folder.
     *
     * A key shorter than [MAX_KEY_LENGTH] names one folder, returned whether or not it exists yet. A key at the limit
     * may have had a hash appended, so every directory in [projectsDir] whose name starts with it is returned instead,
     * sorted by name (the exact folder included, if present). A missing or unreadable [projectsDir] then gives an empty list.
     * A folder whose name starts with the key in other case only is taken when the file system says it is the folder the key
     * names, as on a case-insensitive volume (macOS by default), where the CLI may have spelt the working directory otherwise.
     */
    fun candidates(projectsDir: Path, projectPath: String): List<Path> = candidates(projectsDir, projectPath, ::isSameFile)

    /** [candidates], with [sameFile] in place of the file system's verdict on whether two paths name one folder. */
    internal fun candidates(projectsDir: Path, projectPath: String, sameFile: (Path, Path) -> Boolean): List<Path> {
        val key = key(projectPath)
        if (key.isEmpty()) return emptyList()
        if (key.length < MAX_KEY_LENGTH) return listOf(projectsDir.resolve(key))
        fun isCandidate(folder: Path): Boolean {
            val name = folder.name
            if (name.startsWith(key)) return true
            // The same folder by the key's spelling: on a case-sensitive volume there is none, or another one.
            return name.startsWith(key, ignoreCase = true) && sameFile(folder, projectsDir.resolve(key + name.substring(key.length)))
        }
        return try {
            Files.list(projectsDir).use { entries ->
                entries.filter { it.isDirectory() && isCandidate(it) }.toList().sortedBy { it.name }
            }
        } catch (e: IOException) {
            LOG.debug { "projects folder not listed (${e.javaClass.simpleName})" } // missing until the first session, or refused
            emptyList()
        } catch (e: UncheckedIOException) { // Files.list reports a failure while it iterates this way
            LOG.debug { "projects folder not listed (${e.cause?.javaClass?.simpleName})" }
            emptyList()
        }
    }

    /** Whether [a] and [b] are one file, as the file system says; false when either cannot be looked up. */
    private fun isSameFile(a: Path, b: Path): Boolean =
        try {
            Files.isSameFile(a, b)
        } catch (_: IOException) {
            false
        }

    private fun Char.isKeyCharacter(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
}
