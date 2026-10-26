package dev.daarkolabs.tandem.ide

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What `Add folder` makes of a chosen folder (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 3.4). */
sealed interface FolderCheck {
    /** Add it, by its [realPath] (links resolved, as the CLI and the transcripts key paths). */
    data class Add(val realPath: String) : FolderCheck

    /** Not added; [message] says why, in the composer's notice. */
    data class Refused(val message: String) : FolderCheck
}

/**
 * Checks [chosen] before it is added to a session whose project is [projectRoot] and that has the folders [added] (real paths), in
 * the spec's order: not a directory; the project's root or a folder under it (path segments, both real); already added; else
 * [FolderCheck.Add] with its real path. Touches the file system: call on `Dispatchers.IO`.
 */
fun checkFolder(chosen: Path, projectRoot: Path, added: List<String>): FolderCheck {
    val notFolder = FolderCheck.Refused("Could not add $chosen: it is not a folder.")
    if (!Files.isDirectory(chosen)) return notFolder
    val real = try {
        chosen.toRealPath()
    } catch (_: IOException) {
        return notFolder // gone between the two calls
    }
    val root = try {
        projectRoot.toRealPath()
    } catch (_: IOException) {
        projectRoot.toAbsolutePath().normalize()
    }
    val name = real.fileName?.toString() ?: real.toString()
    if (real == root || real.startsWith(root)) return FolderCheck.Refused("$name is inside the project, so Claude can already use it.")
    if (real.toString() in added) return FolderCheck.Refused("$name is already added.")
    return FolderCheck.Add(real.toString())
}
