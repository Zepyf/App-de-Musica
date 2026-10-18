package com.example.zepyfyv2.data

import android.content.Context
import android.util.Log
import com.example.zepyfyv2.R
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.tasks.await

/**
 * Punto único para modificar el contenido de las playlists en Firestore.
 *
 * Todas las pantallas pasan por aquí, de modo que la comprobación de duplicados,
 * el orden de las canciones y los mensajes al usuario son iguales en toda la app.
 */
object PlaylistRepository {

    private const val COLLECTION = "Playlist"
    private const val FIELD_SONGS = "canciones"
    private const val COVERS_FOLDER = "portadasPlaylist"
    private const val TAG = "PlaylistRepository"

    private val firestore: FirebaseFirestore
        get() = FirebaseFirestore.getInstance()

    /** Resultado de intentar añadir canciones a una playlist. */
    data class AddResult(val added: Int, val alreadyPresent: Int)

    /**
     * Añade a la playlist solo las canciones que todavía no están en ella.
     *
     * La lectura y la escritura van dentro de una transacción: si otro dispositivo
     * modifica la playlist a la vez, Firestore reintenta con los datos actuales,
     * así que la comprobación nunca trabaja sobre una lista desactualizada.
     */
    suspend fun addSongs(playlistId: String, songIds: List<String>): AddResult {
        val requested = songIds.filter { it.isNotBlank() }.distinct()
        if (requested.isEmpty()) return AddResult(0, 0)

        val ref = firestore.collection(COLLECTION).document(playlistId)
        return firestore.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            @Suppress("UNCHECKED_CAST")
            val existing = (snapshot.get(FIELD_SONGS) as? List<String>) ?: emptyList()
            val existingSet = existing.toSet()

            val toAdd = requested.filterNot { it in existingSet }
            if (toAdd.isNotEmpty()) {
                val updatedList = existing + toAdd
                transaction.update(ref, FIELD_SONGS, updatedList)
            }
            AddResult(added = toAdd.size, alreadyPresent = requested.size - toAdd.size)
        }.await()
    }

    /** Añade una sola canción (usado por el selector de canciones de la playlist). */
    suspend fun addSong(playlistId: String, songId: String) {
        if (songId.isBlank()) return
        addSongs(playlistId, listOf(songId))
    }

    /** Quita una sola canción de la playlist. */
    suspend fun removeSong(playlistId: String, songId: String) {
        if (songId.isBlank()) return
        val ref = firestore.collection(COLLECTION).document(playlistId)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            @Suppress("UNCHECKED_CAST")
            val existing = (snapshot.get(FIELD_SONGS) as? List<String>) ?: emptyList()
            val updated = existing.filterNot { it == songId }
            transaction.update(ref, FIELD_SONGS, updated)
        }.await()
    }

    /**
     * Guarda un orden nuevo de canciones (tras reordenar arrastrando).
     * Se reescribe el array completo; no se espera a la confirmación del servidor
     * porque Firestore aplica el cambio en local al momento y lo sincroniza después.
     */
    fun reorderSongs(playlistId: String, orderedSongIds: List<String>, onError: () -> Unit) {
        firestore.collection(COLLECTION).document(playlistId)
            .update(FIELD_SONGS, orderedSongIds)
            .addOnFailureListener { onError() }
    }

    /**
     * Borra la playlist y su portada de Storage (portadasPlaylist/<id>.jpg).
     * Las canciones no se tocan: solo se borra la lista.
     *
     * Se usan tareas sin esperar (en vez de await en una corrutina) para que
     * el borrado no se pierda si el usuario cambia de pantalla, y para no
     * quedarse esperando sin conexión: Firestore lo aplica en local y lo envía
     * al servidor cuando vuelva la red.
     */
    fun deletePlaylist(playlist: Playlist, onError: () -> Unit) {
        firestore.collection(COLLECTION).document(playlist.id).delete()
            .addOnFailureListener { onError() }

        if (!playlist.coverImageUrl.isNullOrBlank()) {
            FirebaseStorage.getInstance().reference
                .child("$COVERS_FOLDER/${playlist.id}.jpg")
                .delete()
                .addOnFailureListener { e ->
                    // Si la portada no existe no hay nada que limpiar
                    val notFound = (e as? StorageException)?.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND
                    if (!notFound) Log.w(TAG, "No se pudo borrar la portada de ${playlist.id}", e)
                }
        }
    }

    /** Texto para el Toast según cuántas se añadieron y cuántas ya estaban. */
    fun buildAddMessage(context: Context, result: AddResult, playlistName: String): String {
        val total = result.added + result.alreadyPresent
        return when {
            result.added == 0 && total == 1 ->
                context.getString(R.string.info_song_already_in_playlist, playlistName)
            result.added == 0 ->
                context.getString(R.string.info_songs_already_in_playlist, playlistName)
            result.alreadyPresent == 0 && total == 1 ->
                context.getString(R.string.success_song_added_to_playlist, playlistName)
            result.alreadyPresent == 0 ->
                context.getString(R.string.success_songs_added_to_playlist, playlistName)
            else ->
                context.getString(
                    R.string.success_songs_added_some_skipped,
                    result.added, playlistName, result.alreadyPresent
                )
        }
    }
}
