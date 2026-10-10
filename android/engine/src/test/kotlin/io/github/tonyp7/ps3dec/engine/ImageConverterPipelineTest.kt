package io.github.tonyp7.ps3dec.engine

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Threading behavior of the buffered pipeline: independence from pool size, failure and cleanup. */
class ImageConverterPipelineTest {
    private fun engineThreads() =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("ps3dec-") }

    private fun assertNoEngineThreads() {
        // Threads are joined (bounded) before convert returns; allow a moment for the last to exit.
        val deadline = System.nanoTime() + 2_000_000_000L
        while (engineThreads().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(emptyList(), engineThreads().map { it.name })
    }

    private val multiBuffer = SyntheticImage.decrypted(
        listOf(3, 2 * ImageConverter.BUFFER_SECTORS + 17, 5),
        seed = 21,
    )

    @Test
    fun `output is identical for every pool size`() {
        val reference = convert(multiBuffer, Mode.ENCRYPT, parallelism = 1)
        for (pool in listOf(2, 3, 4, 8)) {
            assertContentEquals(reference, convert(multiBuffer, Mode.ENCRYPT, parallelism = pool), "pool $pool")
        }
    }

    @Test
    fun `no engine threads remain after success`() {
        convert(multiBuffer, Mode.DECRYPT, parallelism = 4)
        assertNoEngineThreads()
    }

    @Test
    fun `no engine threads remain after a failure`() {
        assertFailsWith<ConversionException> {
            convert(multiBuffer.copyOf(multiBuffer.size / 2), Mode.DECRYPT, size = multiBuffer.size.toLong(), parallelism = 4)
        }
        assertNoEngineThreads()
    }

    @Test
    fun `no engine threads remain after cancellation`() {
        var cancel = false
        assertFailsWith<ConversionCancelledException> {
            convert(
                multiBuffer,
                Mode.DECRYPT,
                callbacks = ConversionCallbacks(onProgress = { cancel = true }, isCancelled = { cancel }),
                parallelism = 4,
            )
        }
        assertNoEngineThreads()
    }

    @Test
    fun `a writer failure in the middle of the image stops the pipeline promptly`() {
        var buffers = 0
        val failing = object : WritableByteChannel {
            override fun write(src: ByteBuffer): Int {
                if (++buffers >= 2) throw IOException("disk is full")
                val n = src.remaining()
                src.position(src.limit())
                return n
            }
            override fun isOpen() = true
            override fun close() {}
        }
        val e = assertTimeoutPreemptively<ConversionException>(Duration.ofSeconds(20)) {
            assertFailsWith<ConversionException> {
                ImageConverter.convert(
                    Channels.newChannel(ByteArrayInputStream(multiBuffer)),
                    multiBuffer.size.toLong(),
                    failing,
                    Mode.ENCRYPT,
                    SyntheticImage.DEFAULT_D1,
                    parallelism = 4,
                )
            }
        }
        assertContains(e.message!!, "disk is full")
        assertNoEngineThreads()
    }

    @Test
    fun `a reader failure in the middle of the image stops the pipeline promptly`() {
        var reads = 0
        val source = ByteArrayInputStream(multiBuffer)
        val failing = object : java.nio.channels.ReadableByteChannel {
            override fun read(dst: ByteBuffer): Int {
                if (++reads > 3) throw IOException("card removed")
                val chunk = ByteArray(dst.remaining())
                val n = source.read(chunk)
                if (n <= 0) return -1
                dst.put(chunk, 0, n)
                return n
            }
            override fun isOpen() = true
            override fun close() {}
        }
        val e = assertTimeoutPreemptively<ConversionException>(Duration.ofSeconds(20)) {
            assertFailsWith<ConversionException> {
                ImageConverter.convert(
                    failing,
                    multiBuffer.size.toLong(),
                    Channels.newChannel(java.io.ByteArrayOutputStream()),
                    Mode.ENCRYPT,
                    SyntheticImage.DEFAULT_D1,
                    parallelism = 4,
                )
            }
        }
        assertContains(e.message!!, "card removed")
        assertNoEngineThreads()
    }

    @Test
    fun `progress is monotonic and ends at the image size under parallelism`() {
        val seen = java.util.Collections.synchronizedList(ArrayList<Long>())
        convert(multiBuffer, Mode.ENCRYPT, callbacks = ConversionCallbacks(onProgress = { seen += it }), parallelism = 4)
        assertEquals(multiBuffer.size.toLong(), seen.last())
        assertEquals(seen.sorted(), seen.toList())
        assertTrue(seen.size >= 3)
    }
}
