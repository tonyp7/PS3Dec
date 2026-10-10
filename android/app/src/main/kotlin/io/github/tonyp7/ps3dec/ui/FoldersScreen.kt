package io.github.tonyp7.ps3dec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.tonyp7.ps3dec.storage.FolderRole
import io.github.tonyp7.ps3dec.storage.FolderState

/** The configured folders and their availability, with a way to choose any of them again. */
@Composable
fun FoldersScreen(
    state: UiState,
    jobRunning: Boolean,
    onChoose: (FolderRole) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Folders", style = MaterialTheme.typography.headlineMedium)
            if (jobRunning) {
                Text(
                    "Folders cannot be changed while a conversion is running.",
                    modifier = Modifier.testTag("folders-locked"),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.folders.forEach { folder ->
                Card(Modifier.fillMaxWidth().testTag("folder-${folder.role.key}").focusRing()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(roleTitle(folder.role), style = MaterialTheme.typography.titleMedium)
                        Text(folder.label ?: "Not chosen yet", style = MaterialTheme.typography.bodyLarge)
                        when (val s = folder.state) {
                            is FolderState.Unavailable -> Text(
                                "Unavailable: ${s.reason}",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("folder-${folder.role.key}-unavailable"),
                            )
                            FolderState.NotChosen -> Unit
                            FolderState.Ready -> Text("Available", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { onChoose(folder.role) }, enabled = !jobRunning) {
                            Text(if (folder.state is FolderState.Unavailable) "Choose again" else "Change")
                        }
                    }
                }
            }
            state.folderError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("folders-error")) }
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
