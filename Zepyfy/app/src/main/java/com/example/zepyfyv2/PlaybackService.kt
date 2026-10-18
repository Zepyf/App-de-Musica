@file:OptIn(UnstableApi::class)

package com.example.zepyfyv2

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.zepyfyv2.media.QueueStore
import com.example.zepyfyv2.media.SmartShuffleOrder

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    private val handler = Handler(Looper.getMainLooper())

    // Mientras suena, guardar la posición cada pocos segundos por si el sistema mata la app
    private val savePositionRunnable = object : Runnable {
        override fun run() {
            mediaSession?.player?.let { QueueStore.savePosition(this@PlaybackService, it) }
            handler.postDelayed(this, SAVE_POSITION_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Todo el audio pasa por las cachés en disco: descargas sin conexión y caché
        // de reproducción (ver AudioCache)
        val mediaSourceFactory = DefaultMediaSourceFactory(AudioCache.playbackDataSourceFactory(this))

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ALL
            }

        player.setShuffleOrder(
            SmartShuffleOrder(
                length = player.mediaItemCount,
                getCurrentPlayingIndex = { player.currentMediaItemIndex }
            )
        )

        restoreQueue(player)
        player.addListener(queuePersistenceListener(player))

        // getLaunchIntentForPackage puede devolver null: en ese caso se abre MainActivity
        val launchIntent = packageManager?.getLaunchIntentForPackage(packageName)
        val sessionActivityPendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    /**
     * Recupera la última cola guardada (en pausa, en la canción y segundo donde se dejó).
     * Así, aunque Android cierre la app en segundo plano, al volver está todo como estaba.
     */
    private fun restoreQueue(player: ExoPlayer) {
        val saved = QueueStore.restore(this) ?: return
        player.repeatMode = saved.repeatMode
        player.shuffleModeEnabled = saved.shuffle
        player.setMediaItems(saved.items, saved.index, saved.positionMs)
        player.setShuffleOrder(
            SmartShuffleOrder(
                length = saved.items.size,
                firstIndex = saved.index,
                getCurrentPlayingIndex = { player.currentMediaItemIndex }
            )
        )
        player.prepare() // prepara sin reproducir: playWhenReady sigue en false
    }

    private fun queuePersistenceListener(player: ExoPlayer) = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // Cola nueva, canciones añadidas ("reproducir a continuación"), quitadas o movidas
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                QueueStore.saveQueue(this@PlaybackService, player)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            QueueStore.savePosition(this@PlaybackService, player)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                QueueStore.savePosition(this@PlaybackService, player)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            QueueStore.savePosition(this@PlaybackService, player)
            handler.removeCallbacks(savePositionRunnable)
            if (isPlaying) handler.postDelayed(savePositionRunnable, SAVE_POSITION_INTERVAL_MS)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            QueueStore.saveModes(this@PlaybackService, player)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            QueueStore.saveModes(this@PlaybackService, player)
            if (shuffleModeEnabled && player.mediaItemCount > 0) {
                // Conservar la canción actual como la primera de la secuencia aleatoria
                player.setShuffleOrder(
                    SmartShuffleOrder(
                        length = player.mediaItemCount,
                        firstIndex = player.currentMediaItemIndex,
                        getCurrentPlayingIndex = { player.currentMediaItemIndex }
                    )
                )
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        handler.removeCallbacks(savePositionRunnable)
        mediaSession?.run {
            QueueStore.savePosition(this@PlaybackService, player)
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        private const val SAVE_POSITION_INTERVAL_MS = 10_000L
    }
}
