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
import com.example.zepyfyv2.data.Album

class AlbumAdapter(
    initialAlbums: List<Album>,
    private val onItemClick: (Album) -> Unit
) : ListAdapter<Album, AlbumAdapter.AlbumViewHolder>(AlbumDiff) {

    init {
        if (initialAlbums.isNotEmpty()) submitList(initialAlbums)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.grid_item_album, parent, false)
        return AlbumViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        val album = getItem(position)
        holder.bind(album)
    }

    /** Con DiffUtil solo se redibujan las tarjetas que cambian. */
    fun updateAlbums(newAlbums: List<Album>) {
        submitList(newAlbums)
    }

    inner class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val albumCoverImageView: ImageView = itemView.findViewById(R.id.albumCoverImageView)
        private val albumTitleTextView: TextView = itemView.findViewById(R.id.albumTitleTextView)
        private val albumArtistTextView: TextView = itemView.findViewById(R.id.albumArtistTextView)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(album: Album) {
            albumTitleTextView.text = album.titulo
            albumArtistTextView.text = album.artistas_principales.joinToString(", ")

            Glide.with(itemView.context)
                .load(album.url_portada)
                .placeholder(R.mipmap.ic_launcher)
                .error(R.mipmap.ic_launcher_round)
                .into(albumCoverImageView)
        }
    }
}

private object AlbumDiff : DiffUtil.ItemCallback<Album>() {
    override fun areItemsTheSame(oldItem: Album, newItem: Album) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Album, newItem: Album) = oldItem == newItem
}
