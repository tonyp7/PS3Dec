package io.github.tonyp7.ps3dec.engine

enum class ImageKind(val label: String) {
    THREE_K3Y_ENCRYPTED("3k3y-encrypted"),
    THREE_K3Y_DECRYPTED("3k3y-decrypted"),
    STANDARD("standard"),
    INVALID("invalid"),
}

/**
 * What an image is and whether it can be converted.
 *
 * @property size length of the image in bytes
 * @property reason why the image is invalid, null otherwise
 */
data class ImageInfo(val kind: ImageKind, val size: Long, val reason: String? = null) {
    val valid: Boolean get() = kind != ImageKind.INVALID

    companion object {
        fun invalid(size: Long, reason: String) = ImageInfo(ImageKind.INVALID, size, reason)
    }
}

/** Classification rules of the `iso-catalog` spec, over a header and the total file size. */
object ImageClassifier {
    /**
     * Classify an image given its total [size] and the first bytes of the file ([header], normally
     * [HEADER_SIZE] bytes, shorter only when the file itself is shorter).
     */
    fun classify(size: Long, header: ByteArray): ImageInfo {
        if (size < HEADER_SIZE) return ImageInfo.invalid(size, "file is smaller than $HEADER_SIZE bytes")
        if (header.size < HEADER_SIZE) return ImageInfo.invalid(size, "file changed while reading")

        val marker = header[MARKER_OFFSET].toInt() and 0xFF
        val kind = when (marker) {
            0 -> ImageKind.STANDARD
            'E'.code, 'e'.code -> ImageKind.THREE_K3Y_ENCRYPTED
            'D'.code, 'd'.code -> ImageKind.THREE_K3Y_DECRYPTED
            else -> return ImageInfo.invalid(
                size,
                "unrecognised 3k3y marker byte 0x${marker.toString(16).padStart(2, '0')}",
            )
        }

        regionTableProblem(header, size)?.let { return ImageInfo.invalid(size, it) }
        return ImageInfo(kind, size)
    }

    /** Why the region table is not consistent with a file of [size] bytes, or null if it is. */
    fun regionTableProblem(header: ByteArray, size: Long): String? {
        val count = header.u32(0)
        if (count < 1) return "region table is empty (not a PS3 disc image?)"
        val entries = 2 * count - 1
        if (TABLE_OFFSET + 4 * entries > header.size) {
            return "region table claims $count region pairs, which does not fit the header " +
                "(not a PS3 disc image?)"
        }
        val table = LongArray(entries.toInt()) { header.u32(TABLE_OFFSET + 4 * it) }
        for (i in 1 until table.size) {
            if (table[i] <= table[i - 1]) return "region table end sectors are not increasing"
        }
        val expected = (table.last() + 1) * SECTOR_SIZE
        if (expected != size) return "region table describes $expected bytes but the file is $size bytes"
        return null
    }
}
