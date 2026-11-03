package com.winlator.cmod.linux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.winlator.cmod.R
import com.winlator.cmod.XServerDisplayActivity

/** The ongoing notification of a version download: percent, speed and what is left, so the app can be left while Steam fetches the files. */
object DownloadNotification {
    private const val CHANNEL = "skynet_downloads"
    private const val ID = 101

    @JvmStatic
    fun update(context: Context, title: String, status: SteamVersionDownloader.Status) {
        val manager = NotificationManagerCompat.from(context)
        if (status.state == SteamDownloadJob.State.CANCELLED) {
            manager.cancel(ID)
            return
        }
        val system = context.getSystemService(NotificationManager::class.java)
        if (system.getNotificationChannel(CHANNEL) == null) {
            system.createNotificationChannel(NotificationChannel(CHANNEL, "Game version downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(context, 1, Intent(context, XServerDisplayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE)
        val finished = status.state == SteamDownloadJob.State.DONE || status.state == SteamDownloadJob.State.FAILED
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ab_gear_0011)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setAutoCancel(finished)
        val fraction = status.fraction()
        when (status.state) {
            SteamDownloadJob.State.DONE -> builder.setContentText("Ready: the shortcut is in your library")
            SteamDownloadJob.State.FAILED -> builder.setContentText(status.error.ifEmpty { "The download failed" })
            else -> {
                builder.setContentText(status.message)
                builder.setProgress(1000, if (fraction < 0) 0 else (fraction * 1000).toInt(), fraction < 0)
            }
        }
        runCatching { manager.notify(ID, builder.build()) }
    }

    @JvmStatic
    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
    }
}
