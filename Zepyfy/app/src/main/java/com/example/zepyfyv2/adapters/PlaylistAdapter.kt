package com.example.zepyfyv2.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.zepyfyv2.R
import com.example.zepyfyv2.data.Playlist

class PlaylistAdapter(
    initialPlaylists: List<Playlist>,
    private val onItemClick: (Playlist) -> Unit,
    private val onItemLongClick: ((Playlist) -> Unit)? = null
) : ListAdapter<Playlist, PlaylistAdapter.PlaylistViewHolder>(PlaylistDiff) {

    init {
        if (initialPlaylists.isNotEmpty()) submitList(initialPlaylists)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.grid_item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** Con DiffUtil solo se redibujan las tarjetas que cambian. */
    fun updatePlaylists(newPlaylists: List<Playlist>) {
        submitList(newPlaylists)
    }

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverImageView: ImageView = itemView.findViewById(R.id.playlistCoverImageView)
        private val nameTextView: TextView = itemView.findViewById(R.id.playlistNameTextView)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }

            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                val callback = onItemLongClick
                if (position != RecyclerView.NO_POSITION && callback != null) {
                    callback(getItem(position))
                    true
                } else {
                    false
                }
            }
        }

        fun bind(playlist: Playlist) {
            nameTextView.text = playlist.name

            val imageUrl = playlist.coverImageUrl

            Glide.with(itemView.context)
                .load(if (imageUrl.isNullOrBlank()) R.mipmap.ic_launcher else imageUrl)
                .error(R.mipmap.ic_launcher)
                .into(coverImageView)
        }
    }
}

private object PlaylistDiff : DiffUtil.ItemCallback<Playlist>() {
    override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist) =
        oldItem.name == newItem.name && oldItem.coverImageUrl == newItem.coverImageUrl
}
