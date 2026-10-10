package io.github.tonyp7.ps3dec.storage

import java.io.IOException

/** Checks made before a job is allowed to start, and the translation of low-level I/O failures. */
object PreFlight {
    /**
     * The input item with [id] in the current [listing], verified to still be the file that was
     * classified: present and of the same size.
     */
    fun checkInput(listing: List<Entry>, id: String, classifiedSize: Long): Entry {
        val entry = Listing.resolve(listing, id, "image")
        if (entry.size != classifiedSize) {
            throw StorageException(
                StorageException.Code.INPUT_CHANGED,
                "${entry.name} changed since it was listed (it was ${humanSize(classifiedSize)}, " +
                    "now ${humanSize(entry.size)}); refresh the list",
            )
        }
        return entry
    }

    /** Fail if the output volume has less than [required] bytes free. An unknown amount passes. */
    fun checkSpace(required: Long, free: Long?) {
        if (free != null && free < required) {
            throw StorageException(
                StorageException.Code.INSUFFICIENT_SPACE,
                "not enough free space in the output folder: need ${humanSize(required)}, have ${humanSize(free)}",
            )
        }
    }

    /**
     * A plain-language explanation for an I/O failure, from its error code, or null if the
     * failure is not one we recognise. Looks through the cause chain.
     */
    fun explain(error: Throwable): StorageException? {
        var t: Throwable? = error
        while (t != null) {
            if (t is StorageException) return t
            if (t is IOException) {
                val text = t.message.orEmpty()
                when {
                    "ENOSPC" in text || "No space left" in text ->
                        return StorageException(
                            StorageException.Code.INSUFFICIENT_SPACE,
                            "the output volume ran out of space",
                            error,
                        )
                    "EFBIG" in text || "File too large" in text ->
                        return StorageException(
                            StorageException.Code.FILE_TOO_LARGE,
                            "the output volume cannot hold a file this large; its filesystem has a " +
                                "maximum file size (FAT32 allows at most 4 GiB). Use a volume formatted " +
                                "as exFAT, F2FS or ext4",
                            error,
                        )
                    "EROFS" in text || "Read-only file system" in text ->
                        return StorageException(
                            StorageException.Code.NOT_WRITABLE,
                            "the output volume is read-only",
                            error,
                        )
                    "ENODEV" in text || "ENXIO" in text || "ESTALE" in text || "No such device" in text ->
                        return StorageException(
                            StorageException.Code.UNAVAILABLE,
                            "the storage became unavailable (was the card removed?)",
                            error,
                        )
                }
            }
            t = t.cause
        }
        return null
    }
}
