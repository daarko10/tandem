package dev.daarkolabs.tandem.ui

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the composer reads from the clipboard, a drop and the file chooser (polish spec 2.7). */
class ComposerImagesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `text with an image rendering pastes as text`() {
        assertFalse(ComposerImages.clipboardHasImage { listOf(DataFlavor.stringFlavor, DataFlavor.imageFlavor) })
        assertTrue(ComposerImages.clipboardHasImage { listOf(DataFlavor.imageFlavor) })
        assertTrue(ComposerImages.clipboardHasImage { listOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor) })
        assertFalse(ComposerImages.clipboardHasImage { listOf(DataFlavor.stringFlavor) })
        assertFalse(ComposerImages.clipboardHasImage { emptyList() })
    }

    @Test
    fun `a clipboard that cannot be read is a text paste`() {
        assertFalse(ComposerImages.clipboardHasImage { throw IllegalStateException("cannot open system clipboard") })
    }

    @Test
    fun `a pasted raw bitmap is encoded as PNG once`() = runBlocking {
        val calls = AtomicInteger()
        val counting: (Image) -> ByteArray = {
            calls.incrementAndGet()
            pngOf(it)
        }
        val read = ComposerImages.readClipboardImages(contents = { Offer(DataFlavor.imageFlavor to BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)) }, encodePng = counting)
        assertEquals(1, read.size)
        val (name, bytes) = read.single()
        assertEquals("Pasted image.png", name)
        val png = requireNotNull(bytes)
        assertContentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), png.copyOf(4))
        assertEquals(1, calls.get())
    }

    @Test
    fun `a pasted file list without an image falls back to a text paste`() = runBlocking {
        val text = dir.resolve("x.txt").apply { writeText("not an image") }
        val textOnly = Offer(DataFlavor.javaFileListFlavor to listOf(text.toFile()), DataFlavor.stringFlavor to "/tmp/x.txt")
        assertEquals(emptyList(), ComposerImages.readClipboardImages(contents = { textOnly }))
        assertEquals("/tmp/x.txt", ComposerImages.clipboardText(contents = { textOnly }))
        assertEquals(emptyList(), ComposerImages.readClipboardImages(contents = { Offer(DataFlavor.javaFileListFlavor to emptyList<File>()) }))
        // A list holding an image reads every file, so that the one that is not an image gets its notice from Attachments.check.
        val png = dir.resolve("a.PNG").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) }
        val both = ComposerImages.readClipboardImages(contents = { Offer(DataFlavor.javaFileListFlavor to listOf(png.toFile(), text.toFile())) })
        assertEquals(listOf("a.PNG", "x.txt"), both.map { it.first })
        assertContentEquals(png.toFile().readBytes(), both[0].second)
        assertContentEquals("not an image".toByteArray(), both[1].second)
        // Nothing on the clipboard, or a clipboard that cannot be read: no images and no text.
        assertEquals(emptyList(), ComposerImages.readClipboardImages(contents = { null }))
        assertNull(ComposerImages.clipboardText(contents = { null }))
        assertNull(ComposerImages.clipboardText(contents = { throw IllegalStateException("cannot open system clipboard") }))
        assertEquals(emptyList(), ComposerImages.readClipboardImages(contents = { throw IllegalStateException("cannot open system clipboard") }))
    }

    @Test
    fun `a file that cannot be read leaves a notice, not an exception`() = runBlocking {
        assertEquals(listOf("gone.png" to null), ComposerImages.readFiles(listOf(dir.resolve("gone.png"))))
    }

    @Test
    fun `a drop of files yields their paths and anything else none`() {
        val a = dir.resolve("a.png").toFile()
        val b = dir.resolve("b.txt").toFile()
        assertEquals(listOf(a.toPath(), b.toPath()), ComposerImages.droppedPaths(Offer(DataFlavor.javaFileListFlavor to listOf(a, b))))
        assertEquals(emptyList(), ComposerImages.droppedPaths(Offer(DataFlavor.stringFlavor to "a.png")))
        val throwing = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor

            override fun getTransferData(flavor: DataFlavor): Any = throw IOException("the drag source went away")
        }
        assertEquals(emptyList(), ComposerImages.droppedPaths(throwing))
    }

    /** A [Transferable] offering each flavour of [data] with its value. */
    private class Offer(vararg data: Pair<DataFlavor, Any>) : Transferable {
        private val values = linkedMapOf(*data)

        override fun getTransferDataFlavors(): Array<DataFlavor> = values.keys.toTypedArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in values

        override fun getTransferData(flavor: DataFlavor): Any = values[flavor] ?: throw UnsupportedFlavorException(flavor)
    }
}
