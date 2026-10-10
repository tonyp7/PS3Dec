package io.github.tonyp7.ps3dec.storage

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import java.io.IOException
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * A [Folder] backed by a Storage Access Framework document tree.
 *
 * [treeUri] is the grant the user gave; [rootDocumentId] is the folder inside it that this object
 * represents (the tree's own root by default, or a descendant folder).
 */
class SafFolder(
    private val context: Context,
    private val treeUri: Uri,
    private val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri),
) : Folder {
    private val resolver get() = context.contentResolver

    override val namespace: String get() = treeUri.authority.orEmpty()

    /** A folder object for a subfolder with the given document id, using the same grant. */
    fun child(documentId: String) = SafFolder(context, treeUri, documentId)

    /** Document id of the folder this object represents. */
    fun rootId(): String = rootDocumentId

    private fun documentUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    override fun list(): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)
        val entries = ArrayList<Entry>()
        try {
            val cursor = resolver.query(children, COLUMNS, null, null, null)
                ?: throw unavailable("the provider returned no listing", null)
            cursor.use { while (it.moveToNext()) entries += it.toEntry() }
        } catch (e: SecurityException) {
            throw unavailable("access to the folder was revoked", e)
        } catch (e: IllegalArgumentException) {
            throw unavailable("the folder no longer exists", e)
        } catch (e: UnsupportedOperationException) {
            throw unavailable("the folder cannot be listed", e)
        } catch (e: IllegalStateException) {
            throw unavailable("the storage is not available", e)
        }
        return entries
    }

    override fun openRead(entry: Entry): ReadableByteChannel {
        val pfd = open(entry, "r")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
    }

    override fun create(name: String): Entry {
        val parent = documentUri(rootDocumentId)
        val created = try {
            DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
        } catch (e: SecurityException) {
            throw IOException("not permitted to create $name: ${e.message}", e)
        } catch (e: IllegalStateException) {
            throw IOException("could not create $name: ${e.message}", e)
        } ?: throw IOException("the storage provider refused to create $name")
        return describe(created) ?: throw IOException("created $name but cannot read it back")
    }

    override fun openWrite(entry: Entry): WritableByteChannel {
        val pfd = open(entry, "w")
        return CacheFriendlyWriteChannel(pfd)
    }

    override fun rename(entry: Entry, newName: String): Entry {
        val renamed = try {
            DocumentsContract.renameDocument(resolver, documentUri(entry.id), newName)
        } catch (e: SecurityException) {
            throw IOException("not permitted to rename ${entry.name}: ${e.message}", e)
        } catch (e: UnsupportedOperationException) {
            throw IOException("the storage provider cannot rename files", e)
        } ?: throw IOException("the storage provider refused to rename ${entry.name}")
        return describe(renamed) ?: throw IOException("renamed ${entry.name} but cannot read it back")
    }

    override fun delete(entry: Entry) {
        val ok = try {
            DocumentsContract.deleteDocument(resolver, documentUri(entry.id))
        } catch (e: SecurityException) {
            throw IOException("not permitted to delete ${entry.name}: ${e.message}", e)
        }
        if (!ok) throw IOException("the storage provider refused to delete ${entry.name}")
    }

    override fun freeSpace(onVolumeOf: Entry): Long? =
        try {
            open(onVolumeOf, "r").use { pfd ->
                val st = Os.fstatvfs(pfd.fileDescriptor)
                st.f_bavail * st.f_frsize
            }
        } catch (_: ErrnoException) {
            null
        } catch (_: IOException) {
            null
        }

    private fun open(entry: Entry, mode: String): ParcelFileDescriptor =
        try {
            resolver.openFileDescriptor(documentUri(entry.id), mode)
        } catch (e: SecurityException) {
            throw IOException("not permitted to open ${entry.name}: ${e.message}", e)
        } catch (e: java.io.FileNotFoundException) {
            throw IOException("${entry.name} is not available: ${e.message}", e)
        } ?: throw IOException("the storage provider could not open ${entry.name}")

    private fun describe(uri: Uri): Entry? =
        resolver.query(uri, COLUMNS, null, null, null)?.use { if (it.moveToFirst()) it.toEntry() else null }

    private fun Cursor.toEntry(): Entry {
        val mime = getString(3)
        return Entry(
            id = getString(0),
            name = getString(1) ?: "",
            size = if (isNull(2)) 0L else getLong(2),
            isDirectory = mime == Document.MIME_TYPE_DIR,
        )
    }

    private fun unavailable(why: String, cause: Throwable?) =
        StorageException(StorageException.Code.UNAVAILABLE, why, cause)

    private companion object {
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE,
        )
    }
}
