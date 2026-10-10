package io.github.tonyp7.ps3dec.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * An in-memory [Folder] that can misbehave the way real storage providers do: refusing to create,
 * renaming created or renamed documents, failing particular renames, and going away.
 */
class MemoryFolder(var free: Long? = 1L shl 40) : Folder {
    private class Doc(val id: String, var name: String, val directory: Boolean, val data: ByteArrayOutputStream)

    override val namespace: String = "memory-${System.identityHashCode(this)}"

    private val docs = LinkedHashMap<String, Doc>()
    private var counter = 0

    var unavailable = false
    var failCreate = false
    var alterCreatedName: ((String) -> String)? = null
    var alterRenamedName: ((from: String, to: String) -> String)? = null
    var failRename: ((from: String, to: String) -> Boolean)? = null
    val renames = ArrayList<Pair<String, String>>()

    fun put(name: String, content: ByteArray = ByteArray(0), directory: Boolean = false): Entry {
        val doc = Doc("doc-${++counter}", name, directory, ByteArrayOutputStream().also { it.write(content) })
        docs[doc.id] = doc
        return doc.toEntry()
    }

    val names: List<String> get() = docs.values.map { it.name }.sorted()

    fun content(name: String): ByteArray =
        docs.values.first { it.name == name }.data.toByteArray()

    private fun Doc.toEntry() = Entry(id, name, data.size().toLong(), directory)

    private fun requireAvailable() {
        if (unavailable) throw StorageException(StorageException.Code.UNAVAILABLE, "the folder is not available")
    }

    override fun list(): List<Entry> {
        requireAvailable()
        return docs.values.map { it.toEntry() }
    }

    override fun openRead(entry: Entry): ReadableByteChannel {
        requireAvailable()
        return Channels.newChannel(ByteArrayInputStream(docs.getValue(entry.id).data.toByteArray()))
    }

    override fun create(name: String): Entry {
        requireAvailable()
        if (failCreate) throw IOException("permission denied")
        var actual = alterCreatedName?.invoke(name) ?: name
        if (docs.values.any { it.name == actual }) actual = "$actual (1)"
        return put(actual)
    }

    override fun openWrite(entry: Entry): WritableByteChannel {
        requireAvailable()
        val doc = docs.getValue(entry.id)
        doc.data.reset()
        return Channels.newChannel(doc.data)
    }

    override fun rename(entry: Entry, newName: String): Entry {
        requireAvailable()
        renames += entry.name to newName
        if (failRename?.invoke(entry.name, newName) == true) throw IOException("rename refused")
        val doc = docs.getValue(entry.id)
        var actual = alterRenamedName?.invoke(entry.name, newName) ?: newName
        if (docs.values.any { it !== doc && it.name == actual }) actual = "$actual (1)"
        doc.name = actual
        return doc.toEntry()
    }

    override fun delete(entry: Entry) {
        requireAvailable()
        docs.remove(entry.id)
    }

    override fun freeSpace(onVolumeOf: Entry): Long? = free
}
