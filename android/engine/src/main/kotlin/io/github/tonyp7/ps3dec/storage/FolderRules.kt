package io.github.tonyp7.ps3dec.storage

/** A folder grant: the provider and the document id of the folder at the top of the tree. */
data class DocumentTree(val authority: String, val documentId: String)

/** Rules about how the three configured folders may relate to each other. */
object FolderRelation {
    /**
     * Whether the two folders are the same folder or one is inside the other, so that output in one
     * would land in or mix with the other. Folders of different providers, or on different volumes,
     * cannot be related this way.
     *
     * Document ids of the external-storage provider look like `primary:PS3/iso`: a volume, a colon,
     * then a path.
     */
    fun overlaps(a: DocumentTree, b: DocumentTree): Boolean {
        if (a.authority != b.authority) return false
        val (volumeA, pathA) = split(a.documentId)
        val (volumeB, pathB) = split(b.documentId)
        if (volumeA != volumeB) return false
        return pathA == pathB || isInside(pathA, pathB) || isInside(pathB, pathA)
    }

    private fun split(documentId: String): Pair<String, String> {
        val colon = documentId.indexOf(':')
        if (colon < 0) return documentId to ""
        return documentId.substring(0, colon) to documentId.substring(colon + 1).trim('/')
    }

    /** True when [inner] is a strict descendant of [outer] ("" being the volume root). */
    private fun isInside(inner: String, outer: String): Boolean =
        outer.isEmpty() && inner.isNotEmpty() || inner.startsWith("$outer/")
}

/** Rules for listing a folder's contents and identifying items in it. */
object Listing {
    const val IMAGE_EXTENSION = ".iso"
    const val KEY_EXTENSION = ".dkey"

    /**
     * The regular files in [entries] with the given extension (compared case-insensitively),
     * sorted by name case-insensitively, then by exact name. Directories and other types are dropped.
     */
    fun withExtension(entries: List<Entry>, extension: String): List<Entry> =
        entries
            .filter { !it.isDirectory && it.name.endsWith(extension, ignoreCase = true) }
            .sortedWith(compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name })

    /**
     * The item with [id] in the current [listing]. Items are identified only by ids that listing
     * returned; anything else is refused.
     */
    fun resolve(listing: List<Entry>, id: String, what: String): Entry =
        listing.firstOrNull { it.id == id }
            ?: throw StorageException(StorageException.Code.NOT_FOUND, "$what not found in the folder: $id")
}
