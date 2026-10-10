package io.github.tonyp7.ps3dec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.tonyp7.ps3dec.catalog.ImageItem
import io.github.tonyp7.ps3dec.catalog.SelectionRules
import io.github.tonyp7.ps3dec.engine.ImageKind
import io.github.tonyp7.ps3dec.engine.Mode
import io.github.tonyp7.ps3dec.job.JobSnapshot
import io.github.tonyp7.ps3dec.job.JobState
import io.github.tonyp7.ps3dec.runtime.Interruption
import io.github.tonyp7.ps3dec.storage.FolderRole
import io.github.tonyp7.ps3dec.storage.FolderState

/** What the main screen can ask for. */
class MainActions(
    val onSelect: (ImageItem) -> Unit = {},
    val onMode: (Mode) -> Unit = {},
    val onKey: (String?) -> Unit = {},
    val onOpenKeyPicker: () -> Unit = {},
    val onCloseKeyPicker: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onConfirmOverwrite: () -> Unit = {},
    val onDismissOverwrite: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onFolders: () -> Unit = {},
    val onChoose: (FolderRole) -> Unit = {},
    val onDismissInterruption: () -> Unit = {},
)

private fun kindLabel(kind: ImageKind) = when (kind) {
    ImageKind.STANDARD -> "Standard (key needed)"
    ImageKind.THREE_K3Y_ENCRYPTED -> "3k3y, encrypted"
    ImageKind.THREE_K3Y_DECRYPTED -> "3k3y, decrypted"
    ImageKind.INVALID -> "Not usable"
}

/** The main screen: images, the choice for the selected one, and the job. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    job: JobSnapshot?,
    interruption: Interruption?,
    actions: MainActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("PS3Dec") },
                actions = {
                    TextButton(onClick = actions.onRefresh, modifier = Modifier.testTag("refresh")) { Text("Refresh") }
                    TextButton(onClick = actions.onFolders, modifier = Modifier.testTag("open-folders")) { Text("Folders") }
                },
            )
        },
    ) { padding ->
        // Two panes from about the width of a handheld in landscape; one scrolling list otherwise.
        // A lazy list is never placed inside a scrolling column (it would be measured unbounded).
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (interruption != null) InterruptionBanner(interruption, actions.onDismissInterruption)
            // Bring the job into view when a new one starts (the selection panel may have been scrolled).
            val paneScroll = rememberScrollState()
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            androidx.compose.runtime.LaunchedEffect(job?.id) {
                if (job != null) {
                    paneScroll.animateScrollTo(0)
                    listState.animateScrollToItem(0)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxSize().testTag("image-list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { imageItems(state, actions) }
                    Column(
                        Modifier.weight(1f).fillMaxSize().verticalScroll(paneScroll),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // The job comes first so its progress is never below the fold on a short screen.
                        JobCard(job, actions.onCancel)
                        SelectionPanel(state, job, actions)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("image-list"),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { JobCard(job, actions.onCancel) }
                    imageItems(state, actions)
                    item { SelectionPanel(state, job, actions) }
                }
            }
        }
    }
    if (state.confirmOverwrite) {
        OverwriteDialog(state.selectedImage?.name.orEmpty(), actions.onConfirmOverwrite, actions.onDismissOverwrite)
    }
}

@Composable
private fun InterruptionBanner(interruption: Interruption, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().testTag("interruption"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("The previous conversion was interrupted", style = MaterialTheme.typography.titleSmall)
            Text(
                "${interruption.image} did not finish: ${interruption.reason}. The partial file was removed; " +
                    "start it again when you are ready.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

// -- images -----------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.imageItems(state: UiState, actions: MainActions) {
    item { Text("Disc images", style = MaterialTheme.typography.titleMedium) }
    val images = state.images
    when {
        state.loading && images == null -> item { Text("Loading...", modifier = Modifier.testTag("images-loading")) }
        images == null || !images.available -> item {
            EmptyState(
                "The disc image folder is not available",
                images?.problem ?: state.folders.firstOrNull { it.role == FolderRole.IMAGES }
                    ?.let { (it.state as? FolderState.Unavailable)?.reason },
                "Choose the disc image folder again",
                { actions.onChoose(FolderRole.IMAGES) },
                "images-unavailable",
            )
        }
        images.items.isEmpty() -> item {
            EmptyState(
                "No disc images found",
                "The folder ${state.folders.first { it.role == FolderRole.IMAGES }.label.orEmpty()} has no .iso files " +
                    "directly inside it (subfolders are not searched).",
                "Choose another folder",
                { actions.onChoose(FolderRole.IMAGES) },
                "images-empty",
            )
        }
        else -> items(images.items, key = { it.id }) { image ->
            ImageRow(image, selected = image.id == state.selectedImageId, onClick = { actions.onSelect(image) })
        }
    }
}

@Composable
private fun EmptyState(title: String, detail: String?, action: String, onAction: () -> Unit, tag: String) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun ImageRow(image: ImageItem, selected: Boolean, onClick: () -> Unit) {
    val container = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        !image.valid -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        onClick = onClick,
        enabled = image.valid,
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth().testTag("image-${image.name}").focusRing(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(image.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${formatSize(image.size)} · ${kindLabel(image.kind)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            when {
                !image.valid -> Text(
                    image.info.reason.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                image.kind == ImageKind.STANDARD -> Text(
                    image.suggestedKey?.let { "Key suggested: ${it.name}" } ?: "No key with this name",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// -- the choice for the selected image ---------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionPanel(state: UiState, job: JobSnapshot?, actions: MainActions) {
    val image = state.selectedImage
    val running = job?.state == JobState.RUNNING
    Card(Modifier.fillMaxWidth().testTag("selection")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(image?.name ?: "No image selected", style = MaterialTheme.typography.titleMedium)

            Text("Action", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(Mode.DECRYPT to "Decrypt", Mode.ENCRYPT to "Encrypt").forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { actions.onMode(mode) },
                        enabled = image != null && !running,
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        modifier = Modifier.testTag("mode-${mode.name.lowercase()}").focusRing(),
                    ) { Text(label) }
                }
            }

            Text("Key", style = MaterialTheme.typography.labelLarge)
            val keyReason = image?.let { SelectionRules.keyDisabledReason(it.kind) }
            when {
                image == null -> Text("Select an image to choose its key.", style = MaterialTheme.typography.bodyMedium)
                keyReason != null -> Text(keyReason, modifier = Modifier.testTag("key-disabled"), style = MaterialTheme.typography.bodyMedium)
                state.keyFiles?.available != true -> EmptyState(
                    "The keys folder is not available",
                    state.keyFiles?.problem,
                    "Choose the keys folder again",
                    { actions.onChoose(FolderRole.KEYS) },
                    "keys-unavailable",
                )
                state.keyFiles.items.isEmpty() -> Text("No .dkey files in the keys folder.", modifier = Modifier.testTag("keys-empty"))
                else -> KeyChoice(state, image, running, actions)
            }

            HorizontalDivider()
            val blocked = SelectionRules.startBlockedReason(image, state.mode, state.checkedKey, running, state.keyPending)
            Button(
                onClick = actions.onStart,
                enabled = blocked == null,
                modifier = Modifier.fillMaxWidth().testTag("start").focusRing(),
            ) { Text("Start") }
            if (blocked != null) {
                Text(blocked, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("start-reason"))
            }
            state.startError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("start-error"))
            }
        }
    }
}

/** The chosen key (suggested or picked), its check result, and a way to pick another. */
@Composable
private fun KeyChoice(state: UiState, image: ImageItem, running: Boolean, actions: MainActions) {
    val chosen = state.chosenKeyEntry
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("key-choice")) {
        if (chosen == null) {
            Text(
                "No key file has the same name as this image. Choose one from the keys folder.",
                modifier = Modifier.testTag("key-none"),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(chosen.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("key-current"))
            val checked = state.checkedKey
            val (text, error) = when {
                state.keyPending || checked == null -> "Checking..." to false
                !checked.valid -> (checked.info.reason ?: "not a valid key") to true
                image.suggestedKey?.id == chosen.id -> "Valid. Suggested for this image." to false
                else -> "Valid." to false
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("key-status"),
            )
        }
        OutlinedButton(
            onClick = actions.onOpenKeyPicker,
            enabled = !running,
            modifier = Modifier.testTag("choose-key").focusRing(),
        ) {
            Text(if (chosen == null) "Choose a key..." else "Choose another key...")
        }
    }
    if (state.showKeyPicker) {
        KeyPickerDialog(
            keys = state.keyFiles?.items.orEmpty(),
            chosenId = state.keyId,
            onPick = { actions.onKey(it.id) },
            onDismiss = actions.onCloseKeyPicker,
        )
    }
}

/** A filterable list of key file names: a full redump set is thousands of files. */
@Composable
private fun KeyPickerDialog(
    keys: List<io.github.tonyp7.ps3dec.storage.Entry>,
    chosenId: String?,
    onPick: (io.github.tonyp7.ps3dec.storage.Entry) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val shown = androidx.compose.runtime.remember(keys, filter) {
        if (filter.isBlank()) keys else keys.filter { it.name.contains(filter.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("key-picker"),
        title = { Text("Choose a key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Filter (${keys.size} keys)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("key-filter"),
                )
                Text("${shown.size} shown", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("key-count"))
                LazyColumn(Modifier.heightIn(max = 360.dp).testTag("key-options")) {
                    items(shown, key = { it.id }) { key ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = key.id == chosenId, role = Role.RadioButton, onClick = { onPick(key) })
                                .testTag("key-option-${key.name}")
                                .focusRing()
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = key.id == chosenId, onClick = null)
                            Text(key.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("key-picker-close").focusRing()) { Text("Close") }
        },
    )
}

@Composable
private fun OverwriteDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("overwrite-dialog"),
        title = { Text("Replace the existing file?") },
        text = {
            Text(
                "$name already exists in the output folder. It is kept until the new file is complete, " +
                    "and replaced only then.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("overwrite-confirm").focusRing()) { Text("Replace") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("overwrite-cancel").focusRing()) { Text("Cancel") }
        },
    )
}

// -- the job -----------------------------------------------------------------------------------

/** Progress of the running job, or the outcome of the last one. */
@Composable
fun JobCard(job: JobSnapshot?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    if (job == null) return
    Card(modifier.fillMaxWidth().testTag("job")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val verb = if (job.mode == Mode.DECRYPT) "Decrypting" else "Encrypting"
            when (job.state) {
                JobState.RUNNING -> {
                    Text("$verb ${job.image}", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { job.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().testTag("progress"))
                    Text(
                        "${(job.fraction * 100).toInt()}% · ${formatSize(job.bytesDone)} of ${formatSize(job.bytesTotal)}",
                        modifier = Modifier.testTag("progress-text"),
                    )
                    Text(
                        "Elapsed ${formatDuration(job.elapsedSeconds)} · ${formatRate(job.bytesPerSecond)}" +
                            (job.etaSeconds?.let { " · ${formatDuration(it)} left" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("progress-stats"),
                    )
                    LogLines(job.log)
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = !job.cancelRequested,
                        modifier = Modifier.testTag("cancel").focusRing(),
                    ) { Text(if (job.cancelRequested) "Cancelling..." else "Cancel") }
                }
                JobState.SUCCEEDED -> {
                    Text("Finished ${job.output}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("outcome"))
                    Text(
                        "${formatSize(job.bytesTotal)} in ${formatDuration(job.elapsedSeconds)}. " +
                            "The file is in your output folder.",
                    )
                    LogLines(job.log)
                }
                JobState.FAILED -> {
                    Text("Failed: ${job.image}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("outcome"))
                    Text(job.error ?: "unknown error", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("outcome-detail"))
                    LogLines(job.log)
                }
                JobState.CANCELLED -> {
                    Text("Cancelled ${job.image}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("outcome"))
                    Text("The partial file was removed.")
                }
            }
        }
    }
}

@Composable
private fun LogLines(lines: List<String>) {
    if (lines.isEmpty()) return
    Column(Modifier.testTag("log")) {
        lines.takeLast(6).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
