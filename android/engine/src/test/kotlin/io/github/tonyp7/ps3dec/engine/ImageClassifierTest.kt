package io.github.tonyp7.ps3dec.engine

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports of the classification cases in `backend/tests/test_catalog.py`. */
class ImageClassifierTest {
    private fun classify(data: ByteArray): ImageInfo =
        ImageClassifier.classify(data.size.toLong(), data.copyOf(minOf(data.size, HEADER_SIZE)))

    @Test
    fun `markers classify as their 3k3y state, either case, and no marker is standard`() {
        val cases = listOf(
            SyntheticImage.ENCRYPTED_MARKER to ImageKind.THREE_K3Y_ENCRYPTED,
            SyntheticImage.DECRYPTED_MARKER to ImageKind.THREE_K3Y_DECRYPTED,
            "encrypted 3K ISO".toByteArray() to ImageKind.THREE_K3Y_ENCRYPTED,
            "decrypted 3K ISO".toByteArray() to ImageKind.THREE_K3Y_DECRYPTED,
            null to ImageKind.STANDARD,
        )
        for ((marker, kind) in cases) {
            val info = classify(SyntheticImage.decrypted(marker = marker))
            assertEquals(kind, info.kind, "marker ${marker?.decodeToString()}")
            assertTrue(info.valid)
            assertNull(info.reason)
        }
    }

    @Test
    fun `an unrecognised marker byte makes the image invalid`() {
        val info = classify(SyntheticImage.decrypted(marker = "Xecrypted 3K ISO".toByteArray()))
        assertEquals(ImageKind.INVALID, info.kind)
        val reason = assertNotNull(info.reason)
        assertContains(reason, "marker")
        assertContains(reason, "0x58")
    }

    @Test
    fun `a file smaller than the header is invalid and keeps its size`() {
        val info = classify(ByteArray(100))
        assertEquals(ImageKind.INVALID, info.kind)
        assertContains(info.reason!!, "smaller")
        assertEquals(100L, info.size)
    }

    @Test
    fun `a file that shrank after its size was taken is reported as changed`() {
        val info = ImageClassifier.classify(HEADER_SIZE.toLong() * 3, ByteArray(100))
        assertContains(info.reason!!, "changed while reading")
    }

    @Test
    fun `a consistent region table is valid`() {
        assertTrue(classify(SyntheticImage.decrypted(listOf(3, 5, 7, 2, 9))).valid)
        assertTrue(classify(SyntheticImage.decrypted(listOf(2))).valid)
    }

    @Test
    fun `a size that disagrees with the table is invalid`() {
        val data = SyntheticImage.decrypted(listOf(4, 6, 4))
        val short = ImageClassifier.classify(
            data.size - SECTOR_SIZE.toLong(),
            data.copyOf(HEADER_SIZE),
        )
        val long = ImageClassifier.classify(data.size + SECTOR_SIZE.toLong(), data.copyOf(HEADER_SIZE))
        for (info in listOf(short, long)) {
            assertEquals(ImageKind.INVALID, info.kind)
            assertContains(info.reason!!, "file is")
        }
    }

    @Test
    fun `an ISO9660 image without a region table is invalid`() {
        val blob = ByteArray(20 * SECTOR_SIZE)
        "CD001".toByteArray().copyInto(blob, 16 * SECTOR_SIZE + 1)
        val info = classify(blob)
        assertEquals(ImageKind.INVALID, info.kind)
        assertContains(info.reason!!, "region table")
    }

    @Test
    fun `random data with a zero marker is invalid`() {
        val junk = SplitMix64(7).bytes(6 * SECTOR_SIZE)
        junk[MARKER_OFFSET] = 0
        assertEquals(ImageKind.INVALID, classify(junk).kind)
    }

    @Test
    fun `non-increasing end sectors are invalid`() {
        val data = SyntheticImage.decrypted(listOf(4, 6, 4))
        SyntheticImage.putU32(data, 16, 3) // second entry smaller than the first
        assertContains(classify(data).reason!!, "increasing")
    }

    @Test
    fun `a table that does not fit the header is invalid`() {
        val data = SyntheticImage.decrypted(listOf(4, 6, 4))
        SyntheticImage.putU32(data, 0, 5000)
        assertContains(classify(data).reason!!, "fit")
    }

    @Test
    fun `an empty region table is invalid`() {
        val data = SyntheticImage.decrypted(listOf(4, 6, 4))
        SyntheticImage.putU32(data, 0, 0)
        assertContains(classify(data).reason!!, "empty")
    }

    @Test
    fun `a huge region count cannot overflow the fit check`() {
        val data = SyntheticImage.decrypted(listOf(4, 6, 4))
        SyntheticImage.putU32(data, 0, 0xFFFFFFFFL)
        assertContains(classify(data).reason!!, "fit")
    }
}
