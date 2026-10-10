package io.github.tonyp7.ps3dec.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.folderStore by preferencesDataStore(name = "folders")

/** The three folders the app works in. */
enum class FolderRole(val key: String, val writable: Boolean) {
    IMAGES("images", writable = false),
    KEYS("keys", writable = false),
    OUTPUT("output", writable = true),
}

/** The chosen folder cannot be used, with the reason to show the user. */
class FolderChoiceException(message: String) : Exception(message)

/** Where a configured folder stands. */
sealed interface FolderState {
    data object NotChosen : FolderState

    /** Chosen, but the grant is gone or the folder cannot be reached. */
    data class Unavailable(val reason: String) : FolderState

    data object Ready : FolderState
}

/**
 * The persisted folder choices: one tree URI per [FolderRole], each backed by a persisted URI
 * permission so it survives restarts and reboots.
 */
class FolderConfig(private val context: Context) {
    private fun key(role: FolderRole) = stringPreferencesKey(role.key)

    private fun Preferences.uri(role: FolderRole): Uri? = this[key(role)]?.let(Uri::parse)

    val folders: Flow<Map<FolderRole, Uri?>> =
        context.folderStore.data.map { prefs -> FolderRole.entries.associateWith { prefs.uri(it) } }

    suspend fun get(role: FolderRole): Uri? = context.folderStore.data.first().uri(role)

    /**
     * Use [treeUri] (the result of the system folder picker) for [role]: refuse an output folder
     * that is, is inside or contains the image folder, keep the grant across restarts, and let go
     * of the grant that role held before.
     */
    suspend fun set(role: FolderRole, treeUri: Uri) {
        val current = context.folderStore.data.first()
        val tree = treeOf(treeUri)
        val clash = when (role) {
            FolderRole.IMAGES -> current.uri(FolderRole.OUTPUT)?.let(::treeOf)
            FolderRole.OUTPUT -> current.uri(FolderRole.IMAGES)?.let(::treeOf)
            FolderRole.KEYS -> null
        }
        if (clash != null && FolderRelation.overlaps(tree, clash)) {
            throw FolderChoiceException(
                "The output folder must be separate from the image folder (not the same folder, " +
                    "and not inside or containing it).",
            )
        }

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (role.writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        context.contentResolver.takePersistableUriPermission(treeUri, flags)

        val previous = current.uri(role)
        context.folderStore.edit { it[key(role)] = treeUri.toString() }
        if (previous != null && previous != treeUri) release(previous)
    }

    /** Forget the folder for [role] and release its grant. */
    suspend fun clear(role: FolderRole) {
        val previous = get(role)
        context.folderStore.edit { it.remove(key(role)) }
        if (previous != null) release(previous)
    }

    /** Whether [role] has a folder and a live grant, and the folder can still be listed. */
    suspend fun state(role: FolderRole): FolderState {
        val uri = get(role) ?: return FolderState.NotChosen
        if (!hasGrant(uri)) return FolderState.Unavailable("access to the folder was revoked; choose it again")
        return try {
            SafFolder(context, uri).list()
            FolderState.Ready
        } catch (e: StorageException) {
            FolderState.Unavailable("${e.message}; choose it again")
        }
    }

    fun hasGrant(uri: Uri): Boolean = context.contentResolver.persistedUriPermissions.any { it.uri == uri }

    private fun release(uri: Uri) {
        // Another role may still use the same tree; keep the grant then.
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun treeOf(uri: Uri) = DocumentTree(uri.authority.orEmpty(), DocumentsContract.getTreeDocumentId(uri))
}
