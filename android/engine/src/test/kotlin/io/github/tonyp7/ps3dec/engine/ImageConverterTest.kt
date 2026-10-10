package io.github.tonyp7.ps3dec.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

internal fun sha256(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

internal fun convert(
    data: ByteArray,
    mode: Mode,
    d1: ByteArray? = SyntheticImage.DEFAULT_D1,
    size: Long = data.size.toLong(),
    callbacks: ConversionCallbacks = ConversionCallbacks(),
    parallelism: Int = ImageConverter.defaultParallelism(),
    discKey: ByteArray? = null,
): ByteArray {
    val out = ByteArrayOutputStream()
    ImageConverter.convert(
        Channels.newChannel(ByteArrayInputStream(data)),
        size,
        Channels.newChannel(out),
        mode,
        d1,
        callbacks,
        parallelism,
        discKey,
    )
    return out.toByteArray()
}

class ImageConverterTest {
    /**
     * Known answers: images from [SyntheticImage] run through the independent Python implementation
     * in `backend/tests/synth.py` (python-cryptography). Each case also pins the input hash, so a
     * drift in the generator is reported as such and not as a wrong conversion.
     */
    private class Known(
        val name: String,
        val regions: List<Int>,
        val seed: Long,
        val marker: ByteArray?,
        val mode: Mode,
        val inputSha: String,
        val outputSha: String,
    )

    private val known = listOf(
        Known(
            "standard encrypt", listOf(4, 6, 4), 1, null, Mode.ENCRYPT,
            "3cd7c5202dc13698480547e2d848888f0658d3310833154e1266146fc906a60e",
            "44ebbeb65cb2f1f94f023cb6a2386f5a63e09e7ee9eef8d7a5c41287be24fc6a",
        ),
        Known(
            "standard decrypt", listOf(4, 6, 4), 2, null, Mode.DECRYPT,
            "56eaf96514f6d5b8e054c3daf15f6c0818324bdb2b3bcd768b19dda13eb19c60",
            "de6d9a013d9a7d1aceb979401aa7c50d1ab62c06218a85c78a3d575578cf02f7",
        ),
        Known(
            "3k3y encrypt", listOf(3, 1, 2, 5, 4), 3, SyntheticImage.DECRYPTED_MARKER, Mode.ENCRYPT,
            "8da82c6181f57fd7f74c149070906156d2eeb72c64ec89c0d9cedff5136262bd",
            "e818df2f1145fc44ec9ad13518799c483008fce185cab425d09b27dee7172bce",
        ),
        Known(
            "3k3y decrypt", listOf(2, 3, 2), 4, SyntheticImage.ENCRYPTED_MARKER, Mode.DECRYPT,
            "a4ec182c342b2d358c0bd3062c1313dd11608b9f584c5a7c2907a50dc5bcbdf7",
            "75f669a93052c412a01e7d9c4f906265b92574ceea0a892251d7f13c6c1b2d4c",
        ),
        Known(
            "minimal", listOf(2, 1, 2), 5, null, Mode.ENCRYPT,
            "9637ff36e9b280a7e862a92c2b89bef90a7156567f2f199db1d66e998b10dd6e",
            "ab4ac21b0c05116ff8ef86a6df9d56f5c632fad1d71fe83dd1145e0113af6537",
        ),
        Known(
            "empty converted region", listOf(2, 0, 2), 6, null, Mode.ENCRYPT,
            "fff6f499626db7da2590fb19341c008c63355b8c1165eb90ac711aec11a019ce",
            "fff6f499626db7da2590fb19341c008c63355b8c1165eb90ac711aec11a019ce",
        ),
    )

    @Test
    fun `output matches the independent reference implementation`() {
        for (k in known) {
            val input = SyntheticImage.decrypted(k.regions, k.seed, k.marker)
            assertEquals(k.inputSha, sha256(input), "${k.name}: generator drifted")
            assertEquals(k.outputSha, sha256(convert(input, k.mode)), k.name)
        }
    }

    @Test
    fun `encrypt then decrypt returns the original image`() {
        val layouts = listOf(listOf(2), listOf(4, 6, 4), listOf(3, 1, 2, 5, 4), listOf(2, 2, 2, 2, 2))
        for (regions in layouts) {
            for (marker in listOf(null, SyntheticImage.DECRYPTED_MARKER)) {
                val original = SyntheticImage.decrypted(regions, seed = 11, marker = marker)
                val encrypted = convert(original, Mode.ENCRYPT)
                val back = convert(encrypted, Mode.DECRYPT)
                assertContentEquals(original, back, "regions=$regions marker=${marker?.decodeToString()}")
            }
        }
    }

    @Test
    fun `plain regions are copied and converted regions change`() {
        val regions = listOf(4, 6, 4)
        val original = SyntheticImage.decrypted(regions, seed = 3)
        val out = convert(original, Mode.ENCRYPT)
        assertEquals(original.size, out.size)
        val plainA = 0 until 4 * SECTOR_SIZE
        val converted = 4 * SECTOR_SIZE until 10 * SECTOR_SIZE
        val plainB = 10 * SECTOR_SIZE until 14 * SECTOR_SIZE
        assertContentEquals(original.copyOfRange(plainA.first, plainA.last + 1), out.copyOfRange(plainA.first, plainA.last + 1))
        assertContentEquals(original.copyOfRange(plainB.first, plainB.last + 1), out.copyOfRange(plainB.first, plainB.last + 1))
        assertNotEquals(
            original.copyOfRange(converted.first, converted.last + 1).toList(),
            out.copyOfRange(converted.first, converted.last + 1).toList(),
        )
    }

    @Test
    fun `3k3y images are stamped with their new state and keep the rest of the header`() {
        val original = SyntheticImage.decrypted(listOf(4, 6, 4), seed = 8, marker = SyntheticImage.DECRYPTED_MARKER)
        val encrypted = convert(original, Mode.ENCRYPT, d1 = null)
        assertEquals("Encrypted 3K ISO", encrypted.copyOfRange(MARKER_OFFSET, MARKER_OFFSET + 16).decodeToString())
        val rest = HEADER_SIZE
        for (i in 0 until rest) {
            if (i in MARKER_OFFSET until MARKER_OFFSET + 16) continue
            assertEquals(original[i], encrypted[i], "header byte $i")
        }
        val decrypted = convert(encrypted, Mode.DECRYPT, d1 = null)
        assertEquals("Decrypted 3K ISO", decrypted.copyOfRange(MARKER_OFFSET, MARKER_OFFSET + 16).decodeToString())
    }

    @Test
    fun `a key supplied for a 3k3y image is ignored`() {
        val image = SyntheticImage.decrypted(listOf(4, 6, 4), seed = 9, marker = SyntheticImage.DECRYPTED_MARKER)
        val other = ByteArray(16) { 0x5A }
        assertContentEquals(convert(image, Mode.ENCRYPT, d1 = null), convert(image, Mode.ENCRYPT, d1 = other))
    }

    @Test
    fun `the key matters for standard images`() {
        val image = SyntheticImage.decrypted(listOf(4, 6, 4), seed = 9)
        val other = ByteArray(16) { 0x5A }
        assertNotEquals(
            sha256(convert(image, Mode.ENCRYPT, d1 = SyntheticImage.DEFAULT_D1)),
            sha256(convert(image, Mode.ENCRYPT, d1 = other)),
        )
    }

    @Test
    fun `a standard image without a key is refused`() {
        val e = assertFailsWith<ConversionException> {
            convert(SyntheticImage.decrypted(), Mode.DECRYPT, d1 = null)
        }
        assertContains(e.message!!, "key is required")
    }

    @Test
    fun `3k3y mode conflicts are refused`() {
        val decrypted = SyntheticImage.decrypted(marker = SyntheticImage.DECRYPTED_MARKER)
        val encrypted = SyntheticImage.decrypted(marker = SyntheticImage.ENCRYPTED_MARKER)
        assertContains(
            assertFailsWith<ConversionException> { convert(decrypted, Mode.DECRYPT) }.message!!,
            "already decrypted",
        )
        assertContains(
            assertFailsWith<ConversionException> { convert(encrypted, Mode.ENCRYPT) }.message!!,
            "already encrypted",
        )
    }

    @Test
    fun `diagnostics name each region and never contain the key`() {
        val lines = ArrayList<String>()
        convert(
            SyntheticImage.decrypted(listOf(4, 6, 4), seed = 1),
            Mode.DECRYPT,
            callbacks = ConversionCallbacks(onDiagnostic = { lines += it }),
        )
        assertEquals(
            listOf(
                "   Copying sectors        0 to        3",
                "Decrypting sectors        4 to        9",
                "   Copying sectors       10 to       13",
            ),
            lines,
        )
        val hex = SyntheticImage.DEFAULT_D1.joinToString("") { "%02x".format(it) }
        assertFalse(lines.any { it.contains(hex, ignoreCase = true) })
    }

    @Test
    fun `progress reports bytes written and ends at the image size`() {
        val seen = ArrayList<Long>()
        val image = SyntheticImage.decrypted(listOf(4, 6, 4))
        convert(image, Mode.ENCRYPT, callbacks = ConversionCallbacks(onProgress = { seen += it }))
        assertEquals(image.size.toLong(), seen.last())
        assertEquals(seen.sorted(), seen)
    }
}

class DiscKeyTest {
    @Test
    fun `a disc key is used as given and a D1 is derived first`() {
        val image = SyntheticImage.decrypted(listOf(4, 6, 4), seed = 9)
        val d1 = SyntheticImage.DEFAULT_D1
        val derived = DiscCrypto.discKeyFor(d1)
        // a D1 and the disc key derived from it produce the same image
        assertContentEquals(
            convert(image, Mode.ENCRYPT, d1 = d1),
            convert(image, Mode.ENCRYPT, d1 = null, discKey = derived),
        )
        // the same 16 bytes passed as a disc key are not derived again
        assertNotEquals(
            convert(image, Mode.ENCRYPT, d1 = d1).toList(),
            convert(image, Mode.ENCRYPT, d1 = null, discKey = d1).toList(),
        )
    }
}
