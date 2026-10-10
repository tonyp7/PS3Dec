package io.github.tonyp7.ps3dec.runtime

import android.content.Context
import io.github.tonyp7.ps3dec.job.JobController
import io.github.tonyp7.ps3dec.job.JobRejectedException
import io.github.tonyp7.ps3dec.job.JobSnapshot
import io.github.tonyp7.ps3dec.job.StartRequest
import io.github.tonyp7.ps3dec.storage.Entry
import io.github.tonyp7.ps3dec.storage.Folder
import io.github.tonyp7.ps3dec.storage.Output
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** A [Folder] whose target can be replaced, so one controller outlives folder reconfiguration. */
class SwitchableFolder : Folder {
    @Volatile
    var target: Folder? = null

    private fun current(): Folder =
        target ?: throw io.github.tonyp7.ps3dec.storage.StorageException(
            io.github.tonyp7.ps3dec.storage.StorageException.Code.UNAVAILABLE,
            "the folder has not been chosen",
        )

    override val namespace: String get() = target?.namespace.orEmpty()

    override fun list(): List<Entry> = current().list()

    override fun openRead(entry: Entry): ReadableByteChannel = current().openRead(entry)

    override fun create(name: String): Entry = current().create(name)

    override fun openWrite(entry: Entry): WritableByteChannel = current().openWrite(entry)

    override fun rename(entry: Entry, newName: String): Entry = current().rename(entry, newName)

    override fun delete(entry: Entry) = current().delete(entry)

    override fun freeSpace(onVolumeOf: Entry): Long? = current().freeSpace(onVolumeOf)
}

/**
 * The process-wide home of the single conversion job. The user interface starts and observes jobs
 * here; [ConversionService] keeps the process alive and visible while one runs.
 */
object ConversionRuntime {
    private val images = SwitchableFolder()
    private val keys = SwitchableFolder()
    private val output = SwitchableFolder()

    private var controller: JobController? = null
    private val state = MutableStateFlow<JobSnapshot?>(null)
    private val interrupted = MutableStateFlow<Interruption?>(null)

    /** The current or most recent job; null until one has run in this process. */
    val job: StateFlow<JobSnapshot?> get() = state

    /** A job that was cut short and has not been shown to the user yet; null when there is none. */
    val interruption: StateFlow<Interruption?> get() = interrupted

    /**
     * Run once when the process starts. If the previous process died while a job was in progress
     * (killed by the system, or stopped), remember that so the user is told, and remove the
     * partial output it left. Temporary files are only removed when no job is running here, so a
     * live job's output is never touched.
     */
    suspend fun recoverInterrupted(context: Context, outputFolder: Folder?) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val marker = PrefsJobMarker(app)
        if (!controller(app).isRunning) {
            marker.interruptedImage()?.let { image ->
                marker.recordInterruption(image, "the app was closed or stopped by the system while converting")
                marker.clear()
            }
            outputFolder?.let { runCatching { Output.sweep(it) } }
        }
        interrupted.value = marker.pendingInterruption()
    }

    /** The user has seen the interruption notice. */
    fun dismissInterruption(context: Context) {
        PrefsJobMarker(context.applicationContext).clearInterruption()
        interrupted.value = null
    }

    @Synchronized
    fun controller(context: Context): JobController =
        controller ?: JobController(
            images, keys, output,
            marker = PrefsJobMarker(context.applicationContext),
        ).also { created ->
            created.addListener { state.value = it }
            controller = created
        }

    /** Use these folders for the next jobs. Refused while a job is running. */
    @Synchronized
    fun configure(imagesFolder: Folder?, keysFolder: Folder?, outputFolder: Folder?) {
        check(controller?.isRunning != true) { "folders cannot change while a job is running" }
        images.target = imagesFolder
        keys.target = keysFolder
        output.target = outputFolder
    }

    /** The folders the controller works on, for listing in the interface. */
    val imageFolder: Folder get() = images
    val keyFolder: Folder get() = keys
    val outputFolder: Folder get() = output

    /**
     * Validate and start a job, and put the process in the foreground for its duration.
     *
     * @throws JobRejectedException if the request is refused; nothing persists then
     */
    suspend fun start(context: Context, request: StartRequest): JobSnapshot = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val c = controller(app)
        val snapshot = c.start(request)
        try {
            ConversionService.start(app)
        } catch (e: Exception) {
            c.cancel()
            throw JobRejectedException(
                JobRejectedException.Code.IO,
                "could not run in the background: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        snapshot
    }

    suspend fun cancel(context: Context) = withContext(Dispatchers.IO) { controller(context.applicationContext).cancel() }
}
