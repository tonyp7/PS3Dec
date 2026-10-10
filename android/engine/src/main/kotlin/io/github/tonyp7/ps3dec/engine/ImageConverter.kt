package io.github.tonyp7.ps3dec.engine

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.security.GeneralSecurityException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The conversion cannot be carried out, with a reason that can be shown to the user. */
class ConversionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The caller asked for the conversion to stop. */
class ConversionCancelledException : Exception("conversion cancelled")

/**
 * Hooks into a running conversion.
 *
 * [onDiagnostic] and [isCancelled] are called on the thread that called
 * [ImageConverter.convert]; [onProgress] is called on the engine's writer thread.
 *
 * @property onDiagnostic one line per region, in the style of the reference tool; never contains key material
 * @property onProgress total bytes written to the output so far
 * @property isCancelled polled at least every few milliseconds; returning true stops the conversion
 */
class ConversionCallbacks(
    val onDiagnostic: (String) -> Unit = {},
    val onProgress: (bytesWritten: Long) -> Unit = {},
    val isCancelled: () -> Boolean = { false },
)

/**
 * Decrypts or encrypts a PS3 disc image with output identical to the reference PS3Dec tool.
 *
 * Layout: the header (sectors 0 and 1) holds the region table. Regions alternate plain, converted,
 * plain, ...; a plain region is copied through and ends at its table entry inclusive, a converted
 * region stops one sector before its entry. Converted sectors are AES-128-CBC, one IV per sector.
 *
 * Work is pipelined over buffers of [BUFFER_SECTORS] sectors: a reader thread fills a buffer, the
 * calling thread converts it (fanning its sectors out over a pool, since sectors are independent),
 * and a writer thread drains it. The output does not depend on the pool size.
 */
object ImageConverter {
    /** Sectors handled per buffer: 4096 sectors, 8 MiB. */
    const val BUFFER_SECTORS = 4096

    private const val BUFFERS = 3
    private const val POLL_MS = 20L
    private const val JOIN_MS = 2_000L

    private val ENCRYPTED_MARKER = "Encrypted 3K ISO".toByteArray(Charsets.US_ASCII)
    private val DECRYPTED_MARKER = "Decrypted 3K ISO".toByteArray(Charsets.US_ASCII)

    /**
     * Conversion workers used when the caller does not choose: the cores available, up to 4.
     * On the reference device four workers already convert faster than its storage can write,
     * and eight were no faster (see `android/README.md`, Performance).
     */
    fun defaultParallelism(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /**
     * Convert [size] bytes from [input] to [output]. The channels are not closed.
     *
     * @param d1 a 16-byte D1 value, from which the disc key is derived; used for standard images
     *   when [discKey] is null, and ignored for 3k3y images, which carry their own
     * @param parallelism number of threads converting sectors
     * @param discKey the 16-byte disc key itself (what a redump `.dkey` file holds, the reference
     *   tool's `key` type); takes precedence over [d1] for standard images
     * @throws ConversionException if the image or key cannot be used, or an I/O error occurs
     * @throws ConversionCancelledException if [ConversionCallbacks.isCancelled] became true
     */
    fun convert(
        input: ReadableByteChannel,
        size: Long,
        output: WritableByteChannel,
        mode: Mode,
        d1: ByteArray?,
        callbacks: ConversionCallbacks = ConversionCallbacks(),
        parallelism: Int = defaultParallelism(),
        discKey: ByteArray? = null,
    ) {
        require(parallelism >= 1) { "parallelism must be at least 1" }
        try {
            run(input, size, output, mode, d1, discKey, callbacks, parallelism)
        } catch (e: IOException) {
            throw ConversionException("I/O error: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    // -- set-up and validation, on the calling thread -------------------------------------------

    private fun run(
        input: ReadableByteChannel,
        size: Long,
        output: WritableByteChannel,
        mode: Mode,
        d1: ByteArray?,
        givenDiscKey: ByteArray?,
        callbacks: ConversionCallbacks,
        parallelism: Int,
    ) {
        val header = ByteArray(HEADER_SIZE)
        readFully(input, header, 0, HEADER_SIZE, 0)

        val info = ImageClassifier.classify(size, header)
        if (!info.valid) throw ConversionException("the image cannot be used: ${info.reason}")

        val threeK3y = info.kind != ImageKind.STANDARD
        if (info.kind == ImageKind.THREE_K3Y_ENCRYPTED && mode == Mode.ENCRYPT) {
            throw ConversionException("the image is a 3k3y image that is already encrypted")
        }
        if (info.kind == ImageKind.THREE_K3Y_DECRYPTED && mode == Mode.DECRYPT) {
            throw ConversionException("the image is a 3k3y image that is already decrypted")
        }
        val keyBytes = when {
            threeK3y -> header.copyOfRange(D1_OFFSET, D1_OFFSET + DiscCrypto.KEY_SIZE)
            givenDiscKey != null -> givenDiscKey
            else -> d1 ?: throw ConversionException("a key is required for this image")
        }
        if (keyBytes.size != DiscCrypto.KEY_SIZE) throw ConversionException("the key must be ${DiscCrypto.KEY_SIZE} bytes")

        val count = header.u32(0)
        val table = LongArray((2 * count - 1).toInt()) { header.u32(TABLE_OFFSET + 4 * it) }
        if (table[0] < 1) throw ConversionException("the first region must cover the 2-sector header")

        val discKey = if (!threeK3y && givenDiscKey != null) keyBytes else DiscCrypto.discKeyFor(keyBytes)
        val stamp = if (threeK3y) (if (mode == Mode.ENCRYPT) ENCRYPTED_MARKER else DECRYPTED_MARKER) else null
        pipeline(input, output, header, plan(table, mode), mode, discKey, stamp, callbacks, parallelism)
    }

    // -- work plan ------------------------------------------------------------------------------

    /** A run of sectors within one region; [sectors] is 0 for an empty region (diagnostic only). */
    private class Step(val firstSector: Long, val sectors: Int, val converted: Boolean, val diagnostic: String?)

    private fun plan(table: LongArray, mode: Mode): Sequence<Step> = sequence {
        var cursor = 0L
        for (i in table.indices) {
            val converted = i % 2 == 1
            val last = table[i] - if (converted) 1 else 0
            val action = if (!converted) "   Copying" else if (mode == Mode.DECRYPT) "Decrypting" else "Encrypting"
            var diagnostic: String? = "%s sectors %8d to %8d".format(action, cursor, last)
            if (cursor > last) yield(Step(cursor, 0, converted, diagnostic))
            while (cursor <= last) {
                val sectors = minOf(BUFFER_SECTORS.toLong(), last - cursor + 1).toInt()
                yield(Step(cursor, sectors, converted, diagnostic))
                diagnostic = null
                cursor += sectors
            }
        }
    }

    // -- the pipeline ---------------------------------------------------------------------------

    /** A step with the buffer holding its data (null for diagnostic-only steps). */
    private class Chunk(val step: Step?, val buffer: ByteArray?)

    private val END = Chunk(null, null)

    /** Control flow only: another stage failed or the pipeline is being torn down. */
    private object Abort : RuntimeException(null, null, false, false)

    private fun pipeline(
        input: ReadableByteChannel,
        output: WritableByteChannel,
        header: ByteArray,
        steps: Sequence<Step>,
        mode: Mode,
        discKey: ByteArray,
        stamp: ByteArray?,
        callbacks: ConversionCallbacks,
        parallelism: Int,
    ) {
        val failure = AtomicReference<Throwable?>()
        val stop = AtomicBoolean(false)
        val free: BlockingQueue<ByteArray> = LinkedBlockingQueue()
        val toProcess: BlockingQueue<Chunk> = LinkedBlockingQueue()
        val toWrite: BlockingQueue<Chunk> = LinkedBlockingQueue()
        repeat(BUFFERS) { free.add(ByteArray(BUFFER_SECTORS * SECTOR_SIZE)) }

        fun abortIfFailed() {
            if (stop.get() || failure.get() != null) throw Abort
        }

        // Waits that notice another stage failing (or the caller cancelling) instead of blocking forever.
        // The queues are unbounded; memory is bounded by the fixed set of buffers circulating through `free`.
        fun <T> BlockingQueue<T>.takeChecked(between: () -> Unit = {}): T {
            while (true) {
                poll(POLL_MS, TimeUnit.MILLISECONDS)?.let { return it }
                abortIfFailed()
                between()
            }
        }

        val crypto = CryptoPool(discKey, mode, parallelism)

        val reader = Thread({
            try {
                for (step in steps) {
                    if (step.sectors == 0) {
                        toProcess.add(Chunk(step, null))
                        continue
                    }
                    val buffer = free.takeChecked()
                    val bytes = step.sectors * SECTOR_SIZE
                    if (step.firstSector == 0L) {
                        header.copyInto(buffer)
                        readFully(input, buffer, HEADER_SIZE, bytes - HEADER_SIZE, HEADER_SIZE.toLong())
                    } else {
                        readFully(input, buffer, 0, bytes, step.firstSector * SECTOR_SIZE)
                    }
                    toProcess.add(Chunk(step, buffer))
                }
                toProcess.add(END)
            } catch (_: Abort) {
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "ps3dec-reader")

        val writer = Thread({
            try {
                var written = 0L
                while (true) {
                    val chunk = toWrite.takeChecked()
                    if (chunk === END) break
                    val buffer = chunk.buffer!!
                    val bytes = chunk.step!!.sectors * SECTOR_SIZE
                    writeFully(output, buffer, bytes)
                    written += bytes
                    callbacks.onProgress(written)
                    free.add(buffer)
                }
            } catch (_: Abort) {
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "ps3dec-writer")

        reader.isDaemon = true
        writer.isDaemon = true
        try {
            reader.start()
            writer.start()

            val cancelCheck = { if (callbacks.isCancelled()) throw ConversionCancelledException() }
            try {
                while (true) {
                    val chunk = toProcess.takeChecked(cancelCheck)
                    if (chunk === END) break
                    cancelCheck()
                    val step = chunk.step!!
                    step.diagnostic?.let(callbacks.onDiagnostic)
                    val buffer = chunk.buffer ?: continue
                    if (step.converted) crypto.process(buffer, step.sectors, step.firstSector)
                    if (stamp != null && step.firstSector == 0L) stamp.copyInto(buffer, MARKER_OFFSET)
                    toWrite.add(chunk)
                }
                toWrite.add(END)
                joinOrAbort(writer, failure)
            } catch (_: Abort) {
                // a stage failed; the failure is rethrown below
            }
        } finally {
            stop.set(true)
            crypto.close()
            // On a normal finish both threads have already ended. If one is still alive the work
            // was abandoned (failure or cancel), so wake it from any blocking read or write.
            if (reader.isAlive) reader.interrupt()
            if (writer.isAlive) writer.interrupt()
            reader.join(JOIN_MS)
            writer.join(JOIN_MS)
        }
        failure.get()?.let { throw it }
    }

    /** Wait for [thread], giving up early if another stage has failed. */
    private fun joinOrAbort(thread: Thread, failure: AtomicReference<Throwable?>) {
        while (thread.isAlive) {
            if (failure.get() != null) throw Abort
            thread.join(POLL_MS)
        }
    }

    // -- sector crypto fan-out ------------------------------------------------------------------

    private class CryptoPool(discKey: ByteArray, mode: Mode, private val workers: Int) : AutoCloseable {
        private val ciphers = Array(workers) { SectorCipher(discKey, mode) }
        private val executor: ExecutorService? =
            if (workers > 1) Executors.newFixedThreadPool(workers, NamedDaemons("ps3dec-crypto")) else null

        fun process(buffer: ByteArray, sectors: Int, firstLba: Long) {
            try {
                val pool = executor
                if (pool == null || sectors < 2 * workers) {
                    ciphers[0].process(buffer, 0, sectors, firstLba)
                    return
                }
                val perWorker = (sectors + workers - 1) / workers
                val futures = ArrayList<Future<*>>(workers)
                for (w in 0 until workers) {
                    val from = w * perWorker
                    if (from >= sectors) break
                    val n = minOf(perWorker, sectors - from)
                    futures += pool.submit { ciphers[w].process(buffer, from * SECTOR_SIZE, n, firstLba + from) }
                }
                for (f in futures) f.get()
            } catch (e: ExecutionException) {
                throw ConversionException("AES failed: ${e.cause?.message ?: e.cause}", e.cause)
            } catch (e: GeneralSecurityException) {
                throw ConversionException("AES failed: ${e.message}", e)
            }
        }

        override fun close() {
            executor?.shutdownNow()
        }
    }

    private class NamedDaemons(private val prefix: String) : ThreadFactory {
        private val n = AtomicInteger()
        override fun newThread(r: Runnable): Thread =
            Thread(r, "$prefix-${n.incrementAndGet()}").apply { isDaemon = true }
    }

    // -- channel helpers ------------------------------------------------------------------------

    private fun readFully(channel: ReadableByteChannel, into: ByteArray, offset: Int, length: Int, position: Long) {
        val bb = ByteBuffer.wrap(into, offset, length)
        while (bb.hasRemaining()) {
            if (channel.read(bb) < 0) {
                val got = position + (bb.position() - offset)
                throw ConversionException("the input ended early, at byte $got")
            }
        }
    }

    private fun writeFully(channel: WritableByteChannel, from: ByteArray, length: Int) {
        val bb = ByteBuffer.wrap(from, 0, length)
        while (bb.hasRemaining()) channel.write(bb)
    }
}
