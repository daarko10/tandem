package dev.daarkolabs.tandem.ide.context

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/**
 * The bubbles' `@path` chips (spec 3.5) need the file list before anyone types `@`: a fresh project's [ProjectFiles] reads it as it
 * starts. Its own project, so that no other test has asked for the list first.
 */
@TestApplication
class ProjectFilesWarmTest {
    companion object {
        private val dir = tempPathFixture()
        private val project = projectFixture(dir, openAfterCreation = true)
    }

    private fun write(name: String) {
        dir.get().resolve(name).writeText("w\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.get())!!.refresh(false, true)
    }

    @Test
    fun `known fills without a lookup and again when a file is created`(): Unit = timeoutRunBlocking(30.seconds) {
        write("warm.txt")
        // Nothing here asks for the list (no candidates() nor matching()): the service reads it as it starts.
        val files = ProjectFiles.getInstance(project.get())
        files.known.first { "warm.txt" in it }
        // A file created under the project is read again, whether or not the list was ever asked for.
        write("warm2.txt")
        files.known.first { "warm2.txt" in it }
    }
}
