package com.example.zepyfyv2

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import java.io.File
import java.util.concurrent.Executors

/**
 * Almacenamiento del audio en disco. Hay dos cachés:
 *
 * 1. **Descargas** (`files/offline_audio`): canciones de los álbumes
 *    marcados para escuchar sin conexión. Nunca se vacía sola (NoOpCacheEvictor):
 *    solo se borra una canción cuando deja de pertenecer a algo marcado.
 *    Está en `files/` y no en `cache/`, así que "Borrar caché" en Android no la toca.
 *
 * 2. **Caché de reproducción** (`cache/audio_cache`): todo lo que suena se guarda
 *    aquí; tamaño máximo [MAX_BYTES] y al llenarse se borra lo que hace más tiempo
 *    que no suena (LRU).
 *
 * Al reproducir se busca primero en descargas, luego en la caché y por último se
 * pide a Firebase Storage. La clave de cada canción es su id de Firestore
 * (setCustomCacheKey en MediaItems), igual en las dos cachés y en las descargas.
 *
 * SimpleCache solo admite UNA instancia por carpeta en todo el proceso (crear dos
 * lanza IllegalStateException), por eso todo son singletons que no se liberan.
 */
@OptIn(UnstableApi::class)
object AudioCache {

    private const val MAX_BYTES = 512L * 1024 * 1024 // 512 MB
    private const val STREAMING_DIR = "audio_cache"
    private const val DOWNLOADS_DIR = "offline_audio"
    private const val MAX_PARALLEL_DOWNLOADS = 2

    private var databaseProvider: StandaloneDatabaseProvider? = null
    private var streamingCache: SimpleCache? = null
    private var downloadCache: SimpleCache? = null
    private var downloadManager: DownloadManager? = null

    /** Índice compartido de las dos cachés y de las descargas (una base de datos SQLite). */
    @Synchronized
    private fun databaseProvider(context: Context): StandaloneDatabaseProvider =
        databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext)
            .also { databaseProvider = it }

    @Synchronized
    private fun streamingCache(context: Context): SimpleCache =
        streamingCache ?: SimpleCache(
            File(context.applicationContext.cacheDir, STREAMING_DIR),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            databaseProvider(context)
        ).also { streamingCache = it }

    @Synchronized
    private fun downloadCache(context: Context): SimpleCache =
        downloadCache ?: SimpleCache(
            File(context.applicationContext.filesDir, DOWNLOADS_DIR),
            NoOpCacheEvictor(),
            databaseProvider(context)
        ).also { downloadCache = it }

    private fun httpDataSourceFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)

    /**
     * DataSource para el reproductor: descargas -> caché de reproducción -> red.
     * Lo que llega de la red se guarda en la caché de reproducción, nunca en la de
     * descargas (esa la escribe solo el DownloadManager).
     */
    fun playbackDataSourceFactory(context: Context): DataSource.Factory {
        val appContext = context.applicationContext
        // DefaultDataSource además permite reproducir archivos locales/content:// si algún día hace falta
        val network = DefaultDataSource.Factory(appContext, httpDataSourceFactory())

        val streaming = CacheDataSource.Factory()
            .setCache(streamingCache(appContext))
            .setUpstreamDataSourceFactory(network)
            // Si un archivo de caché da problemas (p. ej. Android lo borró), tirar de red en vez de fallar
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return CacheDataSource.Factory()
            .setCache(downloadCache(appContext))
            .setUpstreamDataSourceFactory(streaming)
            .setCacheWriteDataSinkFactory(null) // solo lectura
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Gestor de descargas sin conexión. Media3 exige crearlo y usarlo desde el
     * hilo principal (lo usan ZepyfyDownloadService y OfflineDownloads).
     */
    @MainThread
    @Synchronized
    fun downloadManager(context: Context): DownloadManager =
        downloadManager ?: DownloadManager(
            context.applicationContext,
            databaseProvider(context),
            downloadCache(context),
            httpDataSourceFactory(),
            Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS)
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
        }.also { downloadManager = it }
}
