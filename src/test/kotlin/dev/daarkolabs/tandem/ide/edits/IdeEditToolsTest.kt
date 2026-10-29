package dev.daarkolabs.tandem.ide.edits

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.daarkolabs.tandem.protocol.ToolReply
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.ToolCall
import dev.daarkolabs.tandem.session.edits.EditResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [IdeEditTools] on a real (headless) project whose base path is a temporary folder on disk (spec 9.5). Each test's `review` answers
 * at once; a test that changes the file "while the diff is open" does it inside its `review`.
 */
@TestApplication
class IdeEditToolsTest {
    companion object {
        private val tempDir = tempPathFixture()
        private val projectFx = projectFixture(tempDir, openAfterCreation = true)
        private val folders = AtomicInteger()
        private const val SESSION: SessionId = "session-1"
        private const val HELLO = "alpha\nline two\n"
    }

    private val project get() = projectFx.get()

    /** This test's own folder under the project's base path. */
    private val dir: Path by lazy {
        Files.createDirectories(Path.of(project.basePath!!).toRealPath().resolve("t${folders.incrementAndGet()}"))
    }

    private val notices = CopyOnWriteArrayList<Pair<SessionId, String>>()
    private var review: suspend (EditProposal) -> Decision = { Decision.Accepted(it.proposed, all = false) }
    private val tools: IdeEditTools by lazy {
        IdeEditTools(project, { emptyList() }, { review(it) }, { session, text -> notices += session to text })
    }

    private fun write(name: String, text: String): Path {
        val path = dir.resolve(name)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    private fun vfs(path: Path): VirtualFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))

    private suspend fun documentOf(path: Path): Document = readAction { requireNotNull(FileDocumentManager.getInstance().getDocument(vfs(path))) }

    private suspend fun textOf(document: Document): String = readAction { document.text }

    private suspend fun edit(document: Document, change: (Document) -> Unit) = withContext(Dispatchers.EDT) {
        WriteCommandAction.runWriteCommandAction(project) { change(document) }
    }

    private fun editCall(path: Path, old: String, new: String): ToolCall = call("edit_file") {
        put("file_path", path.toString())
        put("old_string", old)
        put("new_string", new)
    }

    private fun writeCall(path: Path, content: String): ToolCall = call("write_file") {
        put("file_path", path.toString())
        put("content", content)
    }

    private fun call(name: String, arguments: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ToolCall =
        ToolCall(name, buildJsonObject(arguments), "toolu_1", JsonPrimitive(1), SESSION)

    private fun run(call: ToolCall, planMode: Boolean = false): ToolReply =
        timeoutRunBlocking(30.seconds) { tools.call(call, autoAccept = null, planMode = planMode) }

    private fun assertReply(expected: ToolReply, actual: ToolReply) {
        assertEquals(expected.isError, actual.isError, actual.text)
        assertEquals(expected.text, actual.text)
    }

    @Test
    fun `an edit applies to the Document of a file open in no editor`() {
        val path = write("hello.txt", HELLO)
        val reply = run(editCall(path, "alpha", "beta"))
        assertFalse(reply.isError, reply.text)
        assertEquals(
            "Applied the edit to $path and saved the file.\nThe file now reads (lines 1-2 of 2):\n     1\tbeta\n     2\tline two",
            reply.text,
        )
        timeoutRunBlocking(30.seconds) {
            assertEquals("beta\nline two\n", textOf(documentOf(path)))
            assertFalse(withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).isFileOpen(vfs(path)) })
        }
    }

    @Test
    fun `one undo restores it`() {
        val path = write("hello.txt", HELLO)
        assertFalse(run(editCall(path, "alpha", "beta")).isError)
        timeoutRunBlocking(30.seconds) {
            val document = documentOf(path)
            withContext(Dispatchers.EDT) {
                val editor = TextEditorProvider.getInstance().createEditor(project, vfs(path)) as TextEditor
                try {
                    val undo = UndoManager.getInstance(project)
                    assertTrue(undo.isUndoAvailable(editor))
                    undo.undo(editor)
                } finally {
                    Disposer.dispose(editor)
                }
            }
            assertEquals(HELLO, textOf(document))
        }
    }

    @Test
    fun `an unsaved buffer is matched, not the disk`() {
        val path = write("hello.txt", HELLO)
        timeoutRunBlocking(30.seconds) {
            edit(documentOf(path)) { it.replaceString(0, 5, "gamma") }
        }
        val reply = run(editCall(path, "gamma", "delta"))
        assertFalse(reply.isError, reply.text)
        assertEquals("delta\nline two\n", Files.readString(path))
    }

    @Test
    fun `a new file is created with its directories`() {
        val path = dir.resolve("new/deeper/new.txt")
        val reply = run(writeCall(path, "one\r\ntwo\n"))
        assertReply(EditResults.created(path.toString(), "one\ntwo\n", changed = false), reply)
        assertEquals("one\ntwo\n", Files.readString(path))
    }

    @Test
    fun `a read-only file is refused`() {
        val path = write("hello.txt", HELLO)
        val file = vfs(path)
        timeoutRunBlocking(30.seconds) { edtWriteAction { file.setWritable(false) } }
        try {
            assertReply(EditResults.readOnly(path.toString()), run(editCall(path, "alpha", "beta")))
            assertEquals(HELLO, Files.readString(path))
        } finally {
            timeoutRunBlocking(30.seconds) { edtWriteAction { file.setWritable(true) } }
        }
    }

    @Test
    fun `a binary file is refused`() {
        val path = dir.resolve("image.png")
        Files.write(path, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D))
        assertReply(EditResults.binary(path.toString()), run(editCall(path, "PNG", "JPG")))
    }

    @Test
    fun `a directory is refused`() {
        val path = Files.createDirectories(dir.resolve("folder"))
        assertReply(EditResults.directory(path.toString()), run(writeCall(path, "text")))
    }

    @Test
    fun `a path outside the base path is refused`() {
        val outside = Files.createTempDirectory("tandem-outside").toRealPath().resolve("hello.txt")
        Files.writeString(outside, HELLO)
        try {
            assertReply(EditResults.outside(outside.toString()), run(editCall(outside, "alpha", "beta")))
            assertReply(EditResults.outside("hello.txt"), run(editCall(Path.of("hello.txt"), "alpha", "beta")))
            assertEquals(HELLO, Files.readString(outside))
        } finally {
            Files.deleteIfExists(outside)
            Files.deleteIfExists(outside.parent)
        }
    }

    @Test
    fun `a file over the IDE's limit is refused`() {
        // The IDE's own limit (idea.max.content.load.filesize), one byte over it.
        val size = FileUtilRt.getUserContentLoadLimit().toLong() + 1
        val path = dir.resolve("large.txt")
        Files.newOutputStream(path).use { out ->
            val chunk = ByteArray(1024 * 1024) { 'x'.code.toByte() }
            var left = size
            while (left > 0) {
                val n = minOf(left, chunk.size.toLong()).toInt()
                out.write(chunk, 0, n)
                left -= n
            }
        }
        try {
            assertReply(EditResults.tooLarge(path.toString(), StringUtil.formatFileSize(size)), run(editCall(path, "x", "y")))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `write_file creates a new file of an unknown type`() {
        val path = dir.resolve("notes.xyz")
        assertReply(EditResults.created(path.toString(), "one\n", changed = false), run(writeCall(path, "one\n")))
        assertEquals("one\n", Files.readString(path))
    }

    @Test
    fun `a new file of unknown type with a NUL is refused`() {
        val path = dir.resolve("blob.zzz")
        assertReply(EditResults.binary(path.toString()), run(writeCall(path, "ab\u0000cd")))
        assertFalse(Files.exists(path))
    }

    @Test
    fun `a file of unknown type with a NUL is refused`() {
        val path = write("data.zzz", "ab\u0000cd")
        assertReply(EditResults.binary(path.toString()), run(editCall(path, "ab", "xy")))
        assertEquals("ab\u0000cd", Files.readString(path))
    }

    @Test
    fun `write_file content over 10 MB is refused`() {
        val path = dir.resolve("huge.txt")
        val size = 10L * 1024 * 1024 + 1
        val reply = run(writeCall(path, "x".repeat(size.toInt())))
        assertReply(EditResults.tooLarge(path.toString(), StringUtil.formatFileSize(size)), reply)
        assertFalse(Files.exists(path))
    }

    @Test
    fun `write_file over an existing file replaces it`() {
        val path = write("hello.txt", HELLO)
        val reply = run(writeCall(path, "one\ntwo\nthree\n"))
        assertReply(EditResults.replaced(path.toString(), "one\ntwo\nthree\n", HELLO, changed = false), reply)
        assertEquals("one\ntwo\nthree\n", Files.readString(path))
    }

    @Test
    fun `plan mode refuses`() {
        val path = write("hello.txt", HELLO)
        assertReply(EditResults.planMode(), run(editCall(path, "alpha", "beta"), planMode = true))
        assertReply(EditResults.planMode(), run(writeCall(dir.resolve("plan.md"), "# Plan\n"), planMode = true))
        assertEquals(HELLO, Files.readString(path))
        assertFalse(Files.exists(dir.resolve("plan.md")))
    }

    @Test
    fun `a thrown IOException in the write becomes the IDE refused error and a notice`() {
        val blocker = write("blocker", "a file, not a folder\n")
        val path = blocker.resolve("new.txt")
        val reply = run(writeCall(path, "text\n"))
        assertTrue(reply.isError, reply.text)
        assertTrue(reply.text.startsWith("The IDE could not apply the edit to $path: "), reply.text)
        assertEquals(1, notices.size, notices.toString())
        assertEquals(SESSION, notices[0].first)
        assertTrue(notices[0].second.startsWith("Could not apply the edit to new.txt: "), notices[0].second)
    }

    @Test
    fun `the disk holds the applied text when the reply is sent`() {
        val path = write("hello.txt", HELLO)
        val reply = run(editCall(path, "alpha", "beta"))
        // The reply is observed only now: the disk and the document must already agree.
        assertFalse(reply.isError, reply.text)
        assertEquals("beta\nline two\n", Files.readString(path))
        timeoutRunBlocking(30.seconds) {
            val document = documentOf(path)
            assertFalse(readAction { FileDocumentManager.getInstance().isDocumentUnsaved(document) })
        }
    }

    @Test
    fun `the file changes while the diff is open, unchanged proposal`() {
        val path = write("hello.txt", HELLO)
        review = { proposal ->
            edit(proposal.document!!) { it.replaceString(0, 5, "omega") }
            Decision.Accepted(proposal.proposed, all = false)
        }
        val reply = run(editCall(path, "alpha", "beta"))
        assertReply(EditResults.fileChanged(path.toString(), "omega\nline two\n"), reply)
        timeoutRunBlocking(30.seconds) { assertEquals("omega\nline two\n", textOf(documentOf(path))) }
        assertEquals(listOf(SESSION to "Could not apply the edit to hello.txt: the file changed while the diff was open"), notices.toList())
    }

    @Test
    fun `the file changes while the diff is open, changed proposal`() {
        val path = write("hello.txt", HELLO)
        review = { proposal ->
            edit(proposal.document!!) { it.insertString(0, "zero\n") }
            Decision.Accepted("beta\nline 2\n", all = false)
        }
        val reply = run(editCall(path, "alpha", "beta"))
        assertReply(EditResults.fileChanged(path.toString(), "zero\nalpha\nline two\n"), reply)
        timeoutRunBlocking(30.seconds) { assertEquals("zero\nalpha\nline two\n", textOf(documentOf(path))) }
        assertEquals(listOf(SESSION to "Could not apply the edit to hello.txt: the file changed while the diff was open"), notices.toList())
    }

    @Test
    fun `a new file that appears while the diff is open is a change`() {
        val path = dir.resolve("late/new.txt")
        review = { proposal ->
            withContext(Dispatchers.IO) { write("late/new.txt", "theirs\n") }
            Decision.Accepted(proposal.proposed, all = false)
        }
        assertReply(EditResults.fileChanged(path.toString(), "theirs\n"), run(writeCall(path, "mine\n")))
        assertEquals("theirs\n", Files.readString(path))
        assertEquals(listOf(SESSION to "Could not apply the edit to new.txt: the file changed while the diff was open"), notices.toList())
    }

    @Test
    fun `a change that keeps old_string in place still applies`() {
        val path = write("hello.txt", HELLO)
        review = { proposal ->
            edit(proposal.document!!) { it.insertString(0, "zero\n") }
            Decision.Accepted(proposal.proposed, all = false)
        }
        val reply = run(editCall(path, "alpha", "beta"))
        assertFalse(reply.isError, reply.text)
        assertEquals("zero\nbeta\nline two\n", Files.readString(path))
        assertTrue(notices.isEmpty(), notices.toString())
    }

    @Test
    fun `a hand change in the diff is what lands and what the model is told`() {
        val path = write("hello.txt", HELLO)
        review = { Decision.Accepted("beta\nline 2\n", all = false) }
        val reply = run(editCall(path, "alpha", "beta"))
        assertFalse(reply.isError, reply.text)
        assertTrue(reply.text.startsWith("Applied to $path and saved the file. The user changed your proposal"), reply.text)
        assertTrue(reply.text.endsWith("The file now reads (lines 1-2 of 2):\n     1\tbeta\n     2\tline 2"), reply.text)
        assertEquals("beta\nline 2\n", Files.readString(path))
    }

    @Test
    fun `a rejection changes nothing and carries the reason`() {
        val path = write("hello.txt", HELLO)
        review = { Decision.Rejected("use gamma") }
        assertReply(EditResults.rejected("use gamma"), run(editCall(path, "alpha", "beta")))
        assertEquals(HELLO, Files.readString(path))
    }

    @Test
    fun `a failed save reports the unsaved text`() {
        // The test runs as root in the sandbox, where a read-only file is still written: a vetoer makes the save fail instead.
        val path = write("hello.txt", HELLO)
        val disposable: Disposable = Disposer.newDisposable()
        try {
            val veto = object : FileDocumentSynchronizationVetoer() {
                override fun maySaveDocument(document: Document, isSaveExplicit: Boolean): Boolean = false
            }
            FileDocumentSynchronizationVetoer.EP_NAME.point.registerExtension(veto, disposable)
            val reply = run(editCall(path, "alpha", "beta"))
            assertTrue(reply.isError, reply.text)
            assertTrue(reply.text.startsWith("The edit is in the IDE's buffer but could not be saved to $path: "), reply.text)
            assertEquals(HELLO, Files.readString(path))
            timeoutRunBlocking(30.seconds) { assertEquals("beta\nline two\n", textOf(documentOf(path))) }
            assertEquals(1, notices.size, notices.toString())
            assertTrue(notices[0].second.startsWith("Could not apply the edit to hello.txt: "), notices[0].second)
        } finally {
            Disposer.dispose(disposable)
            timeoutRunBlocking(30.seconds) {
                val document = documentOf(path)
                withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().saveDocument(document) }
            }
        }
    }

    @Test
    fun `malformed arguments are refused with the tool's text`() {
        val path = write("hello.txt", HELLO)
        val noNew = call("edit_file") {
            put("file_path", path.toString())
            put("old_string", "alpha")
        }
        assertReply(EditResults.malformed("edit_file"), run(noNew))
        val numberContent = call("write_file") {
            put("file_path", path.toString())
            put("content", 7)
        }
        assertReply(EditResults.malformed("write_file"), run(numberContent))
        val badFlag = call("edit_file") {
            put("file_path", path.toString())
            put("old_string", "alpha")
            put("new_string", "beta")
            put("replace_all", "yes")
        }
        assertReply(EditResults.malformed("edit_file"), run(badFlag))
        assertEquals(HELLO, Files.readString(path))
    }

    @Test
    fun `old_string equal to new_string is refused`() {
        val path = write("hello.txt", HELLO)
        assertReply(EditResults.same(), run(editCall(path, "alpha", "alpha")))
    }
}
