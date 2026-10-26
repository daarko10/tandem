package dev.daarkolabs.tandem.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.LightVirtualFile
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The `+` menu's file chooser offers the image types the API takes, and opens as an EDT event of its own (polish spec 2.7). */
@TestApplication
class ImageChooserTest {
    companion object {
        private val tempDir = tempPathFixture()
    }

    @Test
    fun `the file chooser descriptor accepts png, jpg, jpeg, gif and webp only`() {
        val descriptor = ComposerImages.descriptor()
        listOf("a.png", "a.jpg", "a.jpeg", "a.gif", "a.webp", "a.PNG").forEach { name ->
            assertTrue(descriptor.isFileSelectable(LightVirtualFile(name)), name)
        }
        listOf("a.txt", "a.svg", "a.bmp").forEach { name ->
            assertFalse(descriptor.isFileSelectable(LightVirtualFile(name)), name)
        }
    }

    @Test
    fun `the image chooser opens once on the EDT after the click's event, and never for a closed project`() = timeoutRunBlocking(30.seconds) {
        val picked = listOf(tempDir.get().resolve("a.png"))
        val opened = ArrayList<Boolean>()
        val chosen = ArrayList<List<Path>>()
        val choose = {
            opened += ApplicationManager.getApplication().isDispatchThread
            picked
        }
        val project = openProjectAsync(tempDir.get().resolve("chooser").createDirectories()).useProjectAsync { project ->
            withContext(Dispatchers.EDT) {
                ComposerImages.chooseImagesLater(project, choose) { chosen += it }
                assertTrue(opened.isEmpty(), "the chooser opened inside the caller's event")
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
            assertEquals(listOf(true), opened)
            assertEquals(listOf(picked), chosen)
            project
        }
        withContext(Dispatchers.EDT) {
            ComposerImages.chooseImagesLater(project, choose) { chosen += it }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
        assertEquals(1, opened.size, "a chooser opened for a closed project")
        assertEquals(1, chosen.size)
    }
}
