package com.example.zepyfyv2.media

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.json.JSONArray

/**
 * Guarda la cola de reproducción para retomarla aunque Android cierre la app.
 *
 * Se guarda todo lo necesario para reconstruir cada MediaItem (URL, título,
 * portada, extras), así que al restaurar no hace falta red ni Firestore.
 * La cola completa solo se reescribe cuando cambia; la canción actual y la
 * posición se guardan aparte porque cambian mucho más a menudo.
 */
object QueueStore {

    private const val TAG = "QueueStore"
    private const val PREFS = "playback_queue"
    private const val KEY_ITEMS = "items"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "position_ms"
    private const val KEY_REPEAT = "repeat_mode"
    private const val KEY_SHUFFLE = "shuffle"

    data class SavedQueue(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val repeatMode: Int,
        val shuffle: Boolean
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Guarda la lista completa (llamar cuando la cola cambia). */
    fun saveQueue(context: Context, player: Player) {
        val array = JSONArray()
        for (i in 0 until player.mediaItemCount) {
            array.put(MediaItems.toJson(player.getMediaItemAt(i)))
        }
        prefs(context).edit().putString(KEY_ITEMS, array.toString()).apply()
        savePosition(context, player)
    }

    /** Guarda canción actual y posición dentro de ella. */
    fun savePosition(context: Context, player: Player) {
        prefs(context).edit()
            .putInt(KEY_INDEX, player.currentMediaItemIndex)
            .putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    fun saveModes(context: Context, player: Player) {
        prefs(context).edit()
            .putInt(KEY_REPEAT, player.repeatMode)
            .putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
            .apply()
    }

    /** Al cerrar sesión: olvida la cola guardada. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** null si no hay nada guardado (o la cola estaba vacía). */
    fun restore(context: Context): SavedQueue? {
        val prefs = prefs(context)
        val json = prefs.getString(KEY_ITEMS, null) ?: return null
        val items = try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { MediaItems.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "Cola guardada ilegible, se descarta", e)
            return null
        }
        if (items.isEmpty()) return null

        return SavedQueue(
            items = items,
            index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, items.lastIndex),
            positionMs = prefs.getLong(KEY_POSITION, 0L),
            repeatMode = prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_ALL),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false)
        )
    }
}
