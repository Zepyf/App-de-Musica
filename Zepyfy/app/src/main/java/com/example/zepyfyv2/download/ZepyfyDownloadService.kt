package com.example.zepyfyv2.download

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.example.zepyfyv2.AudioCache
import com.example.zepyfyv2.R

/**
 * Servicio que ejecuta las descargas sin conexión en segundo plano, con una
 * notificación de progreso mientras haya descargas en curso.
 *
 * Sin Scheduler: si el sistema corta una descarga (sin red, app cerrada), se
 * retoma la próxima vez que se abra la app (OfflineDownloads.sync arranca el
 * servicio si hay descargas pendientes).
 */
@OptIn(UnstableApi::class)
class ZepyfyDownloadService : DownloadService(
    NOTIFICATION_ID,
    DownloadService.DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    0
) {

    private val notificationHelper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = AudioCache.downloadManager(this)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification = notificationHelper.buildProgressNotification(
        this,
        R.drawable.ic_download,
        null,
        getString(R.string.download_notification_text),
        downloads,
        notMetRequirements
    )

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "offline_downloads"
    }
}
