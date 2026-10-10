package io.github.tonyp7.ps3dec.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tonyp7.ps3dec.job.JobState
import io.github.tonyp7.ps3dec.runtime.ConversionRuntime
import io.github.tonyp7.ps3dec.storage.FolderRole

/** Chooses between setup, folders and the main screen, and owns the system folder picker. */
@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val job by ConversionRuntime.job.collectAsState()
    val interruption by ConversionRuntime.interruption.collectAsState()
    val context = LocalContext.current

    var pendingRole by remember { mutableStateOf<FolderRole?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val role = pendingRole
        pendingRole = null
        if (uri != null && role != null) vm.chooseFolder(role, uri)
    }
    val choose: (FolderRole) -> Unit = { role ->
        vm.clearFolderError()
        pendingRole = role
        picker.launch(null)
    }

    var notificationsGranted by remember { mutableStateOf(notificationPermissionGranted(context)) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = it
    }
    val notificationsNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted

    // Setup ends with an explicit "Continue", remembered across launches, so the notification step
    // is always offered once even when the third folder choice completes the set.
    val prefs = remember { context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    var setupFinished by rememberSaveable { mutableStateOf(prefs.getBoolean("setup_finished", false)) }
    var showFolders by rememberSaveable { mutableStateOf(false) }
    val running = job?.state == JobState.RUNNING

    // Reload the listings when returning to the app (folders may have changed while away).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.reload()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Ps3DecTheme {
        when {
            !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !state.setupComplete || !setupFinished -> SetupScreen(
                state = state,
                onChoose = choose,
                onFinish = {
                    prefs.edit().putBoolean("setup_finished", true).apply()
                    setupFinished = true
                },
                notificationsNeeded = notificationsNeeded,
                onAllowNotifications = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
            showFolders -> {
                BackHandler { showFolders = false }
                FoldersScreen(state, running, choose, onBack = { showFolders = false })
            }
            else -> MainScreen(
                state = state,
                job = job,
                interruption = interruption,
                actions = MainActions(
                    onSelect = vm::select,
                    onMode = vm::setMode,
                    onKey = vm::setKey,
                    onOpenKeyPicker = vm::openKeyPicker,
                    onCloseKeyPicker = vm::closeKeyPicker,
                    onStart = { vm.start() },
                    onConfirmOverwrite = { vm.start(confirmedOverwrite = true) },
                    onDismissOverwrite = vm::dismissOverwrite,
                    onCancel = vm::cancel,
                    onRefresh = vm::reload,
                    onFolders = { showFolders = true },
                    onChoose = choose,
                    onDismissInterruption = vm::dismissInterruption,
                ),
            )
        }
    }
}

private fun notificationPermissionGranted(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
