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
import com.example.zepyfyv2.data.Artist

class ArtistAdapter(
    initialArtists: List<Artist>,
    private val onItemClick: (Artist) -> Unit
) : ListAdapter<Artist, ArtistAdapter.ArtistViewHolder>(ArtistDiff) {

    init {
        if (initialArtists.isNotEmpty()) submitList(initialArtists)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_artist, parent, false)
        return ArtistViewHolder(view)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        val artist = getItem(position)
        holder.bind(artist)
    }

    /** Con DiffUtil solo se redibujan las tarjetas que cambian. */
    fun updateArtists(newArtists: List<Artist>) {
        submitList(newArtists)
    }

    inner class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val artistNameTextView: TextView = itemView.findViewById(R.id.artistNameTextView)
        private val artistImageView: ImageView = itemView.findViewById(R.id.artistImageView)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(artist: Artist) {
            artistNameTextView.text = artist.nombre

            val imageUrl = artist.url_fotoartista?.ifEmpty { null }

            Glide.with(itemView.context)
                .load(imageUrl)
                .placeholder(R.drawable.artistadesconocido)
                .error(R.drawable.artistadesconocido)
                .circleCrop()
                .into(artistImageView)
        }
    }
}

private object ArtistDiff : DiffUtil.ItemCallback<Artist>() {
    override fun areItemsTheSame(oldItem: Artist, newItem: Artist) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Artist, newItem: Artist) = oldItem == newItem
}
