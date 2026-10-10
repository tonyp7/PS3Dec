package io.github.tonyp7.ps3dec.storage

import android.os.ParcelFileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

/**
 * A write channel over a provider's file descriptor that keeps a long conversion from filling
 * memory with dirty pages.
 *
 * Converting a disc image moves tens of gigabytes through the storage, which is written far more
 * slowly than the engine produces it. Left alone the kernel buffers the difference as dirty pages
 * until memory pressure becomes critical, and the low-memory killer then ends the process, even a
 * foreground service (observed on the reference device with a 16 GiB image). Flushing every
 * [SYNC_INTERVAL] bytes keeps the amount of dirty data bounded and paces the engine to the
 * storage's real speed, which is its limit anyway.
 */
internal class CacheFriendlyWriteChannel(pfd: ParcelFileDescriptor) :
    WritableByteChannel, SyncableChannel {
    private val stream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
    private val channel = stream.channel
    private var written = 0L
    private var synced = 0L

    override fun write(src: ByteBuffer): Int {
        val n = channel.write(src)
        written += n
        if (written - synced >= SYNC_INTERVAL) sync()
        return n
    }

    override fun sync() {
        channel.force(false)
        synced = written
    }

    override fun isOpen() = channel.isOpen

    @Throws(IOException::class)
    override fun close() = channel.close()
}

/** Bytes written between flushes. */
private const val SYNC_INTERVAL = 128L * 1024 * 1024
