package io.github.tonyp7.ps3dec.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.tonyp7.ps3dec.MainActivity
import io.github.tonyp7.ps3dec.R
import io.github.tonyp7.ps3dec.engine.Mode
import io.github.tonyp7.ps3dec.job.JobSnapshot
import io.github.tonyp7.ps3dec.job.JobState
import io.github.tonyp7.ps3dec.job.formatBytes

/**
 * Keeps the process in the foreground, with a progress notification and a wake lock, while a
 * conversion runs. The job itself lives in [ConversionRuntime]; this service only provides the
 * environment it needs to survive the app leaving the screen.
 */
class ConversionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var removeListener: (() -> Unit)? = null
    private var lastUpdate = 0L
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                // Cancelling can wait on I/O; keep it off the main thread.
                Thread({ ConversionRuntime.controller(applicationContext).cancel() }, "ps3dec-cancel").start()
            }
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (started) return
        started = true
        ensureChannel()
        val controller = ConversionRuntime.controller(applicationContext)
        val first = controller.snapshot()
        startForeground(
            NOTIFICATION_ID,
            build(first),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        acquireWakeLock()
        removeListener = controller.addListener { update(it) }
        // The job may have ended before this service got going.
        controller.snapshot()?.takeIf { it.state != JobState.RUNNING }?.let { finish(it) }
    }

    private fun update(snapshot: JobSnapshot) {
        if (snapshot.state != JobState.RUNNING) {
            finish(snapshot)
            return
        }
        val now = System.nanoTime()
        if (now - lastUpdate < MIN_UPDATE_INTERVAL_NANOS) return
        lastUpdate = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(snapshot))
    }

    private fun finish(snapshot: JobSnapshot) {
        removeListener?.invoke()
        removeListener = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, buildResult(snapshot))
        stopSelf()
    }

    /**
     * The system's time limit for data-sync foreground services was reached (Android 15 and later).
     * The work cannot continue, so stop it cleanly and remember why, so the user is told.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val controller = ConversionRuntime.controller(applicationContext)
        controller.snapshot()?.takeIf { it.state == JobState.RUNNING }?.let {
            PrefsJobMarker(applicationContext)
                .recordInterruption(it.image, "the system's time limit for background work was reached")
        }
        Thread({
            controller.cancel()
            stopSelf()
        }, "ps3dec-timeout").start()
    }

    override fun onDestroy() {
        removeListener?.invoke()
        removeListener = null
        releaseWakeLock()
        super.onDestroy()
    }

    // -- wake lock --------------------------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ps3dec:conversion").apply {
            setReferenceCounted(false)
            acquire(MAX_JOB_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // -- notifications ----------------------------------------------------------------------

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(snapshot: JobSnapshot?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (snapshot == null) {
            return builder.setContentTitle(getString(R.string.app_name)).setProgress(0, 0, true).build()
        }
        val verb = if (snapshot.mode == Mode.DECRYPT) "Decrypting" else "Encrypting"
        val percent = (snapshot.fraction * 100).toInt()
        val eta = snapshot.etaSeconds?.let { " · ${formatDuration(it)} left" }.orEmpty()
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ConversionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return builder
            .setContentTitle("$verb ${snapshot.image}")
            .setContentText("$percent% · ${formatBytes(snapshot.bytesDone)} of ${formatBytes(snapshot.bytesTotal)}$eta")
            .setProgress(100, percent, false)
            .addAction(0, getString(R.string.cancel), cancel)
            .build()
    }

    private fun buildResult(snapshot: JobSnapshot): Notification {
        val text = when (snapshot.state) {
            JobState.SUCCEEDED -> "Finished ${snapshot.output}"
            JobState.CANCELLED -> "Cancelled ${snapshot.image}"
            else -> "Failed: ${snapshot.error ?: "unknown error"}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
            .also { Log.i(TAG, "job ${snapshot.state}: $text") }
    }

    private fun formatDuration(seconds: Double): String {
        val s = seconds.toLong()
        return if (s >= 3600) "%dh %02dm".format(s / 3600, s % 3600 / 60) else "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val TAG = "ConversionService"
        const val ACTION_CANCEL = "io.github.tonyp7.ps3dec.CANCEL"
        private const val CHANNEL_ID = "conversion"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_NOTIFICATION_ID = 2
        private const val MIN_UPDATE_INTERVAL_NANOS = 500_000_000L
        private const val MAX_JOB_MILLIS = 12L * 60 * 60 * 1000

        /** Start the foreground service; the caller must be in the foreground. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ConversionService::class.java))
        }
    }
}
