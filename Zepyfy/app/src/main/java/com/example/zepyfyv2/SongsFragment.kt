package com.example.zepyfyv2

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.zepyfyv2.adapters.SongAdapter
import com.example.zepyfyv2.data.MusicCatalog
import com.example.zepyfyv2.data.Song

class SongsFragment : Fragment(), SearchableFragment {

    interface OnSongInteractionListener {
        fun onSongClicked(songs: List<Song>, position: Int)
        fun onSelectionModeChange(isActive: Boolean, selectedCount: Int)
    }

    private var listener: OnSongInteractionListener? = null
    private lateinit var songsRecyclerView: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private var allSongs = listOf<Song>()
    private var currentQuery = ""

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (parentFragment is OnSongInteractionListener) {
            listener = parentFragment as OnSongInteractionListener
        } else {
            throw RuntimeException("The parent fragment must implement OnSongInteractionListener")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_songs, container, false)
        songsRecyclerView = view.findViewById(R.id.songsRecyclerView)
        setupRecyclerView()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (songAdapter.isSelectionMode()) {
                songAdapter.clearSelection()
            } else {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Catálogo compartido: no se vuelve a descargar al cambiar de pestaña o volver atrás
        collectWhileStarted(MusicCatalog.songs) { songs ->
            allSongs = songs
            applyFilter()
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(emptyList(),
            onSongClicked = { song, position ->
                val indexInFullList = allSongs.indexOfFirst { it.id == song.id }
                if (indexInFullList != -1) {
                    listener?.onSongClicked(allSongs, indexInFullList)
                }
            },
            onSelectionChanged = { isActive, count ->
                listener?.onSelectionModeChange(isActive, count)
            }
        )
        songsRecyclerView.layoutManager = LinearLayoutManager(context)
        songsRecyclerView.adapter = songAdapter
    }

    fun getSelectedSongIds(): List<String> {
        return songAdapter.getSelectedSongs().map { it.id }
    }

    /** Seleccionadas en el orden de la lista (para "reproducir a continuación"). */
    fun getSelectedSongs(): List<Song> = songAdapter.getSelectedSongs()

    fun clearSongSelection() {
        songAdapter.clearSelection()
    }

    override fun search(query: String) {
        currentQuery = query
        if (view != null) applyFilter()
    }

    private fun applyFilter() {
        val filteredList = if (currentQuery.isEmpty()) {
            allSongs
        } else {
            // Busca por título y también por cualquiera de sus artistas
            allSongs.filter { song ->
                song.titulo.contains(currentQuery, ignoreCase = true) ||
                    song.artistas_nombres?.any { it.contains(currentQuery, ignoreCase = true) } == true
            }
        }
        songAdapter.updateSongs(filteredList)
    }
}