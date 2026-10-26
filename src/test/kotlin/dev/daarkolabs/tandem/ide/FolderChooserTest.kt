package dev.daarkolabs.tandem.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.openProjectAsync
import com.intellij.testFramework.useProjectAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** `Add folder`'s chooser (desktop-parity spec 3.4, 9.4): an EDT event of its own after the menu's, and the IDE tests' property. */
@TestApplication
class FolderChooserTest {
    companion object {
        private val tempDir = tempPathFixture()
    }

    @Test
    fun `the addFolder property names the folder`() {
        val dir = tempDir.get().resolve("chosen").toString()
        val before = System.getProperty("tandem.addFolder")
        try {
            System.setProperty("tandem.addFolder", dir)
            assertEquals(Path.of(dir), FolderChoosers.propertyFolder())
            System.clearProperty("tandem.addFolder")
            // The platform chooser is modal: not called in a unit test.
            assertNull(FolderChoosers.propertyFolder())
        } finally {
            if (before != null) System.setProperty("tandem.addFolder", before) else System.clearProperty("tandem.addFolder")
        }
    }

    @Test
    fun `the folder chooser opens once on the EDT after the menu's event, and never for a closed project`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("outside").createDirectories()
        val opened = ArrayList<Boolean>()
        val chosen = ArrayList<Path>()
        val chooser = FolderChooser {
            opened += ApplicationManager.getApplication().isDispatchThread
            dir
        }
        val cancelled = FolderChooser {
            opened += ApplicationManager.getApplication().isDispatchThread
            null
        }
        val project = openProjectAsync(tempDir.get().resolve("folder-chooser").createDirectories()).useProjectAsync { project ->
            withContext(Dispatchers.EDT) {
                chooser.chooseLater(project, { chosen.add(it) })
                assertTrue(opened.isEmpty(), "the chooser opened inside the caller's event")
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
            assertEquals(listOf(true), opened)
            assertEquals(listOf(dir), chosen)
            withContext(Dispatchers.EDT) {
                cancelled.chooseLater(project, { chosen.add(it) })
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
            assertEquals(listOf(true, true), opened)
            assertEquals(listOf(dir), chosen, "a cancel passes nothing on")
            project
        }
        withContext(Dispatchers.EDT) {
            chooser.chooseLater(project, { chosen.add(it) })
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
        assertEquals(2, opened.size, "a chooser opened for a closed project")
        assertEquals(listOf(dir), chosen)
    }
}
