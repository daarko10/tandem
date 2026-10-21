package dev.daarkolabs.tandem.chat

/**
 * The checks an image goes through before the composer attaches it (polish spec 2.7): its type by its first bytes, its size once
 * encoded, how many a message holds and how much they weigh together. Pure, so the composer runs it wherever the bytes are.
 *
 * The API documents its per-image limit on the base64 data, 5 MB on Amazon Bedrock and Google Cloud and more on the Anthropic API
 * directly (open item 14); the CLI may use any of them, so a file is refused once its base64 would pass 5,242,880 bytes.
 */
object Attachments {
    /** The largest file whose base64 stays within 5,242,880 bytes. */
    const val MAX_BYTES: Int = 3_932_160

    /** Images one message can carry. */
    const val MAX_COUNT: Int = 100

    /** The encoded images of one message, at most: under the API's 32 MB request, with room for the text. */
    const val MAX_TOTAL_ENCODED: Long = 30_000_000

    /** The file extensions the file chooser offers, and that make a pasted file list an image paste. */
    val EXTENSIONS: List<String> = listOf("png", "jpg", "jpeg", "gif", "webp")

    /** How long the base64 of [rawBytes] bytes is, padding included. */
    fun encodedSize(rawBytes: Int): Long = 4L * ((rawBytes + 2) / 3)

    /** The media type [bytes] start with: PNG, JPEG, GIF or WebP; null for anything else. */
    fun mediaType(bytes: ByteArray): String? = when {
        bytes.startsWith(PNG) -> "image/png"
        bytes.startsWith(JPEG) -> "image/jpeg"
        bytes.startsWith(GIF87A) || bytes.startsWith(GIF89A) -> "image/gif"
        bytes.startsWith(RIFF) && bytes.startsWith(WEBP, at = 8) -> "image/webp"
        else -> null
    }

    /**
     * Why the image [name] ([bytes]) cannot be attached to a message that already holds [count] images of [encodedTotal] encoded
     * bytes, as the notice the composer shows; null when it can.
     */
    fun check(name: String, bytes: ByteArray, count: Int, encodedTotal: Long): String? = when {
        mediaType(bytes) == null -> "$name is not a PNG, JPEG, GIF or WebP image"
        bytes.size > MAX_BYTES -> "$name is larger than 5 MB once encoded and was not attached"
        count >= MAX_COUNT -> "Only 100 images can be attached to one message"
        encodedTotal + encodedSize(bytes.size) > MAX_TOTAL_ENCODED -> "$name was not attached: one message can carry 30 MB of images"
        else -> null
    }

    /** What the image at [index] (0-based) of a message is called on screen. */
    fun label(index: Int): String = "[Image #${index + 1}]"

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val GIF87A = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF89A = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

    private fun ByteArray.startsWith(prefix: ByteArray, at: Int = 0): Boolean =
        size >= at + prefix.size && prefix.indices.all { this[at + it] == prefix[it] }
}
