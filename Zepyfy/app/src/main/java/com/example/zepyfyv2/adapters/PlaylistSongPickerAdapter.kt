package com.example.zepyfyv2.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.zepyfyv2.R
import com.example.zepyfyv2.data.Song

/**
 * Lista de todas las canciones donde las que pertenecen a la playlist aparecen
 * "activadas" (mismo gris que la selección del resto de la app, vía
 * list_item_background_selector). Un toque alterna si la canción está o no.
 */
class PlaylistSongPickerAdapter(
    private val onToggle: (song: Song, shouldBeInPlaylist: Boolean) -> Unit
) : ListAdapter<Song, PlaylistSongPickerAdapter.ViewHolder>(SongDiff) {

    private var inPlaylistIds: Set<String> = emptySet()

    fun submitSongs(newSongs: List<Song>) {
        submitList(newSongs)
    }

    /** Ids que están ahora mismo en la playlist (viene del listener de Firestore). */
    fun submitPlaylistIds(ids: Set<String>) {
        if (ids == inPlaylistIds) return
        val changed = (ids - inPlaylistIds) + (inPlaylistIds - ids)
        inPlaylistIds = ids
        currentList.forEachIndexed { index, song ->
            if (song.id in changed) notifyItemChanged(index, SongAdapter.PAYLOAD_SELECTION)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(SongAdapter.PAYLOAD_SELECTION)) {
            holder.bindSelectionState(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.titleTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.artistTextView)
        private val durationTextView: TextView = itemView.findViewById(R.id.durationTextView)
        private val coverImageView: ImageView = itemView.findViewById(R.id.coverImageView)
        private val selectionIndicator: ImageView = itemView.findViewById(R.id.selectionIndicator)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val song = getItem(position)
                onToggle(song, song.id !in inPlaylistIds)
            }
        }

        fun bind(song: Song) {
            bindSelectionState(song)
            titleTextView.text = song.titulo
            artistTextView.text = song.artistas_nombres?.joinToString(", ") ?: ""
            durationTextView.text = String.format("%d:%02d", song.duracion / 60, song.duracion % 60)
            titleTextView.isSelected = true
            artistTextView.isSelected = true

            Glide.with(itemView.context)
                .load(song.url_portada)
                .placeholder(R.mipmap.ic_launcher)
                .error(R.mipmap.ic_launcher_round)
                .into(coverImageView)
        }

        fun bindSelectionState(song: Song) {
            val inPlaylist = song.id in inPlaylistIds
            itemView.isActivated = inPlaylist
            selectionIndicator.visibility = View.VISIBLE
            if (inPlaylist) {
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
        }
    }
}
