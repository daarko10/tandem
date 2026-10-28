package dev.daarkolabs.tandem.ide.context

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.daarkolabs.tandem.chat.MentionQuery
import dev.daarkolabs.tandem.chat.mentionPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The `@` palette's files (spec 3.5, 9.5), in a headless project opened on a temporary folder. */
@TestApplication
class ProjectFilesTest {
    companion object {
        private val dir = tempPathFixture()
        private val project = projectFixture(dir, openAfterCreation = true)
    }

    /** Writes [files] under the project's folder and makes the VFS see them. */
    private fun write(vararg files: Pair<String, String>): Path {
        val base = dir.get()
        for ((path, text) in files) base.resolve(path).also { it.parent.createDirectories() }.writeText(text)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)!!.refresh(false, true)
        return base
    }

    // spec: candidates list the project's files relative to the base
    @Test
    fun `candidates list the project's files relative to the base`() = timeoutRunBlocking(30.seconds) {
        write("hello.txt" to "alpha\n", "src/main/Foo.kt" to "class Foo\n", ".idea/workspace.xml" to "<x/>")
        val files = ProjectFiles.getInstance(project.get())
        val candidates = files.candidates()
        assertTrue("hello.txt" in candidates, candidates.toString())
        assertTrue("src/main/Foo.kt" in candidates, candidates.toString())
        assertTrue(candidates.none { it.startsWith(".idea/") }, "the IDE's own folder is left out: $candidates")
        assertEquals(candidates.toSet(), files.known.value)
        // The IDE's matcher ranks them; an empty text lists them as read.
        assertEquals(listOf("hello.txt"), files.matching("hel"))
        assertEquals(listOf("src/main/Foo.kt"), files.matching("smfoo"))
        assertEquals(candidates.take(1), files.matching("", limit = 1))
        // A new file shows once the VFS reports it: the synchronous refresh drops the cached list.
        write("new.txt" to "n\n")
        val listed = files.candidates()
        assertTrue("new.txt" in listed, listed.toString())
    }

    // spec: open editors come first
    @Test
    fun `open editors come first`() = timeoutRunBlocking(30.seconds) {
        val base = write("a.txt" to "a\n", "b/c.txt" to "c\n")
        val vfs = LocalFileSystem.getInstance()
        val c = vfs.refreshAndFindFileByNioFile(base.resolve("b/c.txt"))!!
        val a = vfs.refreshAndFindFileByNioFile(base.resolve("a.txt"))!!
        val files = ProjectFiles.getInstance(project.get())
        // No editor is open in the headless project: none comes first.
        assertEquals(emptyList(), files.openFirst)
        // The selected editor's file, then the other open ones, each once, relative to the base; one outside it is left out.
        val outside = vfs.refreshAndFindFileByNioFile(Files.createTempFile("outside", ".txt"))!!
        files.openFiles = { listOf(c, a, c, outside) }
        try {
            assertEquals(listOf("b/c.txt", "a.txt"), files.openFirst)
            assertEquals(listOf("b/c.txt", "a.txt"), mentionPalette(MentionQuery(0, "t"), files.candidates(), files.openFirst).rows.take(2))
        } finally {
            files.openFiles = { emptyList() }
        }
    }

    // spec: a change under an ignored folder keeps the list
    @Test
    fun `a change under an ignored folder keeps the list and no walk starts`() = timeoutRunBlocking(30.seconds) {
        write("e.txt" to "e\n", ".git/HEAD" to "ref: main\n")
        val files = ProjectFiles.getInstance(project.get())
        val before = files.candidates()
        assertTrue("e.txt" in before && before.none { it.startsWith(".git/") }, before.toString())
        // The reads the writes above asked for are done before the count is taken.
        files.settle()
        val read = files.candidates()
        val reads = files.reads.get()
        // A commit writes under .git: the cached list stands (the same instance), and no walk starts.
        write(".git/index" to "i")
        assertSame(read, files.candidates())
        files.settle()
        assertEquals(reads, files.reads.get(), "no walk for a change under an ignored folder")
        // A new file in a listed folder drops it: the synchronous refresh clears the cache before the write returns.
        write("f.txt" to "f\n")
        val after = files.candidates()
        assertTrue("f.txt" in after, after.toString())
    }

    @Test
    fun `a mentioned file with unsaved changes gives its buffer`() = timeoutRunBlocking(30.seconds) {
        // Its own folder: `dir` exists whatever ran before, so the directory check below is real.
        val base = write("d.txt" to "d\n", "dir/x.txt" to "x")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.resolve("d.txt"))!!
        val files = ProjectFiles.getInstance(project.get())
        val document = withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().getDocument(file)!! }
        assertNull(files.unsavedText("d.txt"), "a saved file sends nothing")
        edtWriteAction { document.setText("changed\n") }
        try {
            assertEquals("changed\n", files.unsavedText("d.txt"))
            assertEquals("changed\n", files.unsavedText(base.resolve("d.txt").toString()))
            assertNull(files.unsavedText("missing.txt"))
            assertNull(files.unsavedText("dir"), "a directory has no buffer")
        } finally {
            withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().saveAllDocuments() }
        }
        assertNull(files.unsavedText("d.txt"))
    }
}
