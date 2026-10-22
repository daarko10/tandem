package dev.daarkolabs.tandem.chat

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The checks an image goes through before it is attached (polish spec 2.7). */
class AttachmentsTest {
    private val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private val webpHeader = "RIFF".toByteArray() + byteArrayOf(0x24, 0, 0, 0) + "WEBPVP8 ".toByteArray()

    /** A PNG header followed by zeros, [size] bytes in all. */
    private fun png(size: Int = 64): ByteArray = pngHeader.copyOf(size)

    @Test
    fun `media type by magic bytes`() {
        assertEquals("image/png", Attachments.mediaType(png()))
        assertEquals("image/jpeg", Attachments.mediaType(jpegHeader))
        assertEquals("image/gif", Attachments.mediaType("GIF87a....".toByteArray()))
        assertEquals("image/gif", Attachments.mediaType("GIF89a....".toByteArray()))
        assertEquals("image/webp", Attachments.mediaType(webpHeader))
        assertNull(Attachments.mediaType("hello".toByteArray()))
        assertNull(Attachments.mediaType(ByteArray(0)))
        // A RIFF file that is not WebP (a WAV) and a truncated PNG header are not images.
        assertNull(Attachments.mediaType("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray()))
        assertNull(Attachments.mediaType(pngHeader.copyOf(4)))
    }

    @Test
    fun `a file over 3,932,160 bytes, the 101st image, a 30 MB total and a non-image are refused with their notices`() {
        assertEquals("big.png is larger than 5 MB once encoded and was not attached", Attachments.check("big.png", png(3_932_161), 0, 0))
        assertNull(Attachments.check("edge.png", png(3_932_160), 0, 0))
        assertEquals("Only 100 images can be attached to one message", Attachments.check("a.png", png(), 100, 0))
        assertEquals(
            "a.png was not attached: one message can carry 30 MB of images",
            Attachments.check("a.png", png(), 0, 30_000_000 - 10),
        )
        assertEquals("a.txt is not a PNG, JPEG, GIF or WebP image", Attachments.check("a.txt", "x".toByteArray(), 0, 0))
        assertNull(Attachments.check("a.png", png(), 0, 0))
        // The type is checked first: a large text file is refused as a non-image.
        assertEquals("big.txt is not a PNG, JPEG, GIF or WebP image", Attachments.check("big.txt", ByteArray(4_000_000), 0, 0))
        assertEquals(5_242_880L, Attachments.encodedSize(3_932_160))
        assertEquals(4L, Attachments.encodedSize(1))
        assertEquals(0L, Attachments.encodedSize(0))
        assertEquals("[Image #1]", Attachments.label(0))
        assertEquals("[Image #12]", Attachments.label(11))
    }
}
