package com.example.zepyfyv2.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.zepyfyv2.R
import com.example.zepyfyv2.data.Song

/**
 * Lista de canciones con selección múltiple (pulsación larga) y, opcionalmente,
 * un asa para reordenar arrastrando (playlists).
 *
 * El diff con DiffUtil se calcula de forma síncrona: así la lista interna y lo
 * que se ve en pantalla nunca están desfasados, algo imprescindible para
 * arrastrar filas (con ListAdapter el diff es asíncrono y las posiciones se
 * descuadran a mitad de un arrastre). Para listas de cientos de canciones el
 * cálculo tarda muy poco.
 *
 * La selección se guarda por id, así sobrevive aunque el catálogo emita
 * objetos Song nuevos con los mismos datos.
 *
 * @param onStartDrag si no es null, se muestra el asa y al tocarla se llama
 *        para que el fragment arranque el arrastre (ItemTouchHelper.startDrag).
 */
class SongAdapter(
    initialSongs: List<Song>,
    private val onSongClicked: (Song, Int) -> Unit,
    private val onSelectionChanged: (Boolean, Int) -> Unit,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private val items = initialSongs.toMutableList()
    private var isSelectionMode = false
    // id -> canción, para devolverlas aunque el filtro actual las oculte
    private val selectedItems = linkedMapOf<String, Song>()

    /** Canciones en el orden en que se muestran ahora mismo. */
    val currentSongs: List<Song> get() = items.toList()

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            // Solo cambia el gris de selección: no recargar textos ni portada
            holder.bindSelection(items[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    fun isSelectionMode(): Boolean = isSelectionMode

    fun updateSongs(newSongs: List<Song>) {
        val diff = DiffUtil.calculateDiff(SongListDiff(items, newSongs))
        items.clear()
        items.addAll(newSongs)
        diff.dispatchUpdatesTo(this)
    }

    /** Mueve una fila (durante un arrastre). No guarda nada: eso lo hace el fragment al soltar. */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    /**
     * Canciones seleccionadas en el orden de la lista (útil para "reproducir a
     * continuación"); las que el filtro actual oculta van al final.
     */
    fun getSelectedSongs(): List<Song> {
        val visible = items.filter { it.id in selectedItems }
        val visibleIds = visible.map { it.id }.toSet()
        return visible + selectedItems.values.filter { it.id !in visibleIds }
    }

    fun clearSelection() {
        isSelectionMode = false
        selectedItems.clear()
        notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION)
        onSelectionChanged(false, 0)
    }

    private fun toggleSelection(position: Int) {
        val song = items[position]
        val wasSelectionMode = isSelectionMode
        if (selectedItems.remove(song.id) == null) {
            selectedItems[song.id] = song
        }

        if (selectedItems.isEmpty()) {
            isSelectionMode = false
            notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION)
            onSelectionChanged(false, 0)
        } else {
            isSelectionMode = true
            if (!wasSelectionMode) {
                notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION)
            } else {
                notifyItemChanged(position, PAYLOAD_SELECTION)
            }
            onSelectionChanged(true, selectedItems.size)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.titleTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.artistTextView)
        private val durationTextView: TextView = itemView.findViewById(R.id.durationTextView)
        private val coverImageView: ImageView = itemView.findViewById(R.id.coverImageView)
        private val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)
        private val selectionIndicator: ImageView = itemView.findViewById(R.id.selectionIndicator)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    if (isSelectionMode) {
                        toggleSelection(position)
                    } else {
                        onSongClicked(items[position], position)
                    }
                }
            }

            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    isSelectionMode = true
                    toggleSelection(position)
                }
                true
            }

            if (onStartDrag != null) {
                dragHandle.visibility = View.VISIBLE
                // Arrastrar empieza al tocar el asa (la pulsación larga ya es para seleccionar)
                dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag?.invoke(this)
                    false
                }
            }
        }

        fun bind(song: Song) {
            bindSelection(song)
            titleTextView.text = song.titulo
            artistTextView.text = song.artistas_nombres?.joinToString(", ") ?: ""
            durationTextView.text = formatDuration(song.duracion)

            titleTextView.isSelected = true
            artistTextView.isSelected = true

            Glide.with(itemView.context)
                .load(song.url_portada)
                .placeholder(R.mipmap.ic_launcher)
                .error(R.mipmap.ic_launcher_round)
                .into(coverImageView)
        }

        fun bindSelection(song: Song) {
            val isSelected = song.id in selectedItems
            itemView.isActivated = isSelected
            if (isSelectionMode) {
                selectionIndicator.visibility = View.VISIBLE
                if (isSelected) {
                    selectionIndicator.setImageResource(R.drawable.ic_check_circle)
                    selectionIndicator.setColorFilter(
                        ContextCompat.getColor(itemView.context, R.color.zepyfy_accent)
                    )
                } else {
                    selectionIndicator.setImageResource(R.drawable.ic_add)
                    selectionIndicator.setColorFilter(
                        ContextCompat.getColor(itemView.context, R.color.zepyfy_text_secondary)
                    )
                }
            } else {
                selectionIndicator.visibility = View.GONE
            }
        }

        private fun formatDuration(durationInSeconds: Int): String {
            val minutes = durationInSeconds / 60
            val seconds = durationInSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }
    }

    private class SongListDiff(
        private val old: List<Song>,
        private val new: List<Song>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(o: Int, n: Int) = old[o].id == new[n].id
        override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
    }

    companion object {
        const val PAYLOAD_SELECTION = "selection"
    }
}

/** Compartido por los adapters de canciones que usan ListAdapter. */
object SongDiff : DiffUtil.ItemCallback<Song>() {
    override fun areItemsTheSame(oldItem: Song, newItem: Song) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Song, newItem: Song) = oldItem == newItem
}
