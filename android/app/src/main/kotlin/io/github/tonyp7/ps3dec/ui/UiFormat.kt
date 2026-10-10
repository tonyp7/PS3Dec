package io.github.tonyp7.ps3dec.ui

import android.net.Uri
import android.provider.DocumentsContract
import io.github.tonyp7.ps3dec.storage.humanSize

fun formatSize(bytes: Long): String = humanSize(bytes)

fun formatDuration(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return when {
        s >= 3600 -> "%dh %02dm %02ds".format(s / 3600, s % 3600 / 60, s % 60)
        else -> "%d:%02d".format(s / 60, s % 60)
    }
}

fun formatRate(bytesPerSecond: Double?): String =
    if (bytesPerSecond == null) "measuring..." else "${formatSize(bytesPerSecond.toLong())}/s"

/** A readable name for a picked folder, from its tree document id (`primary:PS3/iso`). */
fun describeFolder(uri: Uri): String {
    val id = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (_: IllegalArgumentException) {
        return uri.toString()
    }
    return describeDocumentId(id)
}

/** Pure part of [describeFolder], for tests. */
fun describeDocumentId(id: String): String {
    val colon = id.indexOf(':')
    if (colon < 0) return id
    val volume = id.substring(0, colon)
    val path = id.substring(colon + 1).trim('/')
    val root = if (volume == "primary") "Internal storage" else "Card $volume"
    return if (path.isEmpty()) root else "$root / ${path.replace("/", " / ")}"
}
