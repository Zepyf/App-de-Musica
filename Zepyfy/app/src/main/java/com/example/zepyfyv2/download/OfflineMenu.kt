package com.example.zepyfyv2.download

import android.content.Context
import android.view.MenuItem
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.zepyfyv2.MainActivity
import com.example.zepyfyv2.R

/** Botón "Descargar / Descargada" del menú de los álbumes. */
object OfflineMenu {

    /** Pinta el botón según si [key] está marcada para escuchar sin conexión. */
    fun render(context: Context, item: MenuItem?, key: String) {
        item ?: return
        val marked = OfflineDownloads.isMarked(context, key)
        item.title = context.getString(if (marked) R.string.action_remove_download else R.string.action_download)
        item.icon = ContextCompat.getDrawable(
            context,
            if (marked) R.drawable.ic_download_done else R.drawable.ic_download
        )?.mutate()?.apply { setTint(ContextCompat.getColor(context, R.color.zepyfy_accent)) }
    }

    /**
     * Marca o desmarca [key]. MainActivity se encarga de descargar o borrar las
     * canciones (OfflineDownloads.sync). Devuelve el estado nuevo.
     */
    fun toggle(fragment: Fragment, item: MenuItem?, key: String, songCount: Int): Boolean {
        val context = fragment.requireContext()
        val nowMarked = !OfflineDownloads.isMarked(context, key)
        OfflineDownloads.setMarked(context, key, nowMarked)
        render(context, item, key)

        val message = if (nowMarked) {
            (fragment.activity as? MainActivity)?.requestNotificationPermissionIfNeeded()
            context.resources.getQuantityString(R.plurals.download_started, songCount, songCount)
        } else {
            context.getString(R.string.download_removed)
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        return nowMarked
    }
}
