package io.github.tonyp7.ps3dec

import android.app.Application
import io.github.tonyp7.ps3dec.runtime.ConversionRuntime
import io.github.tonyp7.ps3dec.storage.FolderConfig
import io.github.tonyp7.ps3dec.storage.FolderRole
import io.github.tonyp7.ps3dec.storage.FolderState
import io.github.tonyp7.ps3dec.storage.SafFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PS3DecApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            // If the last process died mid-job, tell the user and clean up its partial output.
            val config = FolderConfig(applicationContext)
            val output = config.get(FolderRole.OUTPUT)
                ?.takeIf { config.state(FolderRole.OUTPUT) == FolderState.Ready }
                ?.let { SafFolder(applicationContext, it) }
            ConversionRuntime.recoverInterrupted(applicationContext, output)
        }
    }
}
