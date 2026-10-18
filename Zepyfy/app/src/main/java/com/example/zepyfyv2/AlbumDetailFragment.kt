package com.example.zepyfyv2

import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.zepyfyv2.adapters.SongAdapter
import com.example.zepyfyv2.data.Album
import com.example.zepyfyv2.data.MusicCatalog
import com.example.zepyfyv2.data.Playlist
import com.example.zepyfyv2.data.PlaylistRepository
import com.example.zepyfyv2.data.Song
import com.example.zepyfyv2.download.OfflineDownloads
import com.example.zepyfyv2.download.OfflineMenu
import kotlinx.coroutines.launch

class AlbumDetailFragment : Fragment() {

    private val args: AlbumDetailFragmentArgs by navArgs()
    private lateinit var songAdapter: SongAdapter
    private var allPlaylists = listOf<Playlist>()
    private var addToPlaylistMenuItem: MenuItem? = null
    private var playNextMenuItem: MenuItem? = null
    private var downloadMenuItem: MenuItem? = null
    private var songList = listOf<Song>()
    private var listener: SongsFragment.OnSongInteractionListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is SongsFragment.OnSongInteractionListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement OnSongInteractionListener")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_album_detail, container, false)
        setupToolbar(view)
        setupRecyclerView(view)
        populateAlbumDetails(view, args.album)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMenu()
        val albumId = args.album.id
        // Canciones del álbum filtradas del catálogo compartido: 0 lecturas extra
        // si ya está cargado.
        collectWhileStarted(MusicCatalog.songs) { songs ->
            songList = songs.filter { it.album == albumId }
            songAdapter.updateSongs(songList)
        }
        collectWhileStarted(MusicCatalog.playlists) { allPlaylists = it }
        collectWhileStarted(OfflineDownloads.markedKeys(requireContext())) {
            context?.let { ctx ->
                OfflineMenu.render(ctx, downloadMenuItem, OfflineDownloads.albumKey(albumId))
            }
        }
    }
    
    /** Menú de la barra. Con MenuProvider se quita solo al destruirse la vista. */
    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.main_menu, menu)
                addToPlaylistMenuItem = menu.findItem(R.id.action_add_to_playlist)
                playNextMenuItem = menu.findItem(R.id.action_play_next)
                // Los álbumes se pueden guardar para escuchar sin conexión
                downloadMenuItem = menu.findItem(R.id.action_toggle_download)?.apply { isVisible = true }
                OfflineMenu.render(requireContext(), downloadMenuItem, OfflineDownloads.albumKey(args.album.id))
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_play_next -> {
                        (activity as? MainActivity)?.playNext(songAdapter.getSelectedSongs())
                        songAdapter.clearSelection()
                        true
                    }
                    R.id.action_toggle_download -> {
                        OfflineMenu.toggle(this@AlbumDetailFragment, menuItem, OfflineDownloads.albumKey(args.album.id), songList.size)
                        true
                    }
                    R.id.action_add_to_playlist -> {
                        showPlaylistsDialog()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }


    private fun setupToolbar(view: View) {
        val toolbar: Toolbar = view.findViewById(R.id.toolbar)
        (activity as AppCompatActivity).setSupportActionBar(toolbar)
        (activity as AppCompatActivity).supportActionBar?.setDisplayHomeAsUpEnabled(true)
        (activity as AppCompatActivity).supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.navigationIcon?.setTint(ContextCompat.getColor(requireContext(), R.color.zepyfy_accent))
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
    }

    /** Reproduce [songList] empezando por [song] (buscada por id, no por posición visible). */
    private fun playFrom(song: Song) {
        val queue = songList.toList()
        val index = queue.indexOfFirst { it.id == song.id }
        if (index != -1) listener?.onSongClicked(queue, index)
    }

    private fun setupRecyclerView(view: View) {
        val songsRecyclerView: RecyclerView = view.findViewById(R.id.albumSongsRecyclerView)
        songAdapter = SongAdapter(emptyList(), 
            onSongClicked = { song, _ -> playFrom(song) },
            onSelectionChanged = { isInSelectionMode, _ ->
                addToPlaylistMenuItem?.isVisible = isInSelectionMode
                playNextMenuItem?.isVisible = isInSelectionMode
                downloadMenuItem?.isVisible = !isInSelectionMode
            }
        )
        songsRecyclerView.layoutManager = LinearLayoutManager(context)
        songsRecyclerView.adapter = songAdapter
    }

    private fun populateAlbumDetails(view: View, album: Album) {
        val coverImageView: ImageView = view.findViewById(R.id.albumDetailCoverImageView)
        val titleTextView: TextView = view.findViewById(R.id.albumDetailTitleTextView)
        val artistTextView: TextView = view.findViewById(R.id.albumDetailArtistTextView)
        titleTextView.text = album.titulo
        artistTextView.text = album.artistas_principales.joinToString(", ")
        Glide.with(this).load(album.url_portada).into(coverImageView)
    }

    private fun showPlaylistsDialog() {
        val playlistNames = allPlaylists.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_add_to_playlist))
            .setItems(playlistNames) { _, which -> addSelectedSongsToPlaylist(allPlaylists[which]) }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun addSelectedSongsToPlaylist(playlist: Playlist) {
        val selectedSongIds = songAdapter.getSelectedSongs().map { it.id }
        if (selectedSongIds.isEmpty()) return

        // Solo añade las que no estén ya (ver PlaylistRepository.addSongs)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = PlaylistRepository.addSongs(playlist.id, selectedSongIds)
                context?.let {
                    Toast.makeText(it, PlaylistRepository.buildAddMessage(it, result, playlist.name), Toast.LENGTH_SHORT).show()
                }
                songAdapter.clearSelection()
            } catch (e: Exception) {
                context?.let { Toast.makeText(it, getString(R.string.error_adding_song), Toast.LENGTH_SHORT).show() }
            }
        }
    }
    
    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}