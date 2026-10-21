package dev.daarkolabs.tandem.ui

import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `+` menu's file chooser offers the image types the API takes (polish spec 2.7). */
@TestApplication
class ImageChooserTest {
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
}
