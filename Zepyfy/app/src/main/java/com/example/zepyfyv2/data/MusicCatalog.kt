package com.example.zepyfyv2.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn

/**
 * Catálogo de música compartido por toda la app.
 *
 * En lugar de que cada pantalla descargue la colección que necesita, hay UN
 * listener de Firestore por colección, compartido por todas:
 *
 *  - La primera pantalla que lo pide arranca el listener. Firestore sirve primero
 *    su caché local (la lista aparece al instante) y después los datos del servidor.
 *  - Mientras el listener sigue vivo, Firestore solo envía (y cobra) los
 *    documentos que cambian, no la colección entera.
 *  - Cuando ninguna pantalla lo observa durante [KEEP_ALIVE_MS], se cierra.
 *    El último valor se conserva, así que al volver se pinta sin esperar.
 *
 * Todo va ligado a la sesión: al cerrar sesión o cambiar de cuenta se cortan
 * los listeners y se vacían los datos de la sesión anterior, para que otra
 * cuenta nunca vea lo que tenía la app en memoria. Si las reglas de Firebase
 * deniegan el acceso, las listas quedan vacías.
 *
 * Valor `null` = todavía cargando (o sin sesión).
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
object MusicCatalog {

    private const val TAG = "MusicCatalog"
    private const val KEEP_ALIVE_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val firestore: FirebaseFirestore
        get() = FirebaseFirestore.getInstance()

    // ---- Sesión ----

    /** UID de la sesión actual (null sin sesión). Cada cambio reinicia los listeners. */
    private val sessionUid = MutableStateFlow(FirebaseAuth.getInstance().currentUser?.uid)

    init {
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            sessionUid.value = auth.currentUser?.uid
        }
    }

    /**
     * Corta los listeners y vacía el catálogo en el acto (sin esperar al aviso de
     * FirebaseAuth). Lo usa el cierre de sesión antes de borrar la caché de Firestore.
     */
    fun endSession() {
        sessionUid.value = null
        _accessDenied.value = false
        serverSynced.clear()
    }

    private val _accessDenied = MutableStateFlow(false)

    /**
     * true cuando Firestore rechaza la lectura por las reglas de seguridad
     * (sesión iniciada con una cuenta cuyo UID no está en firestore.rules).
     * MainActivity lo usa para avisar y mostrar el UID que hay que añadir.
     * Vuelve a false en cuanto una lectura funciona (los reintentos siguen activos).
     */
    val accessDenied: StateFlow<Boolean> = _accessDenied.asStateFlow()

    // Colecciones que ya han recibido datos del servidor (no solo de la caché local)
    private val serverSynced = mutableSetOf<String>()

    /**
     * true si [collection] ya tiene datos confirmados por el servidor. La caché local
     * de Firestore puede estar incompleta, así que las decisiones destructivas
     * (como borrar descargas) solo se toman con datos del servidor.
     */
    fun isServerSynced(collection: String): Boolean = collection in serverSynced

    const val SONGS_COLLECTION = "canciones"
    const val PLAYLISTS_COLLECTION = "Playlist"

    val songs: StateFlow<List<Song>?> = sessionCollection(SONGS_COLLECTION) { doc ->
        doc.toObject(Song::class.java)?.copy(id = doc.id)
    }.sortedState { it.titulo }

    val albums: StateFlow<List<Album>?> = sessionCollection("albums") { doc ->
        doc.toObject(Album::class.java)?.copy(id = doc.id)
    }.sortedState { it.titulo }

    val artists: StateFlow<List<Artist>?> = sessionCollection("artistas") { doc ->
        doc.toObject(Artist::class.java)?.copy(id = doc.id)
    }.sortedState { it.nombre }

    val playlists: StateFlow<List<Playlist>?> = sessionCollection(PLAYLISTS_COLLECTION) { doc ->
        doc.toObject(Playlist::class.java)?.copy(id = doc.id)
    }.sortedState { it.name }

    // ---- Acceso puntual (espera a que haya datos si aún no los hay) ----

    suspend fun awaitPlaylists(): List<Playlist> = playlists.filterNotNull().first()

    /** Valor ya cargado, sin esperar ni arrancar nada (null si no está en memoria). */
    fun cachedArtist(id: String?): Artist? = id?.let { artists.value?.firstOrNull { a -> a.id == it } }

    /**
     * Devuelve las canciones de [ids] en el mismo orden que la lista de ids,
     * ignorando las que ya no existan.
     */
    fun songsByIds(allSongs: List<Song>, ids: List<String>): List<Song> {
        val byId = allSongs.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    // ---- Implementación ----

    /**
     * Escucha [name] mientras haya sesión. Al cambiar de cuenta emite `null` antes
     * de nada, para que no quede a la vista ningún dato de la sesión anterior.
     */
    private fun <T : Any> sessionCollection(
        name: String,
        mapper: (DocumentSnapshot) -> T?
    ): Flow<List<T>?> {
        var lastUid: String? = null
        return sessionUid.flatMapLatest { uid ->
            if (uid == null) {
                lastUid = null
                flowOf(null)
            } else {
                listen(name, mapper).onStart {
                    if (uid != lastUid) {
                        lastUid = uid
                        serverSynced -= name
                        emit(null) // vaciar lo de la cuenta anterior
                    }
                }
            }
        }
    }

    private fun <T : Any> listen(
        name: String,
        mapper: (DocumentSnapshot) -> T?
    ): Flow<List<T>?> {
        // Fuera del callbackFlow para que sobreviva a los reintentos
        var hasData = false
        return callbackFlow<List<T>?> {
            val registration = firestore.collection(name).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error escuchando '$name'", error)
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        // Sin permiso: no se muestra nada, aunque hubiera datos guardados
                        _accessDenied.value = true
                        trySend(emptyList())
                    } else if (!hasData) {
                        // Otro error sin datos aún: vacío para no dejar la UI esperando.
                        // Si ya había datos (p. ej. se fue la red), se conservan.
                        trySend(emptyList())
                    }
                    // Tras un error Firestore cancela el listener: cerramos para reintentar
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    // Ojo: si Firestore sirve desde su caché local no ha validado permisos
                    if (!snapshot.metadata.isFromCache) {
                        _accessDenied.value = false
                        serverSynced += name
                    }
                    hasData = true
                    trySend(snapshot.documents.mapNotNull(mapper))
                }
            }
            awaitClose {
                // Tras cerrar sesión la instancia de Firestore puede estar ya terminada
                try {
                    registration.remove()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo quitar el listener de '$name'", e)
                }
            }
        }.retryWhen { _, attempt ->
            // 2 s, 4 s, 8 s... hasta un máximo de 60 s entre intentos
            delay(minOf(60_000L, 2_000L shl attempt.toInt().coerceAtMost(5)))
            true
        }
    }

    /** Ordena por [key] (sin distinguir mayúsculas) y comparte el resultado como StateFlow. */
    private fun <T : Any> Flow<List<T>?>.sortedState(key: (T) -> String): StateFlow<List<T>?> {
        val comparator = compareBy(String.CASE_INSENSITIVE_ORDER, key)
        return map { list -> list?.sortedWith(comparator) }
            .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_ALIVE_MS), null)
    }
}
