package com.example.zepyfyv2.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.zepyfyv2.R
import com.example.zepyfyv2.media.MediaItems

/**
 * Fila de la cola. [index] es la posición real en la cola del reproductor
 * (la que se usa para saltar, quitar o mover), que no coincide con la posición
 * en pantalla cuando el modo aleatorio está activo.
 */
data class QueueRow(val index: Int, val item: MediaItem, val isCurrent: Boolean) {
    /**
     * Clave estable para DiffUtil: id + número de aparición (una canción puede estar
     * repetida en la cola). No usa [index] para que, al quitar una fila, las demás
     * no se consideren filas nuevas.
     */
    var key: String = item.mediaId
        internal set
}

/**
 * Cola de reproducción. Mismo diseño de fila que las listas de canciones;
 * la canción que suena va resaltada en el color de acento.
 */
class QueueAdapter(
    private val onRowClicked: (QueueRow) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    private val rows = mutableListOf<QueueRow>()

    /** Si es false (modo aleatorio) no se muestran las asas de arrastre. */
    var dragEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, rows.size)
            }
        }

    fun submit(newRows: List<QueueRow>) {
        val seen = HashMap<String, Int>()
        newRows.forEach { row ->
            val n = seen.merge(row.item.mediaId, 1, Int::plus) ?: 1
            row.key = "${row.item.mediaId}#$n"
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = rows.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(o: Int, n: Int) = rows[o].key == newRows[n].key
            override fun areContentsTheSame(o: Int, n: Int) = rows[o].isCurrent == newRows[n].isCurrent
        })
        rows.clear()
        rows.addAll(newRows)
        diff.dispatchUpdatesTo(this)
    }

    fun rowAt(position: Int): QueueRow? = rows.getOrNull(position)

    /** Mueve una fila durante el arrastre (el cambio real se aplica al soltar). */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in rows.indices || to !in rows.indices) return
        rows.add(to, rows.removeAt(from))
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(rows[position])

    @SuppressLint("ClickableViewAccessibility")
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.titleTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.artistTextView)
        private val durationTextView: TextView = itemView.findViewById(R.id.durationTextView)
        private val coverImageView: ImageView = itemView.findViewById(R.id.coverImageView)
        private val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)

        init {
            itemView.setOnClickListener {
                rowAt(bindingAdapterPosition)?.let(onRowClicked)
            }
            dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(this)
                false
            }
        }

        fun bind(row: QueueRow) {
            val metadata = row.item.mediaMetadata
            val context = itemView.context

            titleTextView.text = metadata.title
            artistTextView.text = metadata.artist
            titleTextView.isSelected = true
            artistTextView.isSelected = true

            val seconds = metadata.extras?.getString(MediaItems.EXTRA_DURATION_S)?.toIntOrNull()
            durationTextView.text = seconds?.let { String.format("%d:%02d", it / 60, it % 60) } ?: ""

            // Canción actual: título en color de acento
            titleTextView.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (row.isCurrent) R.color.zepyfy_accent else R.color.zepyfy_text_primary
                )
            )

            dragHandle.visibility = if (dragEnabled) View.VISIBLE else View.GONE

            Glide.with(context)
                .load(metadata.artworkUri)
                .placeholder(R.mipmap.ic_launcher)
                .error(R.mipmap.ic_launcher_round)
                .into(coverImageView)
        }
    }
}
