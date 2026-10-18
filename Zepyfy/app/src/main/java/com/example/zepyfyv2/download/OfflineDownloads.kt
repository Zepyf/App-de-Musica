package com.example.zepyfyv2.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.example.zepyfyv2.AudioCache
import com.example.zepyfyv2.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Álbumes marcados para escuchar sin conexión.
 *
 * Lo que se guarda (en SharedPreferences) es solo QUÉ está marcado. Las canciones
 * a descargar se calculan en cada [sync] a partir del catálogo, así que si añades
 * una canción a un álbum marcado se descarga sola, y si la quitas del álbum
 * se borra.
 */
@OptIn(UnstableApi::class)
object OfflineDownloads {

    private const val TAG = "OfflineDownloads"
    private const val PREFS = "offline_downloads"
    private const val KEY_MARKED = "marked_keys"

    fun albumKey(albumId: String) = "album:$albumId"

    private val _markedKeys = MutableStateFlow<Set<String>>(emptySet())
    private var loaded = false

    /** Claves marcadas ("album:<id>"). */
    fun markedKeys(context: Context): StateFlow<Set<String>> {
        ensureLoaded(context)
        return _markedKeys.asStateFlow()
    }

    fun isMarked(context: Context, key: String): Boolean {
        ensureLoaded(context)
        return key in _markedKeys.value
    }

    /** Marca o desmarca. La descarga o el borrado real lo hace [sync]. */
    fun setMarked(context: Context, key: String, marked: Boolean) {
        ensureLoaded(context)
        val updated = if (marked) _markedKeys.value + key else _markedKeys.value - key
        if (updated == _markedKeys.value) return
        _markedKeys.value = updated
        prefs(context).edit().putStringSet(KEY_MARKED, updated).apply()
    }

    /**
     * Al cerrar sesión: olvida todo lo marcado y borra todas las canciones descargadas,
     * para que la siguiente cuenta no tenga la música de la anterior.
     */
    fun clearAll(context: Context) {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        _markedKeys.value = emptySet()
        prefs(appContext).edit().clear().apply()
        try {
            DownloadService.sendRemoveAllDownloads(appContext, ZepyfyDownloadService::class.java, false)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "No se pudo arrancar el servicio para borrar las descargas", e)
        }
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        _markedKeys.value = prefs(context).getStringSet(KEY_MARKED, emptySet())?.toSet() ?: emptySet()
        loaded = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Canciones que deberían estar descargadas según lo marcado (id -> canción). */
    fun neededSongs(marked: Set<String>, songs: List<Song>): Map<String, Song> {
        if (marked.isEmpty()) return emptyMap()
        val needed = LinkedHashMap<String, Song>()
        for (song in songs) {
            val albumId = song.album ?: continue
            if (albumKey(albumId) in marked) needed[song.id] = song
        }
        return needed
    }

    /**
     * Pone las descargas en línea con lo marcado: pide las que faltan y borra las
     * que ya no hacen falta. Llamar desde el hilo principal (lo hace MainActivity
     * cada vez que cambian lo marcado o las canciones).
     */
    @MainThread
    suspend fun sync(
        context: Context,
        songs: List<Song>,
        allowRemovals: Boolean = true
    ) {
        val appContext = context.applicationContext
        val needed = neededSongs(markedKeys(appContext).value, songs)

        val downloadIndex = AudioCache.downloadManager(appContext).downloadIndex
        // Estado actual de cada descarga (lectura de la base de datos fuera del hilo principal)
        val states: Map<String, Int> = withContext(Dispatchers.IO) {
            val result = HashMap<String, Int>()
            try {
                downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        result[cursor.download.request.id] = cursor.download.state
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo leer el índice de descargas", e)
            }
            result
        }

        val activeStates = setOf(
            Download.STATE_QUEUED,
            Download.STATE_DOWNLOADING,
            Download.STATE_RESTARTING,
            Download.STATE_COMPLETED
        )

        // Añadir lo que falta (o lo que falló en un intento anterior)
        for ((id, song) in needed) {
            if (states[id] in activeStates) continue
            val url = song.url_cancion ?: continue
            val request = DownloadRequest.Builder(id, Uri.parse(url))
                .setCustomCacheKey(id) // misma clave que usa el reproductor
                .setData(song.titulo.toByteArray()) // la notificación de progreso no lo usa, pero ayuda al depurar
                .build()
            DownloadService.sendAddDownload(appContext, ZepyfyDownloadService::class.java, request, false)
        }

        // Borrar lo que ya no pertenece a nada marcado. Solo con datos confirmados por el
        // servidor: con una caché local incompleta se borrarían descargas válidas.
        if (allowRemovals) for ((id, state) in states) {
            if (id !in needed && state != Download.STATE_REMOVING) {
                DownloadService.sendRemoveDownload(appContext, ZepyfyDownloadService::class.java, id, false)
            }
        }

        // Retomar descargas que quedaron a medias la última vez que se cerró la app
        val hasPending = states.any { (id, state) ->
            id in needed && state in setOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING)
        }
        if (hasPending) {
            try {
                DownloadService.start(appContext, ZepyfyDownloadService::class.java)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "No se pudo arrancar el servicio de descargas", e)
            }
        }
    }
}
