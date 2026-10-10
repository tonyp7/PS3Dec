package io.github.tonyp7.ps3dec.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tonyp7.ps3dec.catalog.Catalog
import io.github.tonyp7.ps3dec.catalog.ImageItem
import io.github.tonyp7.ps3dec.catalog.KeyItem
import io.github.tonyp7.ps3dec.catalog.Listed
import io.github.tonyp7.ps3dec.catalog.SelectionRules
import io.github.tonyp7.ps3dec.engine.Mode
import io.github.tonyp7.ps3dec.job.JobRejectedException
import io.github.tonyp7.ps3dec.job.StartRequest
import io.github.tonyp7.ps3dec.runtime.ConversionRuntime
import io.github.tonyp7.ps3dec.storage.Entry
import io.github.tonyp7.ps3dec.storage.FolderChoiceException
import io.github.tonyp7.ps3dec.storage.FolderConfig
import io.github.tonyp7.ps3dec.storage.FolderRole
import io.github.tonyp7.ps3dec.storage.FolderState
import io.github.tonyp7.ps3dec.storage.SafFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One configured folder as the interface shows it. */
data class FolderUi(val role: FolderRole, val label: String?, val state: FolderState)

data class UiState(
    /** False until the saved folder choices have been read. */
    val loaded: Boolean = false,
    val folders: List<FolderUi> = FolderRole.entries.map { FolderUi(it, null, FolderState.NotChosen) },
    val images: Listed<ImageItem>? = null,
    /** The key files by name; none of them has been read. */
    val keyFiles: Listed<Entry>? = null,
    val loading: Boolean = false,
    val selectedImageId: String? = null,
    val mode: Mode = Mode.DECRYPT,
    val keyId: String? = null,
    /** The chosen key once it has been read and checked. */
    val checkedKey: KeyItem? = null,
    /** The chosen key is being read and checked. */
    val keyPending: Boolean = false,
    val showKeyPicker: Boolean = false,
    /** Set when the target already exists; the user must confirm before the job is sent. */
    val confirmOverwrite: Boolean = false,
    val startError: String? = null,
    val folderError: String? = null,
) {
    /** All three folders have been chosen (they may still be unavailable). */
    val setupComplete: Boolean get() = folders.all { it.state != FolderState.NotChosen }

    val selectedImage: ImageItem? get() = images?.items?.firstOrNull { it.id == selectedImageId }

    /** The chosen key's file entry, whether or not it has been checked yet. */
    val chosenKeyEntry: Entry? get() = keyFiles?.items?.firstOrNull { it.id == keyId }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    private val config = FolderConfig(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch { refresh() }
    }

    // -- folders and listings -----------------------------------------------------------------

    /** Re-read the folders' states and contents. */
    fun reload() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val started = android.os.SystemClock.elapsedRealtime()
        _state.update { it.copy(loading = true) }
        val result = withContext(Dispatchers.IO) {
            val uris = FolderRole.entries.associateWith { config.get(it) }
            val states = FolderRole.entries.associateWith { config.state(it) }
            val folders = FolderRole.entries.map { role ->
                FolderUi(role, uris[role]?.let(::describeFolder), states.getValue(role))
            }

            fun folderFor(role: FolderRole): SafFolder? =
                uris[role]?.takeIf { states[role] == FolderState.Ready }?.let { SafFolder(app, it) }

            val images = folderFor(FolderRole.IMAGES)
            val keys = folderFor(FolderRole.KEYS)
            val output = folderFor(FolderRole.OUTPUT)
            if (!ConversionRuntime.controller(app).isRunning) ConversionRuntime.configure(images, keys, output)

            val keyListing = keys?.let { Catalog.keyFiles(it) }
            val imageListing = images?.let { Catalog.images(it, keyListing?.items.orEmpty()) }
            Triple(folders, keyListing, imageListing)
        }
        val (folders, keyListing, imageListing) = result
        android.util.Log.i(
            "PS3Dec",
            "listing refreshed in ${android.os.SystemClock.elapsedRealtime() - started} ms: " +
                "${imageListing?.items?.size ?: 0} images, ${keyListing?.items?.size ?: 0} key files",
        )
        _state.update { old ->
            val stillThere = imageListing?.items?.any { it.id == old.selectedImageId && it.valid } == true
            old.copy(
                loaded = true,
                loading = false,
                folders = folders,
                keyFiles = keyListing,
                images = imageListing,
                selectedImageId = if (stillThere) old.selectedImageId else null,
                keyId = if (stillThere) old.keyId else null,
                checkedKey = if (stillThere) old.checkedKey else null,
                keyPending = if (stillThere) old.keyPending else false,
            )
        }
        // Keys are only read on demand; re-check the chosen one in case its file changed.
        if (_state.value.keyId != null) checkKey()
    }

    /** The user picked a folder for [role] in the system picker. */
    fun chooseFolder(role: FolderRole, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { config.set(role, uri) }
                _state.update { it.copy(folderError = null) }
            } catch (e: FolderChoiceException) {
                _state.update { it.copy(folderError = e.message) }
                return@launch
            } catch (e: SecurityException) {
                _state.update { it.copy(folderError = "Android did not allow access to that folder: ${e.message}") }
                return@launch
            }
            refresh()
        }
    }

    fun clearFolderError() = _state.update { it.copy(folderError = null) }

    // -- selection ----------------------------------------------------------------------------

    fun select(image: ImageItem) {
        if (!image.valid) return
        _state.update {
            it.copy(
                selectedImageId = image.id,
                mode = SelectionRules.defaultMode(image.kind),
                keyId = image.suggestedKey?.id,
                checkedKey = null,
                keyPending = false,
                startError = null,
                confirmOverwrite = false,
            )
        }
        checkKey()
    }

    fun setMode(mode: Mode) = _state.update { it.copy(mode = mode, startError = null) }

    /** Use the key file with this id; it is read and checked now. */
    fun setKey(id: String?) {
        _state.update { it.copy(keyId = id, checkedKey = null, keyPending = false, showKeyPicker = false, startError = null) }
        checkKey()
    }

    fun openKeyPicker() = _state.update { it.copy(showKeyPicker = true) }

    fun closeKeyPicker() = _state.update { it.copy(showKeyPicker = false) }

    private var keyCheck = 0

    /** Read and validate the chosen key, ignoring the result if the choice changed meanwhile. */
    private fun checkKey() {
        val entry = _state.value.chosenKeyEntry
        val ticket = ++keyCheck
        if (entry == null) {
            _state.update { it.copy(checkedKey = null, keyPending = false) }
            return
        }
        _state.update { it.copy(keyPending = true) }
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) { Catalog.inspectKey(ConversionRuntime.keyFolder, entry) }
            if (ticket == keyCheck) _state.update { it.copy(checkedKey = item, keyPending = false) }
        }
    }

    // -- jobs ---------------------------------------------------------------------------------

    /** Start the selected job; asks for confirmation first if the target file already exists. */
    fun start(confirmedOverwrite: Boolean = false) {
        val s = _state.value
        val image = s.selectedImage ?: return
        viewModelScope.launch {
            val exists = !confirmedOverwrite && withContext(Dispatchers.IO) {
                runCatching {
                    ConversionRuntime.outputFolder.list().any { it.name == image.name && !it.isDirectory }
                }.getOrDefault(false)
            }
            if (exists) {
                _state.update { it.copy(confirmOverwrite = true) }
                return@launch
            }
            _state.update { it.copy(confirmOverwrite = false, startError = null) }
            try {
                ConversionRuntime.start(
                    app,
                    StartRequest(
                        imageId = image.id,
                        mode = s.mode,
                        keyId = if (SelectionRules.usesKey(image.kind)) s.keyId else null,
                        overwrite = confirmedOverwrite,
                        expectedSize = image.size,
                    ),
                )
            } catch (e: JobRejectedException) {
                _state.update { it.copy(startError = e.message) }
            }
        }
    }

    fun dismissOverwrite() = _state.update { it.copy(confirmOverwrite = false) }

    fun cancel() {
        viewModelScope.launch { ConversionRuntime.cancel(app) }
    }

    fun dismissInterruption() = ConversionRuntime.dismissInterruption(app)
}
