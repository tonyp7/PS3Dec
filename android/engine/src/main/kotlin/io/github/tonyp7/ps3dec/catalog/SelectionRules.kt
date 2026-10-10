package io.github.tonyp7.ps3dec.catalog

import io.github.tonyp7.ps3dec.engine.ImageKind
import io.github.tonyp7.ps3dec.engine.Mode

/** The rules of the `android-ui` spec for what selecting an image sets up and when Start is allowed. */
object SelectionRules {
    /** Decrypt a standard or encrypted 3k3y image; encrypt a decrypted 3k3y image. */
    fun defaultMode(kind: ImageKind): Mode =
        if (kind == ImageKind.THREE_K3Y_DECRYPTED) Mode.ENCRYPT else Mode.DECRYPT

    /** The key selection applies to standard images only; 3k3y images carry their own key. */
    fun usesKey(kind: ImageKind): Boolean = kind == ImageKind.STANDARD

    /** Why the key control is disabled for this image, or null when it is usable. */
    fun keyDisabledReason(kind: ImageKind): String? =
        if (usesKey(kind)) null else "3k3y images contain their own key, so no key file is needed."

    /** The conflict between [mode] and a 3k3y image's state, or null. */
    fun modeConflict(kind: ImageKind, mode: Mode): String? = when {
        kind == ImageKind.THREE_K3Y_DECRYPTED && mode == Mode.DECRYPT -> "This 3k3y image is already decrypted."
        kind == ImageKind.THREE_K3Y_ENCRYPTED && mode == Mode.ENCRYPT -> "This 3k3y image is already encrypted."
        else -> null
    }

    /**
     * Why Start is disabled, or null when it is allowed: no image selected, a job is running, the
     * mode conflicts with a 3k3y image's state, or a standard image has no valid key.
     *
     * @param key the chosen key once it has been read and checked
     * @param keyPending a key has been chosen but is still being checked
     */
    fun startBlockedReason(
        image: ImageItem?,
        mode: Mode,
        key: KeyItem?,
        jobRunning: Boolean,
        keyPending: Boolean = false,
    ): String? = when {
        jobRunning -> "A job is already running."
        image == null -> "Choose an image first."
        !image.valid -> "This image cannot be used: ${image.info.reason}"
        modeConflict(image.kind, mode) != null -> modeConflict(image.kind, mode)
        usesKey(image.kind) && keyPending -> "Checking the key..."
        usesKey(image.kind) && key == null -> "Choose a key for this image."
        usesKey(image.kind) && !key!!.valid -> "The chosen key is not valid: ${key.info.reason}"
        else -> null
    }
}
