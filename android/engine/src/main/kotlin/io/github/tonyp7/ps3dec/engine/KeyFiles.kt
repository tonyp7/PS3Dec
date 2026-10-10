package io.github.tonyp7.ps3dec.engine

/** A `.dkey` file that cannot be used, with the reason to show the user. */
class KeyFormatException(message: String) : Exception(message)

/** A key file as listed to the user: never carries the key value itself. */
data class KeyInfo(val name: String, val valid: Boolean, val reason: String? = null)

/** Key file rules of the `key-handling` spec. */
object KeyFiles {
    /** Key files longer than this are rejected without being parsed. */
    const val MAX_KEY_FILE_BYTES = 256

    private const val KEY_HEX_LENGTH = 32

    /**
     * The canonical upper-case 32-character disc key from a key file's content (redump `.dkey`
     * files hold the disc key itself, as the reference tool's `key` type takes it, not a D1): surrounding whitespace
     * and an optional `0x` prefix are removed, and exactly 32 hexadecimal digits must remain.
     *
     * @throws KeyFormatException with a user-readable reason if the content is not a key
     */
    fun parseKey(raw: ByteArray): String {
        if (raw.size > MAX_KEY_FILE_BYTES) throw KeyFormatException("file is larger than $MAX_KEY_FILE_BYTES bytes")
        if (raw.any { it < 0 }) throw KeyFormatException("file is not ASCII text")

        var text = raw.decodeToString().trim()
        if (text.startsWith("0x") || text.startsWith("0X")) text = text.substring(2)
        if (text.isEmpty()) throw KeyFormatException("file is empty")
        if (text.length != KEY_HEX_LENGTH) {
            throw KeyFormatException("expected $KEY_HEX_LENGTH hex characters, found ${text.length}")
        }
        if (!text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            throw KeyFormatException("contains characters that are not hexadecimal")
        }
        return text.uppercase()
    }

    /** Listing entry for a key file with the given content. */
    fun inspect(name: String, raw: ByteArray): KeyInfo =
        try {
            parseKey(raw)
            KeyInfo(name, valid = true)
        } catch (e: KeyFormatException) {
            KeyInfo(name, valid = false, reason = e.message)
        }

    /** Listing entry for a key file that could not be read at all. */
    fun unreadable(name: String, why: String): KeyInfo = KeyInfo(name, valid = false, reason = "unreadable: $why")

    /**
     * The valid key whose file name without extension equals [isoName]'s without extension,
     * compared case-insensitively; an exact-case match wins over a case-folded one.
     */
    fun suggest(isoName: String, keys: List<KeyInfo>): String? {
        val stem = stem(isoName)
        val matches = keys.filter { it.valid && stem(it.name).equals(stem, ignoreCase = true) }
        if (matches.isEmpty()) return null
        return (matches.firstOrNull { stem(it.name) == stem } ?: matches.first()).name
    }

    /** File name without its last extension; a leading or trailing dot does not start an extension. */
    internal fun stem(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot in 1 until name.length - 1) name.substring(0, dot) else name
    }
}
