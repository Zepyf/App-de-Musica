package com.example.zepyfyv2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.zepyfyv2.adapters.PlaylistAdapter
import com.example.zepyfyv2.data.MusicCatalog
import com.example.zepyfyv2.data.Playlist
import com.example.zepyfyv2.data.PlaylistRepository

class PlaylistsFragment : Fragment(), SearchableFragment {

    private lateinit var playlistsRecyclerView: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private var allPlaylists = listOf<Playlist>()
    private var currentQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_playlists, container, false)
        playlistsRecyclerView = view.findViewById(R.id.playlistsRecyclerView)
        setupRecyclerView()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Mismo listener en tiempo real que usan el resto de pantallas (MusicCatalog)
        collectWhileStarted(MusicCatalog.playlists) { playlists ->
            allPlaylists = playlists
            applyFilter()
        }
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            emptyList(),
            onItemClick = { playlist ->
                val action = MainFragmentDirections.actionMainFragmentToPlaylistDetailFragment(playlist)
                findNavController().navigate(action)
            },
            // Pulsación larga en la tarjeta: borrar la playlist
            onItemLongClick = { playlist -> confirmDelete(playlist) }
        )
        playlistsRecyclerView.layoutManager = GridLayoutManager(context, 2)
        playlistsRecyclerView.adapter = playlistAdapter
    }

    private fun confirmDelete(playlist: Playlist) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_delete_playlist_title))
            .setMessage(getString(R.string.dialog_delete_playlist_message, playlist.name))
            .setPositiveButton(getString(R.string.action_remove)) { _, _ -> deletePlaylist(playlist) }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun deletePlaylist(playlist: Playlist) {
        val appContext = requireContext().applicationContext
        PlaylistRepository.deletePlaylist(playlist) {
            Toast.makeText(appContext, appContext.getString(R.string.error_deleting_playlist), Toast.LENGTH_SHORT).show()
        }
        // La tarjeta desaparece sola: el listener de MusicCatalog recibe el borrado al instante
        Toast.makeText(appContext, appContext.getString(R.string.success_playlist_deleted, playlist.name), Toast.LENGTH_SHORT).show()
    }

    override fun search(query: String) {
        currentQuery = query
        if (view != null) applyFilter()
    }

    private fun applyFilter() {
        val filteredList = if (currentQuery.isEmpty()) {
            allPlaylists
        } else {
            allPlaylists.filter { it.name.contains(currentQuery, ignoreCase = true) }
        }
        playlistAdapter.updatePlaylists(filteredList)
    }
}