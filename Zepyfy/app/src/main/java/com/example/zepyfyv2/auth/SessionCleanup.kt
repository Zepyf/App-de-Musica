package com.example.zepyfyv2.auth

import android.content.Context
import android.util.Log
import com.example.zepyfyv2.data.MusicCatalog
import com.example.zepyfyv2.download.OfflineDownloads
import com.example.zepyfyv2.media.QueueStore
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.yield

/**
 * Borra del dispositivo todo lo que pertenece a la sesión que se cierra, para que
 * otra cuenta que inicie sesión después no vea nada de la anterior:
 *
 * - El catálogo en memoria (canciones, álbumes, artistas, playlists).
 * - La copia local de Firestore, que se serviría antes de que el servidor responda.
 * - La cola de reproducción guardada.
 * - Las descargas sin conexión y lo marcado para descargar.
 *
 * Las reglas de Firebase ya impiden que otra cuenta lea los datos del servidor;
 * esto evita que los vea a través de lo que la app tenía guardado en el móvil.
 */
object SessionCleanup {

    private const val TAG = "SessionCleanup"

    suspend fun clearLocalData(context: Context) {
        val appContext = context.applicationContext

        // 1. Vaciar el catálogo y cortar sus listeners (antes de cerrar Firestore)
        MusicCatalog.endSession()
        yield() // deja que los listeners se den de baja

        // 2. Cola guardada y descargas
        QueueStore.clear(appContext)
        OfflineDownloads.clearAll(appContext)

        // 3. Copia local de Firestore. clearPersistence solo funciona con la instancia
        //    terminada; la siguiente llamada a getInstance() crea una nueva y limpia.
        try {
            val firestore = FirebaseFirestore.getInstance()
            firestore.terminate().await()
            firestore.clearPersistence().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo borrar la caché local de Firestore", e)
        }
    }
}
