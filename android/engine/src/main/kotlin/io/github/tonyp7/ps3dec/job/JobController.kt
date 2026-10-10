package io.github.tonyp7.ps3dec.job

import io.github.tonyp7.ps3dec.engine.ConversionCallbacks
import io.github.tonyp7.ps3dec.engine.ConversionCancelledException
import io.github.tonyp7.ps3dec.engine.ConversionException
import io.github.tonyp7.ps3dec.engine.HEADER_SIZE
import io.github.tonyp7.ps3dec.engine.ImageClassifier
import io.github.tonyp7.ps3dec.engine.ImageConverter
import io.github.tonyp7.ps3dec.engine.ImageInfo
import io.github.tonyp7.ps3dec.engine.ImageKind
import io.github.tonyp7.ps3dec.engine.KeyFiles
import io.github.tonyp7.ps3dec.engine.KeyFormatException
import io.github.tonyp7.ps3dec.engine.Mode
import io.github.tonyp7.ps3dec.storage.Entry
import io.github.tonyp7.ps3dec.storage.Folder
import io.github.tonyp7.ps3dec.storage.Listing
import io.github.tonyp7.ps3dec.storage.Output
import io.github.tonyp7.ps3dec.storage.OutputSession
import io.github.tonyp7.ps3dec.storage.PreFlight
import io.github.tonyp7.ps3dec.storage.StorageException
import io.github.tonyp7.ps3dec.storage.humanSize
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

enum class JobState { RUNNING, SUCCEEDED, FAILED, CANCELLED }

/** What a job looked like at one moment, for the interface. Never contains key material. */
data class JobSnapshot(
    val id: String,
    val state: JobState,
    val image: String,
    val mode: Mode,
    val output: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val elapsedSeconds: Double,
    val bytesPerSecond: Double?,
    val etaSeconds: Double?,
    val log: List<String>,
    val error: String?,
    val cancelRequested: Boolean,
) {
    val fraction: Double get() = if (bytesTotal > 0) minOf(1.0, bytesDone.toDouble() / bytesTotal) else 0.0
}

/** A request to start a job. Items are identified by the ids from the current folder listings. */
data class StartRequest(
    val imageId: String,
    val mode: Mode,
    val keyId: String? = null,
    val overwrite: Boolean = false,
    /** Size of the image as it was listed to the user; if given, the image must still have it. */
    val expectedSize: Long? = null,
)

/** Why a start request was refused. */
class JobRejectedException(val code: Code, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Code {
        JOB_RUNNING,
        NOT_FOUND,
        INVALID_IMAGE,
        MODE_CONFLICT,
        KEY_REQUIRED,
        INVALID_KEY,
        INPUT_CHANGED,
        OUTPUT_EXISTS,
        OUTPUT_NOT_WRITABLE,
        OUTPUT_SAME_AS_INPUT,
        INSUFFICIENT_SPACE,
        UNAVAILABLE,
        IO,
    }
}

/**
 * Remembers that a job is in progress, so that a job the system killed can be recognised on the
 * next launch. Written when a job starts and cleared when it ends in any state.
 */
interface JobMarker {
    fun begin(image: String)

    fun clear()

    object None : JobMarker {
        override fun begin(image: String) {}
        override fun clear() {}
    }
}

/**
 * Runs at most one conversion at a time, in the background, over three folders.
 *
 * Start requests are validated completely before anything is written (the `conversion-job` and
 * `key-handling` rules), the conversion runs on its own thread, and the last job's final state is
 * kept until the next job starts.
 */
class JobController(
    private val images: Folder,
    private val keys: Folder,
    private val output: Folder,
    private val parallelism: Int = ImageConverter.defaultParallelism(),
    private val marker: JobMarker = JobMarker.None,
    private val clock: () -> Long = System::nanoTime,
) {
    private class Job(
        val id: String,
        val image: String,
        val mode: Mode,
        val bytesTotal: Long,
        val startedNanos: Long,
    ) {
        @Volatile var state = JobState.RUNNING
        @Volatile var error: String? = null
        @Volatile var finishedNanos: Long? = null
        @Volatile var cancelRequested = false
        @Volatile var bytesPerSecond: Double? = null
        @Volatile var etaSeconds: Double? = null
        val bytesDone = AtomicLong()
        val log = ArrayDeque<String>()
        val samples = ArrayDeque<Pair<Long, Long>>()
    }

    private val lock = Any()
    private var job: Job? = null
    private var thread: Thread? = null
    private val listeners = CopyOnWriteArrayList<(JobSnapshot) -> Unit>()

    // -- queries ----------------------------------------------------------------------------

    val isRunning: Boolean get() = job?.state == JobState.RUNNING

    /** The current or most recent job, or null if none has run since the app started. */
    fun snapshot(): JobSnapshot? = job?.let(::snapshotOf)

    /** Be told about every change of the job; the returned function removes the listener. */
    fun addListener(listener: (JobSnapshot) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    // -- commands ---------------------------------------------------------------------------

    /**
     * Validate [request] and start the job.
     *
     * @throws JobRejectedException with the specific reason; nothing persists in that case
     */
    fun start(request: StartRequest): JobSnapshot {
        synchronized(lock) {
            if (isRunning) throw JobRejectedException(JobRejectedException.Code.JOB_RUNNING, "another job is already running")
            val plan = try {
                validate(request)
            } catch (e: JobRejectedException) {
                throw e
            } catch (e: Exception) {
                throw JobRejectedException(
                    JobRejectedException.Code.IO,
                    "could not prepare the job: ${e.message ?: e.javaClass.simpleName}",
                    e,
                )
            }
            val job = Job(
                id = UUID.randomUUID().toString().take(12),
                image = plan.entry.name,
                mode = request.mode,
                bytesTotal = plan.entry.size,
                startedNanos = clock(),
            )
            this.job = job
            runCatching { marker.begin(plan.entry.name) }
            val worker = Thread({ run(job, plan) }, "ps3dec-job-${job.id}")
            worker.isDaemon = true
            thread = worker
            worker.start()
            return snapshotOf(job).also(::notifyListeners)
        }
    }

    /**
     * Stop the running job (no effect when idle) and wait, for a bounded time, until it has
     * settled: output removed, state set to cancelled.
     */
    fun cancel(waitMillis: Long = 10_000) {
        val running: Job
        val worker: Thread?
        synchronized(lock) {
            running = job?.takeIf { it.state == JobState.RUNNING } ?: return
            running.cancelRequested = true
            worker = thread
        }
        notifyListeners(snapshotOf(running))
        worker?.join(waitMillis)
    }

    /** Wait for the current job to end; for tests and orderly shutdown. */
    fun awaitFinished(waitMillis: Long = 30_000): Boolean {
        val worker = synchronized(lock) { thread } ?: return true
        worker.join(waitMillis)
        return !worker.isAlive
    }

    // -- validation -------------------------------------------------------------------------

    private class Plan(val entry: Entry, val info: ImageInfo, val key: ByteArray?, val session: OutputSession)

    private fun validate(request: StartRequest): Plan {
        val imageListing = try {
            images.list()
        } catch (e: StorageException) {
            throw rejected(e)
        }
        val entry = try {
            val candidates = Listing.withExtension(imageListing, Listing.IMAGE_EXTENSION)
            val found = Listing.resolve(candidates, request.imageId, "image")
            request.expectedSize?.let { PreFlight.checkInput(imageListing, found.id, it) } ?: found
        } catch (e: StorageException) {
            throw rejected(e)
        }

        val info = classify(entry)
        if (!info.valid) {
            throw JobRejectedException(
                JobRejectedException.Code.INVALID_IMAGE,
                "${entry.name} cannot be used: ${info.reason}",
            )
        }
        if (info.kind == ImageKind.THREE_K3Y_DECRYPTED && request.mode == Mode.DECRYPT) {
            throw JobRejectedException(
                JobRejectedException.Code.MODE_CONFLICT,
                "${entry.name} is a 3k3y image that is already decrypted",
            )
        }
        if (info.kind == ImageKind.THREE_K3Y_ENCRYPTED && request.mode == Mode.ENCRYPT) {
            throw JobRejectedException(
                JobRejectedException.Code.MODE_CONFLICT,
                "${entry.name} is a 3k3y image that is already encrypted",
            )
        }

        val key = if (info.kind == ImageKind.STANDARD) loadKey(request.keyId) else null

        val outputListing = try {
            output.list()
        } catch (e: StorageException) {
            throw rejected(e)
        }
        if (images.namespace == output.namespace && outputListing.any { it.id == entry.id }) {
            throw JobRejectedException(
                JobRejectedException.Code.OUTPUT_SAME_AS_INPUT,
                "the output would overwrite the input image; use separate folders",
            )
        }

        val session = try {
            Output.begin(output, entry.name, request.overwrite)
        } catch (e: StorageException) {
            throw rejected(e)
        }
        try {
            PreFlight.checkSpace(entry.size, session.freeBytes)
        } catch (e: StorageException) {
            session.abort()
            throw rejected(e)
        }
        return Plan(entry, info, key, session)
    }

    private fun classify(entry: Entry): ImageInfo {
        val header = try {
            images.openRead(entry).use { readUpTo(it, HEADER_SIZE) }
        } catch (e: IOException) {
            return ImageInfo.invalid(entry.size, "unreadable: ${e.message ?: e.javaClass.simpleName}")
        }
        return ImageClassifier.classify(entry.size, header)
    }

    private fun loadKey(keyId: String?): ByteArray {
        if (keyId.isNullOrEmpty()) {
            throw JobRejectedException(JobRejectedException.Code.KEY_REQUIRED, "a key is required for this image")
        }
        val keyEntry = try {
            Listing.resolve(Listing.withExtension(keys.list(), Listing.KEY_EXTENSION), keyId, "key")
        } catch (e: StorageException) {
            throw rejected(e)
        }
        val raw = try {
            keys.openRead(keyEntry).use { readUpTo(it, KeyFiles.MAX_KEY_FILE_BYTES + 1) }
        } catch (e: IOException) {
            throw JobRejectedException(
                JobRejectedException.Code.INVALID_KEY,
                "key ${keyEntry.name} is unreadable: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        val hex = try {
            KeyFiles.parseKey(raw)
        } catch (e: KeyFormatException) {
            throw JobRejectedException(
                JobRejectedException.Code.INVALID_KEY,
                "key ${keyEntry.name} is invalid: ${e.message}",
            )
        }
        return ByteArray(16) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private fun rejected(e: StorageException): JobRejectedException {
        val code = when (e.code) {
            StorageException.Code.UNAVAILABLE -> JobRejectedException.Code.UNAVAILABLE
            StorageException.Code.NOT_WRITABLE, StorageException.Code.NAME_CHANGED ->
                JobRejectedException.Code.OUTPUT_NOT_WRITABLE
            StorageException.Code.OUTPUT_EXISTS -> JobRejectedException.Code.OUTPUT_EXISTS
            StorageException.Code.NOT_FOUND -> JobRejectedException.Code.NOT_FOUND
            StorageException.Code.INPUT_CHANGED -> JobRejectedException.Code.INPUT_CHANGED
            StorageException.Code.INSUFFICIENT_SPACE -> JobRejectedException.Code.INSUFFICIENT_SPACE
            StorageException.Code.FILE_TOO_LARGE, StorageException.Code.IO -> JobRejectedException.Code.IO
        }
        return JobRejectedException(code, e.message ?: "storage error", e)
    }

    // -- the job ----------------------------------------------------------------------------

    private fun run(job: Job, plan: Plan) {
        var committed = false
        try {
            val keyHex = plan.key?.joinToString("") { "%02x".format(it) }
            val callbacks = ConversionCallbacks(
                onDiagnostic = { line -> addLog(job, line, keyHex) },
                onProgress = { done -> progress(job, done) },
                isCancelled = { job.cancelRequested },
            )
            images.openRead(plan.entry).use { input ->
                ImageConverter.convert(
                    input, plan.entry.size, plan.session.channel, job.mode, null, callbacks, parallelism, discKey = plan.key,
                )
            }

            val size = plan.session.partSize()
            when {
                job.cancelRequested -> finish(job, JobState.CANCELLED, null)
                size == null -> finish(job, JobState.FAILED, "the output file cannot be seen to check its size")
                size != plan.entry.size ->
                    finish(job, JobState.FAILED, "output is $size bytes but the image is ${plan.entry.size} bytes")
                else -> {
                    plan.session.commit()
                    committed = true
                    job.bytesDone.set(job.bytesTotal)
                    job.etaSeconds = 0.0
                    finish(job, JobState.SUCCEEDED, null)
                }
            }
        } catch (e: ConversionCancelledException) {
            finish(job, JobState.CANCELLED, null)
        } catch (e: ConversionException) {
            finish(job, JobState.FAILED, PreFlight.explain(e)?.message ?: e.message ?: "conversion failed")
        } catch (e: StorageException) {
            finish(job, JobState.FAILED, e.message ?: "storage error")
        } catch (e: IOException) {
            finish(job, JobState.FAILED, PreFlight.explain(e)?.message ?: "I/O error: ${e.message}")
        } catch (e: Throwable) {
            finish(job, JobState.FAILED, "internal error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (!committed) plan.session.abort()
            runCatching { marker.clear() }
            // A job never stays "running" once its work has ended, whatever happened above.
            if (job.state == JobState.RUNNING) finish(job, JobState.FAILED, "the job ended unexpectedly")
        }
    }

    private fun finish(job: Job, state: JobState, error: String?) {
        if (job.state != JobState.RUNNING) return
        job.error = error
        job.finishedNanos = clock()
        job.state = state
        notifyListeners(snapshotOf(job))
    }

    private fun addLog(job: Job, line: String, keyHex: String?) {
        val text = line.trim()
        if (text.isEmpty()) return
        val lowered = text.lowercase()
        if ("decryption key" in lowered || (keyHex != null && keyHex in lowered)) return // never relay key material
        synchronized(job.log) {
            job.log.addLast(text)
            while (job.log.size > LOG_LINES) job.log.removeFirst()
        }
        notifyListeners(snapshotOf(job))
    }

    private fun progress(job: Job, done: Long) {
        val now = clock()
        job.bytesDone.set(minOf(done, job.bytesTotal))
        synchronized(job.samples) {
            job.samples.addLast(now to done)
            while (job.samples.size > 2 && now - job.samples.first().first > THROUGHPUT_WINDOW_NANOS) {
                job.samples.removeFirst()
            }
            val (t0, b0) = job.samples.first()
            val (t1, b1) = job.samples.last()
            if (t1 > t0 && b1 > b0) {
                val rate = (b1 - b0) / ((t1 - t0) / 1e9)
                job.bytesPerSecond = rate
                job.etaSeconds = Math.round((job.bytesTotal - job.bytesDone.get()) / rate * 10) / 10.0
            }
        }
        notifyListeners(snapshotOf(job))
    }

    private fun snapshotOf(job: Job): JobSnapshot {
        val end = job.finishedNanos ?: clock()
        return JobSnapshot(
            id = job.id,
            state = job.state,
            image = job.image,
            mode = job.mode,
            output = job.image,
            bytesDone = job.bytesDone.get(),
            bytesTotal = job.bytesTotal,
            elapsedSeconds = Math.round((end - job.startedNanos) / 1e7) / 100.0,
            bytesPerSecond = job.bytesPerSecond,
            etaSeconds = job.etaSeconds,
            log = synchronized(job.log) { job.log.toList() },
            error = job.error,
            cancelRequested = job.cancelRequested,
        )
    }

    private fun notifyListeners(snapshot: JobSnapshot) {
        for (l in listeners) runCatching { l(snapshot) }
    }

    private fun readUpTo(channel: ReadableByteChannel, max: Int): ByteArray {
        val bb = ByteBuffer.allocate(max)
        while (bb.hasRemaining()) {
            if (channel.read(bb) < 0) break
        }
        return bb.array().copyOf(bb.position())
    }

    private companion object {
        const val LOG_LINES = 50
        const val THROUGHPUT_WINDOW_NANOS = 5_000_000_000L
    }
}

/** Sizes the way the interface shows them; re-exported so UI code needs one import. */
fun formatBytes(bytes: Long): String = humanSize(bytes)
