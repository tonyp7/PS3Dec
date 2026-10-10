package io.github.tonyp7.ps3dec.engine

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Malformed input, failure and cancellation behavior of [ImageConverter]. */
class ImageConverterSafetyTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `an image shorter than its table claims stops with an early-end error`() {
        val image = SyntheticImage.decrypted(listOf(4, 6, 4))
        val truncated = image.copyOf(image.size - SECTOR_SIZE)
        val e = assertFailsWith<ConversionException> {
            convert(truncated, Mode.ENCRYPT, size = image.size.toLong())
        }
        assertContains(e.message!!, "ended early")
    }

    @Test
    fun `an input too short for even the header is refused`() {
        val e = assertFailsWith<ConversionException> { convert(ByteArray(100), Mode.DECRYPT, size = 100) }
        assertContains(e.message!!, "ended early")
    }

    @Test
    fun `a table that is not increasing is refused before any output`() {
        val image = SyntheticImage.decrypted(listOf(4, 6, 4))
        SyntheticImage.putU32(image, 16, 3)
        val written = ArrayList<Long>()
        val e = assertFailsWith<ConversionException> {
            convert(image, Mode.ENCRYPT, callbacks = ConversionCallbacks(onProgress = { written += it }))
        }
        assertContains(e.message!!, "increasing")
        assertTrue(written.isEmpty())
    }

    @Test
    fun `a first region that does not cover the header is refused`() {
        val image = SyntheticImage.decrypted(listOf(4, 6, 4))
        SyntheticImage.putU32(image, TABLE_OFFSET, 0)
        val e = assertFailsWith<ConversionException> { convert(image, Mode.ENCRYPT) }
        assertContains(e.message!!, "first region")
    }

    @Test
    fun `an image whose size disagrees with its table is refused`() {
        val image = SyntheticImage.decrypted(listOf(4, 6, 4))
        val e = assertFailsWith<ConversionException> {
            convert(image, Mode.DECRYPT, size = image.size + SECTOR_SIZE.toLong())
        }
        assertContains(e.message!!, "region table")
    }

    @Test
    fun `corrupted images only ever produce conversion errors`() {
        val rng = SplitMix64(1234)
        val markers = byteArrayOf(0, 'E'.code.toByte(), 'e'.code.toByte(), 'D'.code.toByte(), 'd'.code.toByte())
        var refused = 0
        var converted = 0
        repeat(400) { round ->
            val layout = listOf(2 + round % 5, round % 4, 2 + round % 3)
            val image = SyntheticImage.decrypted(layout, seed = round.toLong())
            val noise = rng.bytes(8)
            var shrink = 0
            when (round % 4) {
                0 -> image[noise[0].toInt() and 3] = noise[1] // region count
                1 -> image[TABLE_OFFSET + (noise[0].toInt() and 15)] = noise[1] // table entries
                2 -> image[MARKER_OFFSET] = markers[(noise[0].toInt() and 0xFF) % markers.size] // marker state
                else -> shrink = SECTOR_SIZE * (1 + (noise[0].toInt() and 1)) // input ends early
            }
            // Bytes inside the plain first region are harmless to the structure.
            image[HEADER_SIZE - 1 - (noise[2].toInt() and 63)] = noise[3]
            try {
                convert(image.copyOf(image.size - shrink), Mode.ENCRYPT, size = image.size.toLong())
                converted++
            } catch (e: ConversionException) {
                refused++
            }
        }
        assertTrue(refused > 0, "no corrupted image was refused")
        assertTrue(converted > 0, "no image survived corruption; the test is not reaching conversion")
    }

    @Test
    fun `random bytes of every plausible size are refused cleanly`() {
        val rng = SplitMix64(99)
        for (n in listOf(0, 1, 2047, 2048, 4095, 4096, 4097, 8192, 20_000)) {
            val data = rng.bytes(n)
            assertFailsWith<ConversionException>("size $n") {
                convert(data, Mode.DECRYPT, size = n.toLong())
            }
        }
    }

    @Test
    fun `cancelling during a large region stops it within one buffer`() {
        val image = SyntheticImage.decrypted(listOf(2, 3 * ImageConverter.BUFFER_SECTORS + 5, 2), seed = 4)
        val bufferBytes = ImageConverter.BUFFER_SECTORS.toLong() * SECTOR_SIZE
        var progress = 0L
        var cancel = false
        assertFailsWith<ConversionCancelledException> {
            convert(
                image,
                Mode.ENCRYPT,
                callbacks = ConversionCallbacks(
                    onProgress = {
                        progress = it
                        // Cancel once the first buffer of the large region has been written.
                        if (it > HEADER_SIZE) cancel = true
                    },
                    isCancelled = { cancel },
                ),
            )
        }
        // Cancelled once one buffer of the region was written: the pipeline holds at most a few
        // buffers in flight, so the rest of the region must not have been processed.
        assertTrue(progress >= HEADER_SIZE + bufferBytes, "progress $progress")
        assertTrue(progress < image.size, "progress $progress of ${image.size}")
    }

    @Test
    fun `an output that fails mid-write is reported as an I-O error`() {
        val image = SyntheticImage.decrypted(listOf(4, 6, 4))
        val failing = object : WritableByteChannel {
            override fun write(src: ByteBuffer): Int = throw IOException("No space left on device")
            override fun isOpen() = true
            override fun close() {}
        }
        val e = assertFailsWith<ConversionException> {
            ImageConverter.convert(
                Channels.newChannel(ByteArrayInputStream(image)),
                image.size.toLong(),
                failing,
                Mode.ENCRYPT,
                SyntheticImage.DEFAULT_D1,
            )
        }
        assertContains(e.message!!, "No space left")
        assertTrue(e.cause is IOException)
    }

    @Test
    fun `no failure message or diagnostic line contains key material`() {
        val d1 = SyntheticImage.DEFAULT_D1
        val discKey = DiscCrypto.discKeyFor(d1)
        val image = SyntheticImage.decrypted(listOf(4, 6, 4))
        val messages = ArrayList<String>()
        val lines = ArrayList<String>()
        val callbacks = ConversionCallbacks(onDiagnostic = { lines += it })

        // Several failures with the key in play, plus one success for its diagnostics.
        val failures: List<() -> Unit> = listOf(
            { convert(image.copyOf(image.size - SECTOR_SIZE), Mode.ENCRYPT, d1, image.size.toLong(), callbacks) },
            { convert(ByteArray(10), Mode.ENCRYPT, d1, 10, callbacks) },
            { convert(SyntheticImage.decrypted(marker = SyntheticImage.ENCRYPTED_MARKER), Mode.ENCRYPT, d1, callbacks = callbacks) },
        )
        for (f in failures) {
            try {
                f()
            } catch (e: ConversionException) {
                messages += e.message!!
            }
        }
        convert(image, Mode.ENCRYPT, d1, callbacks = callbacks)

        val needles = listOf(hex(d1), hex(discKey), hex(d1).uppercase(), hex(discKey).uppercase())
        for (text in messages + lines) {
            for (needle in needles) assertFalse(text.contains(needle), "key material in: $text")
        }
        assertTrue(messages.size == failures.size)
    }
}
