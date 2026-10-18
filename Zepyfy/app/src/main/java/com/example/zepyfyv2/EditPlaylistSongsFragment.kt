package com.example.zepyfyv2

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.zepyfyv2.adapters.PlaylistSongPickerAdapter
import com.example.zepyfyv2.data.MusicCatalog
import com.example.zepyfyv2.data.Playlist
import com.example.zepyfyv2.data.PlaylistRepository
import com.example.zepyfyv2.data.Song
import com.example.zepyfyv2.databinding.FragmentEditPlaylistSongsBinding
import kotlinx.coroutines.launch

/**
 * Muestra TODAS las canciones. Las que ya están en la playlist salen en gris
 * (estado "activated", el mismo que usa la selección del resto de la app).
 * Tocar una canción la añade o la quita de la playlist directamente en Firestore.
 */
class EditPlaylistSongsFragment : Fragment() {

    private val args: EditPlaylistSongsFragmentArgs by navArgs()

    private var _binding: FragmentEditPlaylistSongsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: PlaylistSongPickerAdapter
    private var allSongs = listOf<Song>()

    // Estado que se pinta: lo que dice Firestore + los cambios optimistas del usuario
    private var playlistSongIds = setOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEditPlaylistSongsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val playlist = args.playlist

        setupToolbar(playlist)

        adapter = PlaylistSongPickerAdapter { song, shouldBeInPlaylist ->
            toggleSong(playlist, song, shouldBeInPlaylist)
        }
        binding.songsRecyclerView.adapter = adapter

        binding.searchEditText.doAfterTextChanged { applyFilter(it?.toString().orEmpty()) }

        // Mientras llega Firestore, pintar con lo que traía el argumento
        playlistSongIds = playlist.canciones?.toSet() ?: emptySet()
        adapter.submitPlaylistIds(playlistSongIds)
        updateCounter()

        // Todas las canciones: catálogo compartido (normalmente ya está en memoria,
        // así que la lista aparece al instante y sin lecturas extra)
        collectWhileStarted(MusicCatalog.songs) { songs ->
            allSongs = songs
            applyFilter(binding.searchEditText.text?.toString().orEmpty())
        }

        // La playlist en tiempo real: si cambia (aquí o en otro sitio) se repinta.
        // Los cambios hechos desde esta pantalla llegan al momento porque Firestore
        // notifica las escrituras locales antes de confirmarlas con el servidor.
        collectWhileStarted(MusicCatalog.playlists) { playlists ->
            val current = playlists.firstOrNull { it.id == playlist.id } ?: return@collectWhileStarted
            playlistSongIds = current.canciones?.toSet() ?: emptySet()
            adapter.submitPlaylistIds(playlistSongIds)
            updateCounter()
        }
    }

    private fun setupToolbar(playlist: Playlist) {
        val activity = activity as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        activity.supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.navigationIcon?.setTint(ContextCompat.getColor(requireContext(), R.color.zepyfy_accent))
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbarTitle.text = getString(R.string.title_add_songs_to, playlist.name)
    }

    private fun toggleSong(playlist: Playlist, song: Song, shouldBeInPlaylist: Boolean) {
        // UI optimista: se marca/desmarca al instante y se revierte si Firestore falla
        val previous = playlistSongIds
        playlistSongIds = if (shouldBeInPlaylist) previous + song.id else previous - song.id
        adapter.submitPlaylistIds(playlistSongIds)
        updateCounter()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (shouldBeInPlaylist) {
                    PlaylistRepository.addSong(playlist.id, song.id)
                } else {
                    PlaylistRepository.removeSong(playlist.id, song.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando playlist", e)
                if (_binding == null) return@launch
                // Revertir solo esa canción, sin pisar otros cambios hechos mientras tanto
                playlistSongIds = if (shouldBeInPlaylist) playlistSongIds - song.id else playlistSongIds + song.id
                adapter.submitPlaylistIds(playlistSongIds)
                updateCounter()
                Toast.makeText(requireContext(), getString(R.string.error_updating_playlist), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) allSongs else allSongs.filter { song ->
            song.titulo.contains(q, ignoreCase = true) ||
                song.artistas_nombres?.any { it.contains(q, ignoreCase = true) } == true
        }
        adapter.submitSongs(filtered)
    }

    private fun updateCounter() {
        val count = playlistSongIds.size
        binding.playlistCountTextView.text =
            resources.getQuantityString(R.plurals.playlist_song_count_hint, count, count)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "EditPlaylistSongs"
    }
}
