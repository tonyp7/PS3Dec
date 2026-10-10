package io.github.tonyp7.ps3dec.catalog

import io.github.tonyp7.ps3dec.engine.HEADER_SIZE
import io.github.tonyp7.ps3dec.engine.ImageClassifier
import io.github.tonyp7.ps3dec.engine.ImageInfo
import io.github.tonyp7.ps3dec.engine.ImageKind
import io.github.tonyp7.ps3dec.engine.KeyFiles
import io.github.tonyp7.ps3dec.engine.KeyInfo
import io.github.tonyp7.ps3dec.storage.Entry
import io.github.tonyp7.ps3dec.storage.Folder
import io.github.tonyp7.ps3dec.storage.Listing
import io.github.tonyp7.ps3dec.storage.StorageException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

/** A disc image in the image folder, classified, with the key that matches its name (if any). */
data class ImageItem(val entry: Entry, val info: ImageInfo, val suggestedKey: Entry?) {
    val id: String get() = entry.id
    val name: String get() = entry.name
    val size: Long get() = entry.size
    val kind: ImageKind get() = info.kind
    val valid: Boolean get() = info.valid
}

/** A key file that has been read and checked; never carries the key value. */
data class KeyItem(val entry: Entry, val info: KeyInfo) {
    val id: String get() = entry.id
    val name: String get() = entry.name
    val valid: Boolean get() = info.valid
}

/**
 * The result of listing one folder.
 *
 * @property available false if the folder could not be reached; [problem] then says why
 */
data class Listed<T>(val available: Boolean, val items: List<T>, val problem: String? = null)

/**
 * What the user sees of the image and key folders: the `iso-catalog` and `key-handling` rules.
 *
 * A key folder can hold a whole redump set (thousands of files), and reading each one is a round
 * trip to the storage provider. Keys are therefore only *listed* by name; a key file is read and
 * checked when it is suggested for the selected image or chosen by the user ([inspectKey]).
 */
object Catalog {
    /** The `.dkey` files in the key folder, by name only: nothing is read. */
    fun keyFiles(folder: Folder): Listed<Entry> {
        val entries = try {
            Listing.withExtension(folder.list(), Listing.KEY_EXTENSION)
        } catch (e: StorageException) {
            return Listed(false, emptyList(), e.message)
        }
        return Listed(true, entries)
    }

    /** Read and check one key file. */
    fun inspectKey(folder: Folder, entry: Entry): KeyItem {
        val info = try {
            KeyFiles.inspect(entry.name, readUpTo(folder, entry, KeyFiles.MAX_KEY_FILE_BYTES + 1))
        } catch (e: IOException) {
            KeyFiles.unreadable(entry.name, e.message ?: e.javaClass.simpleName)
        }
        return KeyItem(entry, info)
    }

    /**
     * The `.iso` files in the image folder, classified (the first 4096 bytes of each are read).
     * A standard image gets the key file whose name matches it, if one exists; whether that key
     * is valid is not known until it is inspected.
     */
    fun images(folder: Folder, keyFiles: List<Entry>): Listed<ImageItem> {
        val entries = try {
            Listing.withExtension(folder.list(), Listing.IMAGE_EXTENSION)
        } catch (e: StorageException) {
            return Listed(false, emptyList(), e.message)
        }
        val byName = keyFiles.map { KeyInfo(it.name, valid = true) }
        val items = entries.map { entry ->
            val info = try {
                ImageClassifier.classify(entry.size, readUpTo(folder, entry, HEADER_SIZE))
            } catch (e: IOException) {
                ImageInfo.invalid(entry.size, "unreadable: ${e.message ?: e.javaClass.simpleName}")
            }
            val suggested = if (info.kind == ImageKind.STANDARD) {
                KeyFiles.suggest(entry.name, byName)?.let { name -> keyFiles.firstOrNull { it.name == name } }
            } else {
                null
            }
            ImageItem(entry, info, suggested)
        }
        return Listed(true, items)
    }

    private fun readUpTo(folder: Folder, entry: Entry, max: Int): ByteArray =
        folder.openRead(entry).use { channel: ReadableByteChannel ->
            val bb = ByteBuffer.allocate(max)
            while (bb.hasRemaining()) {
                if (channel.read(bb) < 0) break
            }
            bb.array().copyOf(bb.position())
        }
}
