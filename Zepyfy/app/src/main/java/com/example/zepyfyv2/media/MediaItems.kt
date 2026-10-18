package com.example.zepyfyv2.media

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.example.zepyfyv2.data.MusicCatalog
import com.example.zepyfyv2.data.Song
import org.json.JSONObject

/**
 * Conversión Song -> MediaItem en un solo sitio. La usan: reproducir una lista,
 * "reproducir a continuación" y la cola guardada (QueueStore).
 *
 * Todos los extras son String para poder guardarlos y restaurarlos tal cual.
 */
object MediaItems {

    const val EXTRA_ALBUM_ID = "album_id"
    const val EXTRA_ALBUM_NAME = "album_name"
    const val EXTRA_ALBUM_COVER_URL = "album_cover_url"
    const val EXTRA_ARTIST_ID = "artist_id_0"
    const val EXTRA_ARTIST_NAME = "artist_name_0"
    const val EXTRA_ARTIST_PHOTO_URL = "artist_photo_url"
    const val EXTRA_LYRICS_URL = "lyrics_url"
    const val EXTRA_DURATION_S = "duration_s"

    fun fromSongs(songs: List<Song>): List<MediaItem> = songs.map { fromSong(it) }

    fun fromSong(song: Song): MediaItem {
        val artistId = song.artistas?.getOrNull(0)
        val extras = Bundle().apply {
            putString(EXTRA_ALBUM_ID, song.album)
            putString(EXTRA_ALBUM_NAME, song.album_nombre)
            putString(EXTRA_ALBUM_COVER_URL, song.url_portada)
            putString(EXTRA_ARTIST_ID, artistId)
            putString(EXTRA_ARTIST_NAME, song.artistas_nombres?.getOrNull(0))
            // Si el catálogo de artistas no está en memoria queda null y PlayerFragment usa su fallback
            putString(EXTRA_ARTIST_PHOTO_URL, MusicCatalog.cachedArtist(artistId)?.url_fotoartista)
            putString(EXTRA_LYRICS_URL, song.url_letra)
            putString(EXTRA_DURATION_S, song.duracion.toString())
        }
        return build(
            id = song.id,
            uri = song.url_cancion,
            title = song.titulo,
            artist = song.artistas_nombres?.joinToString(", "),
            artworkUri = song.url_portada,
            extras = extras
        )
    }

    // ---- Guardar / restaurar (QueueStore) ----

    fun toJson(item: MediaItem): JSONObject {
        val metadata = item.mediaMetadata
        val extras = JSONObject()
        metadata.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                bundle.getString(key)?.let { extras.put(key, it) }
            }
        }
        return JSONObject()
            .put("id", item.mediaId)
            .put("uri", item.localConfiguration?.uri?.toString())
            .put("title", metadata.title?.toString())
            .put("artist", metadata.artist?.toString())
            .put("artwork", metadata.artworkUri?.toString())
            .put("extras", extras)
    }

    fun fromJson(json: JSONObject): MediaItem? {
        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val uri = json.optString("uri").takeIf { it.isNotEmpty() } ?: return null
        val extras = Bundle()
        json.optJSONObject("extras")?.let { obj ->
            obj.keys().forEach { key -> extras.putString(key, obj.optString(key)) }
        }
        return build(
            id = id,
            uri = uri,
            title = json.optString("title").ifEmpty { null },
            artist = json.optString("artist").ifEmpty { null },
            artworkUri = json.optString("artwork").ifEmpty { null },
            extras = extras
        )
    }

    @OptIn(UnstableApi::class) // setCustomCacheKey
    private fun build(
        id: String,
        uri: String?,
        title: String?,
        artist: String?,
        artworkUri: String?,
        extras: Bundle
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(artworkUri?.let(Uri::parse))
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            // Clave de caché y de descarga = id de la canción, no la URL. Así, si el
            // token de descarga de Firebase cambia, no se vuelve a bajar el archivo.
            .setCustomCacheKey(id)
            .setMediaMetadata(metadata)
            .build()
    }
}
