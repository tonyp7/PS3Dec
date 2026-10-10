package io.github.tonyp7.ps3dec.storage

import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * A document in a [Folder].
 *
 * @property id provider-assigned identifier; the only way an item is ever referred to
 * @property size length in bytes as reported by the folder listing
 */
data class Entry(val id: String, val name: String, val size: Long, val isDirectory: Boolean = false)

/** A storage operation failed in a way that can be explained to the user. */
class StorageException(val code: Code, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Code {
        /** The folder cannot be reached (card removed, grant revoked). */
        UNAVAILABLE,

        /** Output cannot be created or renamed there. */
        NOT_WRITABLE,

        /** A file with the target name exists and overwriting was not confirmed. */
        OUTPUT_EXISTS,

        /** The provider gave a created or renamed document a different name than requested. */
        NAME_CHANGED,

        /** The named item is not in the current listing. */
        NOT_FOUND,

        /** The input is no longer the file that was classified. */
        INPUT_CHANGED,

        INSUFFICIENT_SPACE,
        FILE_TOO_LARGE,
        IO,
    }
}

/** An output channel that can be told to push what it has written down to the storage. */
interface SyncableChannel {
    /** Block until everything written so far has reached the storage. */
    fun sync()
}

/**
 * The few operations the app needs on one folder, independent of how the folder is reached (the
 * Storage Access Framework on a device, memory in tests). Only direct children are ever involved.
 */
interface Folder {
    /**
     * Identifies the provider whose ids [Entry.id] values are unique within. Two folders with the
     * same namespace can be compared by id to tell whether they hold the same document.
     */
    val namespace: String

    /** Direct children of the folder. @throws StorageException with [StorageException.Code.UNAVAILABLE] */
    fun list(): List<Entry>

    fun openRead(entry: Entry): ReadableByteChannel

    /** Create an empty file named [name]. The returned entry's name may differ if the provider alters it. */
    fun create(name: String): Entry

    fun openWrite(entry: Entry): WritableByteChannel

    /** Rename [entry]. The returned entry's name may differ if the provider alters it. */
    fun rename(entry: Entry, newName: String): Entry

    fun delete(entry: Entry)

    /** Free bytes on the volume that holds [onVolumeOf], or null if that cannot be determined. */
    fun freeSpace(onVolumeOf: Entry): Long?
}
