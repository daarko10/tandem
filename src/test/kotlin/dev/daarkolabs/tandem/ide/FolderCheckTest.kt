package dev.daarkolabs.tandem.ide

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** `Add folder`'s checks (desktop-parity spec 3.4): not a folder, inside the project, already added, else its real path. */
class FolderCheckTest {
    @TempDir
    lateinit var tmp: Path

    private val root: Path by lazy { tmp.resolve("x/app").createDirectories() }

    private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

    @Test
    fun `a folder outside the project is added by its real path`() {
        val outside = tmp.resolve("elsewhere/lib").createDirectories()
        assertEquals(FolderCheck.Add(outside.toRealPath().toString()), checkFolder(outside, root, emptyList()))
    }

    @Test
    fun `the project root and a folder under it are refused`() {
        val name = root.toRealPath().fileName
        assertEquals(FolderCheck.Refused("$name is inside the project, so Claude can already use it."), checkFolder(root, root, emptyList()))
        val sub = root.resolve("sub").createDirectories()
        assertEquals(FolderCheck.Refused("sub is inside the project, so Claude can already use it."), checkFolder(sub, root, emptyList()))
    }

    @Test
    fun `a folder already added is refused, also through a link`() {
        val outside = tmp.resolve("elsewhere/lib").createDirectories()
        val added = listOf(outside.toRealPath().toString())
        assertEquals(FolderCheck.Refused("lib is already added."), checkFolder(outside, root, added))
        assumeTrue(!isWindows, "symbolic links need privileges on Windows")
        val link = Files.createSymbolicLink(tmp.resolve("link"), outside)
        assertEquals(FolderCheck.Refused("lib is already added."), checkFolder(link, root, added))
    }

    @Test
    fun `a file is not a folder`() {
        val file = tmp.resolve("notes.txt").apply { writeText("x") }
        assertEquals(FolderCheck.Refused("Could not add $file: it is not a folder."), checkFolder(file, root, emptyList()))
        val gone = tmp.resolve("gone")
        assertEquals(FolderCheck.Refused("Could not add $gone: it is not a folder."), checkFolder(gone, root, emptyList()))
    }

    @Test
    fun `a sibling that shares the project's prefix is outside`() {
        val sibling = tmp.resolve("x/app2").createDirectories()
        assertEquals(FolderCheck.Add(sibling.toRealPath().toString()), checkFolder(sibling, root, emptyList()))
    }
}
