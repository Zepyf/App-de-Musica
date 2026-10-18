package com.example.zepyfyv2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.zepyfyv2.adapters.AlbumAdapter
import com.example.zepyfyv2.data.Album
import com.example.zepyfyv2.data.MusicCatalog
import com.example.zepyfyv2.data.Song
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.combine

class AlbumsFragment : Fragment(), SearchableFragment {

    private lateinit var albumsRecyclerView: RecyclerView
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var filterAlbumsButton: MaterialButton
    private var allSongs = listOf<Song>()
    private var allAlbums = mapOf<String, Album>()
    private var currentQuery = ""
    private var isAlbumsMode = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_albums, container, false)
        albumsRecyclerView = view.findViewById(R.id.albumsRecyclerView)
        filterAlbumsButton = view.findViewById(R.id.filterAlbumsButton)
        setupRecyclerView()
        setupFilterButton()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Las canciones se usan para separar álbumes (más de una canción) de sencillos;
        // ambas colecciones salen del catálogo compartido, sin lecturas propias.
        collectWhileStarted(
            combine(MusicCatalog.songs, MusicCatalog.albums) { songs, albums ->
                if (songs == null || albums == null) null else songs to albums
            }
        ) { (songs, albums) ->
            allSongs = songs
            allAlbums = albums.associateBy { it.id }
            applyFilters()
        }
    }

    private fun setupRecyclerView() {
        albumAdapter = AlbumAdapter(emptyList()) { album ->
            val action = MainFragmentDirections.actionMainFragmentToAlbumDetailFragment(album)
            findNavController().navigate(action)
        }
        albumsRecyclerView.layoutManager = GridLayoutManager(context, 2)
        albumsRecyclerView.adapter = albumAdapter
    }

    private fun setupFilterButton() {
        filterAlbumsButton.setOnClickListener { 
            isAlbumsMode = !isAlbumsMode
            filterAlbumsButton.isChecked = isAlbumsMode
            filterAlbumsButton.text = getString(if (isAlbumsMode) R.string.filter_albums else R.string.filter_singles)
            applyFilters()
        }
    }

    private fun applyFilters() {
        // Group songs by album ID to get song counts
        val songsByAlbum = allSongs.groupBy { it.album }

        // Get a set of album IDs that match the filter (album vs single)
        val filteredAlbumIds = if (isAlbumsMode) {
            songsByAlbum.filter { it.value.size > 1 }.keys
        } else {
            songsByAlbum.filter { it.value.size == 1 }.keys
        }

        // Get the actual album objects for the filtered IDs
        var displayAlbums = allAlbums.values.filter { it.id in filteredAlbumIds }

        // Apply search query to the title of the real albums
        if (currentQuery.isNotEmpty()) {
            displayAlbums = displayAlbums.filter { it.titulo.contains(currentQuery, ignoreCase = true) }
        }
        
        albumAdapter.updateAlbums(displayAlbums.sortedBy { it.titulo })
    }

    override fun search(query: String) {
        currentQuery = query
        if (view != null) { // Ensure fragment is attached
            applyFilters()
        }
    }
}