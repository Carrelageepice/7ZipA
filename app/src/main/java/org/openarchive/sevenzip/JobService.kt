package org.openarchive.sevenzip

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Long jobs (extract, create, test) run under a foreground service. That does
 * two things: Android keeps the app's process alive while the screen is off or
 * you are in another app, and a notification shows progress with a Cancel
 * button. The work itself still runs in the app; the service is just the
 * "this app is busy, please don't kill it" signal.
 */
class JobService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            JobControl.ACTION_CANCEL -> {
                JobControl.cancelled = true
            }
            JobControl.ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                instance = this
                val title = intent?.getStringExtra(JobControl.EXTRA_TITLE) ?: "Working"
                val n = JobControl.build(this, title, "Starting...", 0, 0)
                try {
                    val type = if (Build.VERSION.SDK_INT >= 29) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    } else {
                        0
                    }
                    ServiceCompat.startForeground(this, JobControl.ONGOING_ID, n, type)
                } catch (_: Exception) {
                    // Could not go foreground (e.g. restricted background start).
                    // The job still runs; it just may be stopped if the app is closed.
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: JobService? = null
    }
}

object JobControl {
    const val ACTION_START = "org.openarchive.sevenzip.JOB_START"
    const val ACTION_CANCEL = "org.openarchive.sevenzip.JOB_CANCEL"
    const val ACTION_STOP = "org.openarchive.sevenzip.JOB_STOP"
    const val EXTRA_TITLE = "title"
    const val CHANNEL = "jobs"
    const val ONGOING_ID = 1
    const val DONE_ID = 2

    /** Set by the notification's Cancel button; progress callbacks should honour it. */
    @Volatile
    var cancelled = false

    private var title = ""
    private var lastUpdate = 0L
    private var running = 0

    /** Call before starting a long job (any thread). */
    @Synchronized
    fun begin(context: Context, jobTitle: String) {
        cancelled = false
        title = jobTitle
        lastUpdate = 0L
        running++
        try {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, JobService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_TITLE, jobTitle)
            )
        } catch (_: Exception) {
            // Not allowed right now; the job still runs without the notification.
        }
    }

    /** Call from the progress callback. Cheap: updates at most once a second. */
    fun progress(context: Context, done: Long, total: Long, text: String) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastUpdate < 1000) return
            lastUpdate = now
        }
        if (JobService.instance == null) return
        notify(context, ONGOING_ID, build(context, title, text, done, total))
    }

    /**
     * Call when the job is over (success, failure or cancel). Shows a short
     * result notification if the user is not looking at the app.
     */
    @Synchronized
    fun end(context: Context, resultText: String) {
        val app = context.applicationContext
        if (running > 0) running--
        if (running > 0) return  // another job is still going
        try {
            app.stopService(Intent(app, JobService::class.java))
        } catch (_: Exception) {
            // Service was not running.
        }
        if (!appInForeground(app)) {
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(resultText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(resultText))
                .setContentIntent(openAppIntent(app))
                .setAutoCancel(true)
                .build()
            notify(app, DONE_ID, n)
        }
    }

    internal fun build(
        context: Context, title: String, text: String, done: Long, total: Long
    ): Notification {
        ensureChannel(context)
        val cancelIntent = PendingIntent.getService(
            context, 1,
            Intent(context, JobService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "Cancel", cancelIntent)
        if (total > 0) {
            b.setProgress(1000, ((done.toDouble() / total) * 1000).toInt().coerceIn(0, 1000), false)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Archive jobs", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notify(context: Context, id: Int, n: Notification) {
        // Android 13+: without the permission the notification is simply not shown.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (_: Exception) {
            // Notifications are a nicety; never fail a job over them.
        }
    }

    private fun appInForeground(context: Context): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
