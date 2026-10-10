package io.github.tonyp7.ps3dec.engine

import io.github.tonyp7.ps3dec.storage.SyncableChannel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Throughput benchmark, run on a device through `app_process` (see `android/README.md`), not by JUnit.
 *
 * Arguments: `[directory] [image size in MiB] [comma-separated pool sizes] [file|null]`. With `null` the
 * output goes to `/dev/null`, which takes storage write speed out of the measurement.
 *
 * It reports engine throughput on real files, a copy-only run as the I/O ceiling, and the cost of the
 * crypto alone (per-sector `Cipher.init` as the engine does it, against one bulk ECB call plus an XOR
 * pass for CBC decryption).
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "/data/local/tmp" })
    val mib = args.getOrElse(1) { "2048" }.toInt()
    val pools = args.getOrElse(2) { "1,2,4,8" }.split(',').map(String::toInt)
    val toNull = args.getOrElse(3) { "file" } == "null"
    val sectors = mib * 512 // 2048-byte sectors
    println("directory=$dir image=${mib} MiB pools=$pools output=${if (toNull) "/dev/null" else "file"} cores=${Runtime.getRuntime().availableProcessors()}")

    val key = ByteArray(16) { (it * 7 + 1).toByte() }

    println("\n-- crypto only, in memory (8 MiB buffer, MiB/s) --")
    cryptoBench(key)

    val converted = File(dir, "bench-converted.iso")
    val plain = File(dir, "bench-plain.iso")
    val out = File(dir, "bench-out.iso")
    try {
        println("\n-- engine on files --")
        makeImage(converted, listOf(2, sectors - 4, 2))
        makeImage(plain, listOf(sectors))
        println("%-34s %8s %10s".format("case", "seconds", "MiB/s"))
        run("copy only (single plain region)", plain, out, Mode.ENCRYPT, 1, mib, toNull)
        for (mode in listOf(Mode.DECRYPT, Mode.ENCRYPT)) {
            for (p in pools) run("${mode.name.lowercase()}, pool $p", converted, out, mode, p, mib, toNull)
        }
    } finally {
        converted.delete()
        plain.delete()
        out.delete()
    }
}

/** Flushes every 128 MiB like the app's output channel, so dirty pages stay bounded. */
private class FlushingChannel(private val inner: FileChannel) : WritableByteChannel, SyncableChannel {
    private var sinceSync = 0L

    override fun write(src: ByteBuffer): Int {
        val n = inner.write(src)
        sinceSync += n
        if (sinceSync >= 128L shl 20) sync()
        return n
    }

    override fun sync() {
        inner.force(false)
        sinceSync = 0
    }

    override fun isOpen() = inner.isOpen
    override fun close() = inner.close()
}

private fun run(label: String, src: File, dst: File, mode: Mode, pool: Int, mib: Int, toNull: Boolean) {
    dst.delete()
    val t0 = System.nanoTime()
    FileInputStream(src).channel.use { input ->
        FileOutputStream(if (toNull) File("/dev/null") else dst).channel.use { raw ->
            val output: WritableByteChannel = if (toNull) raw else FlushingChannel(raw)
            ImageConverter.convert(
                input, src.length(), output, mode, SyntheticImage.DEFAULT_D1,
                ConversionCallbacks(), parallelism = pool,
            )
            if (!toNull) raw.force(false)
        }
    }
    val s = (System.nanoTime() - t0) / 1e9
    println("%-34s %8.1f %10.0f".format(label, s, mib / s))
}

private fun makeImage(file: File, regions: List<Int>) {
    val block = SplitMix64(42).bytes(8 * 1024 * 1024)
    val firstBlock = block.copyOf().also { SyntheticImage.headerOnly(regions).copyInto(it) }
    var remaining = regions.sum().toLong() * SECTOR_SIZE
    FileOutputStream(file).use { out ->
        var first = true
        while (remaining > 0) {
            val n = minOf(remaining, block.size.toLong()).toInt()
            out.write(if (first) firstBlock else block, 0, n)
            first = false
            remaining -= n
        }
        out.fd.sync()
    }
}

private fun cryptoBench(key: ByteArray) {
    val sectors = ImageConverter.BUFFER_SECTORS
    val bytes = sectors * SECTOR_SIZE
    val reps = 24 // 192 MiB per thread
    val data = SplitMix64(1).bytes(bytes)
    val workers = listOf(1, 4, 8)

    // Warm up the JIT and let the clocks settle, so the first measurement is not the cold one.
    run {
        val c = SectorCipher(key, Mode.DECRYPT)
        val b = data.copyOf()
        repeat(40) { c.process(b, 0, sectors, 0) }
    }

    fun report(label: String, threads: Int, seconds: Double) =
        println("%-34s %10.0f".format("$label, $threads thread(s)", threads * reps * bytes / 1048576.0 / seconds))

    for (threads in workers) {
        val pool = Executors.newFixedThreadPool(threads)
        val ciphers = List(threads) { SectorCipher(key, Mode.DECRYPT) }
        val bufs = List(threads) { data.copyOf() }
        val t0 = System.nanoTime()
        val futures = (0 until threads).map { w ->
            pool.submit { repeat(reps) { ciphers[w].process(bufs[w], 0, sectors, 0) } }
        }
        futures.forEach { it.get() }
        report("per-sector CBC decrypt", threads, (System.nanoTime() - t0) / 1e9)
        pool.shutdown()
    }

    for (threads in workers) {
        val pool = Executors.newFixedThreadPool(threads)
        val bufs = List(threads) { data.copyOf() }
        val prevs = List(threads) { ByteArray(bytes) }
        val t0 = System.nanoTime()
        val futures = (0 until threads).map { w ->
            pool.submit {
                val ecb = Cipher.getInstance("AES/ECB/NoPadding")
                ecb.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                val buf = bufs[w]
                val prev = prevs[w]
                repeat(reps) {
                    // CBC decrypt = ECB decrypt, then XOR each block with the previous ciphertext
                    // block (the sector's IV for the first block of every sector).
                    System.arraycopy(buf, 0, prev, 0, bytes)
                    ecb.doFinal(buf, 0, bytes, buf, 0)
                    var b = 0
                    while (b < bytes) {
                        if (b % SECTOR_SIZE != 0) {
                            for (i in 0 until 16) buf[b + i] = (buf[b + i].toInt() xor prev[b - 16 + i].toInt()).toByte()
                        }
                        b += 16
                    }
                }
            }
        }
        futures.forEach { it.get() }
        report("bulk ECB + XOR decrypt", threads, (System.nanoTime() - t0) / 1e9)
        pool.shutdown()
    }
}
