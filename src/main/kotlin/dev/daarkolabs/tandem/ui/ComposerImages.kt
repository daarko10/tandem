package dev.daarkolabs.tandem.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.chat.Attachments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Where the composer's images come from (polish spec 2.7): the clipboard, a drop and the `+` menu's file chooser. Only
 * [clipboardHasImage] (the clipboard's flavours) and [chooseImages] (a modal chooser) run on the EDT; everything that reads data runs
 * on [Dispatchers.IO]. A file is named by its file name, which the notices quote; nothing here logs a name or a byte of it.
 */
internal object ComposerImages {
    private val LOG: Logger = logger<ComposerImages>()

    /** What a pasted raw bitmap is called in the notices. */
    private const val PASTED_NAME = "Pasted image.png"

    /**
     * Whether Ctrl/Cmd+V pastes images: the clipboard offers a file list, or an image without text. Rich-text sources put an image
     * rendering beside their text, and a text paste stays a text paste. [flavors] lists the clipboard's flavours; one that throws (a
     * clipboard another application holds) leaves the key to the text area.
     */
    fun clipboardHasImage(flavors: () -> List<DataFlavor>): Boolean = try {
        val offered = flavors()
        DataFlavor.javaFileListFlavor in offered || (DataFlavor.imageFlavor in offered && DataFlavor.stringFlavor !in offered)
    } catch (e: Exception) {
        LOG.debug("clipboard flavours not readable: ${e.javaClass.name}")
        false
    }

    /**
     * The images on the clipboard, read on [Dispatchers.IO]: the files of a file list that holds at least one image file (all of
     * them, so that one that is not an image gets its notice), else a raw bitmap encoded as PNG once with [encodePng]. Empty for a
     * file list without an image file, or anything else, so that the caller pastes the clipboard's text ([clipboardText]).
     */
    suspend fun readClipboardImages(
        contents: () -> Transferable? = { CopyPasteManager.getInstance().contents },
        encodePng: (Image) -> ByteArray = ::pngOf,
    ): List<Pair<String, ByteArray?>> = withContext(Dispatchers.IO) {
        val transferable = readOrNull { contents() } ?: return@withContext emptyList()
        if (readOrFalse { transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }) {
            val files = filesOf(transferable)
            return@withContext if (files.any { it.isImageFile() }) readFiles(files) else emptyList()
        }
        val image = readOrNull { transferable.getTransferData(DataFlavor.imageFlavor) as? Image } ?: return@withContext emptyList()
        listOf(PASTED_NAME to readOrNull { encodePng(image) })
    }

    /** The clipboard's text, read on [Dispatchers.IO]; null when it has none or cannot be read. */
    suspend fun clipboardText(contents: () -> Transferable? = { CopyPasteManager.getInstance().contents }): String? = withContext(Dispatchers.IO) {
        readOrNull { contents()?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }?.getTransferData(DataFlavor.stringFlavor) as? String }
    }

    /**
     * The bytes of each of [paths], read on [Dispatchers.IO], by file name; null bytes for a file that cannot be read, which the
     * composer turns into its notice. At most one byte more than [Attachments.MAX_BYTES] is read: that is enough to refuse it.
     */
    suspend fun readFiles(paths: List<Path>): List<Pair<String, ByteArray?>> = withContext(Dispatchers.IO) {
        paths.map { path ->
            val name = path.fileName?.toString() ?: path.toString()
            val bytes = try {
                Files.newInputStream(path).use { it.readNBytes(Attachments.MAX_BYTES + 1) }
            } catch (e: IOException) {
                LOG.debug("image file not readable: ${e.javaClass.name}")
                null
            } catch (e: SecurityException) {
                LOG.debug("image file not readable: ${e.javaClass.name}")
                null
            }
            name to bytes
        }
    }

    /** The images the user picks in the IDE's file chooser, which is modal: call it on the EDT. */
    fun chooseImages(project: Project): List<Path> = FileChooser.chooseFiles(descriptor(), project, null).map { it.toNioPath() }

    /** The file chooser's descriptor: several files, the image types of [Attachments.EXTENSIONS] only. */
    fun descriptor(): FileChooserDescriptor =
        FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().withExtensionFilter("Images", *Attachments.EXTENSIONS.toTypedArray())

    /** The paths of the files [transferable] carries as a file list, in order; none for anything else, or a list that cannot be read. */
    fun droppedPaths(transferable: Transferable): List<Path> =
        if (readOrFalse { transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }) filesOf(transferable) else emptyList()

    private fun filesOf(transferable: Transferable): List<Path> =
        readOrNull { (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>()?.map { it.toPath() } }
            ?: emptyList()

    private fun Path.isImageFile(): Boolean = fileName?.toString()?.substringAfterLast('.', "")?.lowercase() in Attachments.EXTENSIONS

    /** [read], or null when it throws: the clipboard and a drag source are other applications, which may fail or go away. */
    private inline fun <T> readOrNull(read: () -> T?): T? = try {
        read()
    } catch (e: Exception) {
        LOG.debug("transfer data not readable: ${e.javaClass.name}")
        null
    }

    private inline fun readOrFalse(read: () -> Boolean): Boolean = readOrNull(read) ?: false
}

/**
 * [image] as PNG bytes: a pasted bitmap, the one image the composer encodes itself (polish spec 2.7). A multi-resolution image gives
 * its largest variant. Throws when the image has no size yet or cannot be written.
 */
internal fun pngOf(image: Image): ByteArray {
    val source = (image as? MultiResolutionImage)?.resolutionVariants?.maxByOrNull { it.getWidth(null).toLong() * it.getHeight(null) } ?: image
    val buffered = source as? BufferedImage ?: run {
        val width = source.getWidth(null)
        val height = source.getHeight(null)
        require(width > 0 && height > 0) { "image has no size" }
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { copy ->
            val graphics = copy.createGraphics()
            try {
                graphics.drawImage(source, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }
    }
    val out = ByteArrayOutputStream()
    check(ImageIO.write(buffered, "png", out)) { "no PNG writer" }
    return out.toByteArray()
}
