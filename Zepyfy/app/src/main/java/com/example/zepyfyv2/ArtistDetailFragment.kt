package com.example.zepyfyv2

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.zepyfyv2.data.Artist
import com.example.zepyfyv2.data.MusicCatalog
import com.example.zepyfyv2.data.Playlist
import com.example.zepyfyv2.data.PlaylistRepository
import com.example.zepyfyv2.data.Song
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ArtistDetailFragment : Fragment() {

    private val args: ArtistDetailFragmentArgs by navArgs()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private lateinit var songAdapter: SongAdapter
    private var allPlaylists = listOf<Playlist>()
    private var addToPlaylistMenuItem: MenuItem? = null
    private var playNextMenuItem: MenuItem? = null
    private lateinit var artistImageView: ImageView
    private var currentArtist: Artist? = null
    private var songList = listOf<Song>()
    private var listener: SongsFragment.OnSongInteractionListener? = null

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { 
            currentArtist?.let { artist ->
                uploadImageAndUpdateArtist(artist, it)
            }
        }
    }

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
        currentArtist = args.artist
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_artist_detail, container, false)
        artistImageView = view.findViewById(R.id.artistDetailImageView)
        artistImageView.setOnClickListener { selectImageLauncher.launch("image/*") }
        setupToolbar(view)
        setupRecyclerView(view)
        populateArtistDetails(view, currentArtist!!)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMenu()
        val artistId = args.artist.id
        // Canciones del artista filtradas del catálogo compartido (sin consultas propias)
        collectWhileStarted(MusicCatalog.songs) { songs ->
            songList = songs.filter { it.artistas?.contains(artistId) == true }
            songAdapter.updateSongs(songList)
        }
        collectWhileStarted(MusicCatalog.playlists) { allPlaylists = it }
    }
    
    /** Menú de la barra. Con MenuProvider se quita solo al destruirse la vista. */
    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.main_menu, menu)
                addToPlaylistMenuItem = menu.findItem(R.id.action_add_to_playlist)
                playNextMenuItem = menu.findItem(R.id.action_play_next)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_play_next -> {
                        (activity as? MainActivity)?.playNext(songAdapter.getSelectedSongs())
                        songAdapter.clearSelection()
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
        val songsRecyclerView: RecyclerView = view.findViewById(R.id.artistSongsRecyclerView)
        songAdapter = SongAdapter(emptyList(), 
            onSongClicked = { song, _ -> playFrom(song) },
            onSelectionChanged = { isInSelectionMode, _ ->
                addToPlaylistMenuItem?.isVisible = isInSelectionMode
                playNextMenuItem?.isVisible = isInSelectionMode
            }
        )
        songsRecyclerView.layoutManager = LinearLayoutManager(context)
        songsRecyclerView.adapter = songAdapter
    }

    private fun populateArtistDetails(view: View, artist: Artist) {
        val nameTextView: TextView = view.findViewById(R.id.artistDetailNameTextView)
        nameTextView.text = artist.nombre
        updateArtistImage(artist.url_fotoartista)
    }

    private fun updateArtistImage(imageUrl: String?) {
        Glide.with(this).load(imageUrl).placeholder(R.drawable.artistadesconocido).error(R.drawable.artistadesconocido).circleCrop().into(artistImageView)
    }
    
    private fun uploadImageAndUpdateArtist(artist: Artist, imageUri: Uri) {
        val storageRef = storage.reference.child("fotosArtistas/${artist.id}.jpg")
        // Las tareas de Firebase ya trabajan en segundo plano: basta con el scope de la
        // vista, que se cancela al salir y evita tocar vistas destruidas.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val uploadTask = storageRef.putFile(imageUri).await()
                val downloadUrl = uploadTask.storage.downloadUrl.await().toString()
                firestore.collection("artistas").document(artist.id)
                    .update("url_fotoartista", downloadUrl).await()

                currentArtist = currentArtist?.copy(url_fotoartista = downloadUrl)
                updateArtistImage(downloadUrl)
                context?.let { Toast.makeText(it, getString(R.string.success_photo_updated), Toast.LENGTH_SHORT).show() }
            } catch (e: CancellationException) {
                throw e // salir de la pantalla no es un error
            } catch (e: Exception) {
                Log.e("ArtistDetailFragment", "Error subiendo foto", e)
                context?.let { Toast.makeText(it, getString(R.string.error_uploading_image), Toast.LENGTH_SHORT).show() }
            }
        }
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