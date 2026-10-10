package io.github.tonyp7.ps3dec.storage

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel

/** Suffix of the temporary file a job writes to; never ends in `.iso`. */
const val PART_SUFFIX = ".ps3dec.part"

/** Suffix an existing output is renamed to while it is being replaced. */
const val OLD_SUFFIX = ".ps3dec.old"

/** What [Output.sweep] did. */
data class SweepResult(val partsRemoved: Int, val oldRestored: Int, val oldRemoved: Int)

/** Human-readable size in the style of the web backend ("512 B", "1.5 GiB"). */
fun humanSize(bytes: Long): String {
    var size = bytes.toDouble()
    for (unit in listOf("B", "KiB", "MiB", "GiB")) {
        if (size < 1024 || unit == "GiB") {
            return if (unit == "B") "%.0f %s".format(size, unit) else "%.1f %s".format(size, unit)
        }
        size /= 1024
    }
    error("unreachable")
}

/**
 * The output protocol of the `android-storage` spec: write to `<name>.ps3dec.part`, move it to
 * `<name>` only after success, and never lose an existing `<name>` if the replacement cannot be
 * completed.
 */
object Output {
    /**
     * Start an output for [finalName] in [folder].
     *
     * Leftovers of earlier runs are cleaned first ([sweep]), so this must only be called while no
     * job is running. The temporary file is created (which also proves the folder is writable) and
     * the free space on its volume is read.
     *
     * @throws StorageException [StorageException.Code.OUTPUT_EXISTS] if the target exists and
     *   [overwrite] is false or the target is a directory; [StorageException.Code.NOT_WRITABLE] if
     *   the temporary file cannot be created; [StorageException.Code.NAME_CHANGED] if the provider
     *   altered the temporary file's name
     */
    fun begin(folder: Folder, finalName: String, overwrite: Boolean): OutputSession {
        sweep(folder)

        val existing = folder.list().firstOrNull { it.name == finalName }
        if (existing != null && existing.isDirectory) {
            throw StorageException(
                StorageException.Code.OUTPUT_EXISTS,
                "$finalName in the output folder is a directory",
            )
        }
        if (existing != null && !overwrite) {
            throw StorageException(
                StorageException.Code.OUTPUT_EXISTS,
                "$finalName already exists in the output folder",
            )
        }

        val partName = finalName + PART_SUFFIX
        val part = try {
            folder.create(partName)
        } catch (e: StorageException) {
            throw e
        } catch (e: IOException) {
            throw StorageException(
                StorageException.Code.NOT_WRITABLE,
                "the output folder is not writable: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        if (part.name != partName) {
            runCatching { folder.delete(part) }
            throw StorageException(
                StorageException.Code.NAME_CHANGED,
                "the storage provider named the temporary file ${part.name} instead of $partName",
            )
        }

        val channel = try {
            folder.openWrite(part)
        } catch (e: IOException) {
            runCatching { folder.delete(part) }
            throw StorageException(
                StorageException.Code.NOT_WRITABLE,
                "the output folder is not writable: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        val free = runCatching { folder.freeSpace(part) }.getOrNull()
        return OutputSession(folder, finalName, part, channel, free)
    }

    /**
     * Remove temporary files left by an earlier run and resolve a replacement that was interrupted:
     * a `<name>.ps3dec.old` whose `<name>` is missing is restored, otherwise it is deleted.
     * Call only while no job is running.
     */
    fun sweep(folder: Folder): SweepResult {
        val entries = try {
            folder.list()
        } catch (e: StorageException) {
            return SweepResult(0, 0, 0)
        }
        val names = entries.filter { !it.isDirectory }.map { it.name }.toSet()
        var parts = 0
        var restored = 0
        var removed = 0
        for (entry in entries) {
            if (entry.isDirectory) continue
            when {
                entry.name.endsWith(PART_SUFFIX, ignoreCase = true) ->
                    if (runCatching { folder.delete(entry) }.isSuccess) parts++

                entry.name.endsWith(OLD_SUFFIX, ignoreCase = true) -> {
                    val original = entry.name.dropLast(OLD_SUFFIX.length)
                    if (original in names) {
                        if (runCatching { folder.delete(entry) }.isSuccess) removed++
                    } else {
                        val back = runCatching { folder.rename(entry, original) }.getOrNull()
                        if (back != null && back.name == original) restored++
                    }
                }
            }
        }
        return SweepResult(parts, restored, removed)
    }
}

/** An output being written. Exactly one of [commit] or [abort] ends it. */
class OutputSession internal constructor(
    private val folder: Folder,
    val finalName: String,
    private val part: Entry,
    /** Where the converted image is written. */
    val channel: WritableByteChannel,
    /** Free bytes on the output volume when the session began, or null if unknown. */
    val freeBytes: Long?,
) {
    private var finished = false

    /** Current size of the temporary file, or null if it cannot be seen. */
    fun partSize(): Long? = runCatching { folder.list().firstOrNull { it.id == part.id }?.size }.getOrNull()

    /** Throw away the temporary file. Safe to call more than once. */
    fun abort() {
        if (finished) return
        finished = true
        runCatching { channel.close() }
        runCatching { folder.delete(part) }
    }

    /**
     * Move the temporary file to its final name, replacing an existing file of that name.
     *
     * The existing file is renamed aside first and removed only after the new file is in place; if
     * the move fails, the original is renamed back and the temporary file is deleted.
     *
     * @return the finished output
     * @throws StorageException if the output could not be put in place; the original is intact
     */
    fun commit(): Entry {
        check(!finished) { "output already finished" }
        finished = true
        try {
            // Push buffered data to the provider before the file is closed and renamed.
            when (channel) {
                is SyncableChannel -> channel.sync()
                is FileChannel -> channel.force(true)
            }
            channel.close()
        } catch (e: IOException) {
            runCatching { folder.delete(part) }
            throw StorageException(StorageException.Code.IO, "could not finish the output file: ${e.message}", e)
        }

        val existing = folder.list().firstOrNull { it.name == finalName }
        var aside: Entry? = null
        try {
            if (existing != null) {
                if (existing.isDirectory) {
                    throw StorageException(
                        StorageException.Code.OUTPUT_EXISTS,
                        "$finalName in the output folder is a directory",
                    )
                }
                aside = rename(existing, finalName + OLD_SUFFIX)
            }
            val done = rename(part, finalName)
            aside?.let { runCatching { folder.delete(it) } }
            return done
        } catch (e: Throwable) {
            aside?.let { runCatching { folder.rename(it, finalName) } }
            runCatching { folder.delete(part) }
            if (e is StorageException) throw e
            throw StorageException(
                StorageException.Code.IO,
                "could not move the output into place: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
    }

    private fun rename(entry: Entry, newName: String): Entry {
        val renamed = try {
            folder.rename(entry, newName)
        } catch (e: IOException) {
            throw StorageException(
                StorageException.Code.NOT_WRITABLE,
                "the storage provider could not rename ${entry.name} to $newName: ${e.message}",
                e,
            )
        }
        if (renamed.name != newName) {
            // Put it back under a name we recognise, so the sweep can deal with it later.
            runCatching { folder.rename(renamed, entry.name) }
            throw StorageException(
                StorageException.Code.NAME_CHANGED,
                "the storage provider named the file ${renamed.name} instead of $newName",
            )
        }
        return renamed
    }
}
