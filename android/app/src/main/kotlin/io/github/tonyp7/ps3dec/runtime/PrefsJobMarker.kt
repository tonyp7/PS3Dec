package io.github.tonyp7.ps3dec.runtime

import android.content.Context
import io.github.tonyp7.ps3dec.job.JobMarker

/** A job that ended without the app having finished it, to be told to the user once. */
data class Interruption(val image: String, val reason: String)

/**
 * Persists "a job is in progress" so that a job the system killed can be recognised on the next
 * launch. Written synchronously: if the process dies right after, the marker must already be on disk.
 */
class PrefsJobMarker(context: Context) : JobMarker {
    private val prefs = context.getSharedPreferences("job-marker", Context.MODE_PRIVATE)

    override fun begin(image: String) {
        prefs.edit().putString(KEY_IMAGE, image).putLong(KEY_STARTED, System.currentTimeMillis()).commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY_IMAGE).remove(KEY_STARTED).commit()
    }

    /** The image a job was converting when the app was last killed, or null if it ended normally. */
    fun interruptedImage(): String? = prefs.getString(KEY_IMAGE, null)

    /** Remember that a job was interrupted, until the user has been told. */
    fun recordInterruption(image: String, reason: String) {
        prefs.edit().putString(KEY_INTERRUPTED_IMAGE, image).putString(KEY_INTERRUPTED_REASON, reason).commit()
    }

    /** The interruption not yet shown to the user, if any. */
    fun pendingInterruption(): Interruption? {
        val image = prefs.getString(KEY_INTERRUPTED_IMAGE, null) ?: return null
        return Interruption(image, prefs.getString(KEY_INTERRUPTED_REASON, "") ?: "")
    }

    fun clearInterruption() {
        prefs.edit().remove(KEY_INTERRUPTED_IMAGE).remove(KEY_INTERRUPTED_REASON).commit()
    }

    private companion object {
        const val KEY_IMAGE = "image"
        const val KEY_STARTED = "started"
        const val KEY_INTERRUPTED_IMAGE = "interrupted_image"
        const val KEY_INTERRUPTED_REASON = "interrupted_reason"
    }
}
