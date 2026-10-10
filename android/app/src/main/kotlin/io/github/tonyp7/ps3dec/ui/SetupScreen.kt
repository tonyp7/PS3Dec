package io.github.tonyp7.ps3dec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

internal fun roleTitle(role: FolderRole) = when (role) {
    FolderRole.IMAGES -> "Disc images"
    FolderRole.KEYS -> "Keys"
    FolderRole.OUTPUT -> "Output"
}

internal fun roleExplanation(role: FolderRole) = when (role) {
    FolderRole.IMAGES -> "The folder holding your PS3 disc images (.iso files). The app only reads from it."
    FolderRole.KEYS -> "The folder holding the matching key files (.dkey). The app only reads from it."
    FolderRole.OUTPUT -> "Where converted images are written. It must be a different folder from the disc images."
}

/** Shown on first launch, until the three folders are chosen. */
@Composable
fun SetupScreen(
    state: UiState,
    onChoose: (FolderRole) -> Unit,
    onFinish: () -> Unit,
    notificationsNeeded: Boolean,
    onAllowNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Set up PS3Dec", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Choose three folders. Android does not let an app use the root of the storage or the " +
                    "Download folder, so pick (or create) a subfolder such as PS3/iso.",
                style = MaterialTheme.typography.bodyLarge,
            )
            state.folders.forEachIndexed { index, folder ->
                Card(Modifier.fillMaxWidth().testTag("setup-${folder.role.key}").focusRing()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}. ${roleTitle(folder.role)}", style = MaterialTheme.typography.titleMedium)
                        Text(roleExplanation(folder.role), style = MaterialTheme.typography.bodyMedium)
                        if (folder.label != null) {
                            Text(
                                if (folder.state is FolderState.Ready) "Chosen: ${folder.label}" else "${folder.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Button(onClick = { onChoose(folder.role) }) {
                            Text(if (folder.label == null) "Choose folder" else "Choose a different folder")
                        }
                    }
                }
            }
            state.folderError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("setup-error"))
            }
            if (notificationsNeeded) {
                Card(Modifier.fillMaxWidth().testTag("setup-notifications").focusRing()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Progress notification (optional)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A conversion can take several minutes. A notification shows its progress and lets " +
                                "you cancel it from outside the app. Conversions work without it.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = onAllowNotifications) { Text("Allow notifications") }
                    }
                }
            }
            Row {
                Button(onClick = onFinish, enabled = state.setupComplete, modifier = Modifier.testTag("setup-finish")) {
                    Text("Continue")
                }
            }
            if (!state.setupComplete) {
                Text(
                    "Choose all three folders to continue.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("setup-hint"),
                )
            }
        }
    }
}
