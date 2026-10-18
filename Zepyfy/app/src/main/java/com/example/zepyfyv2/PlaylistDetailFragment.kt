package com.example.zepyfyv2

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.zepyfyv2.adapters.SongAdapter
import com.example.zepyfyv2.data.MusicCatalog
import com.example.zepyfyv2.data.Playlist
import com.example.zepyfyv2.data.PlaylistRepository
import com.example.zepyfyv2.data.Song
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class PlaylistDetailFragment : Fragment() {

    private val args: PlaylistDetailFragmentArgs by navArgs()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private lateinit var songAdapter: SongAdapter
    private var addToPlaylistMenuItem: MenuItem? = null
    private var removeFromPlaylistMenuItem: MenuItem? = null
    private var playNextMenuItem: MenuItem? = null
    private lateinit var coverImageView: ImageView
    private lateinit var nameEditText: EditText
    private var currentPlaylist: Playlist? = null
    private var songList = mutableListOf<Song>()
    private var allPlaylists = listOf<Playlist>()
    private var canAddToOtherPlaylists = false
    private var listener: SongsFragment.OnSongInteractionListener? = null
    private var currentSongIds: List<String>? = null

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            currentPlaylist?.let { playlist ->
                uploadImageAndUpdatePlaylist(playlist, it)
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
        currentPlaylist = args.playlist
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_playlist_detail, container, false)
        coverImageView = view.findViewById(R.id.playlistDetailCoverImageView)
        nameEditText = view.findViewById(R.id.playlistDetailNameEditText)

        coverImageView.setOnClickListener { selectImageLauncher.launch("image/*") }
        view.findViewById<View>(R.id.addSongsButton).setOnClickListener { openSongPicker() }
        setupToolbar(view)
        setupRecyclerView(view)
        populatePlaylistDetails(view, currentPlaylist!!)
        setupNameEditing()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMenu()
        val playlistId = currentPlaylist?.id ?: return

        // Playlist y canciones salen del catálogo compartido, que ya escucha `Playlist`
        // y `canciones` en tiempo real: cualquier cambio se refleja aquí al momento.
        collectWhileStarted(
            combine(MusicCatalog.playlists, MusicCatalog.songs) { playlists, songs ->
                if (playlists == null || songs == null) null else playlists to songs
            }
        ) { (playlists, songs) ->
            allPlaylists = playlists
            canAddToOtherPlaylists = playlists.size > 1

            val playlist = playlists.firstOrNull { it.id == playlistId } ?: return@collectWhileStarted
            currentPlaylist = playlist // nombre, portada y canciones al día
            val songIds = playlist.canciones ?: emptyList()
            currentSongIds = songIds

            // Mientras se arrastra una canción no se toca la lista: al soltar se guarda
            // el orden nuevo y el catálogo devuelve la playlist ya reordenada.
            if (isDragging) return@collectWhileStarted

            // Orden del array de Firestore (= orden en que se añadieron o se reordenaron),
            // no alfabético como antes
            songList.clear()
            songList.addAll(MusicCatalog.songsByIds(songs, songIds))
            songAdapter.updateSongs(songList.toList())
        }
    }

    // ---------- Reordenar arrastrando ----------

    private var isDragging = false
    private var dragMoved = false

    private val dragHelper by lazy {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            // El arrastre empieza solo desde el asa (la pulsación larga es para seleccionar)
            override fun isLongPressDragEnabled() = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                songAdapter.moveItem(from, to)
                dragMoved = true
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    isDragging = true
                    dragMoved = false
                    viewHolder?.itemView?.alpha = 0.7f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                isDragging = false
                if (dragMoved) saveCurrentOrder()
            }
        })
    }

    /** Guarda en Firestore el orden que se ve ahora en la lista. */
    private fun saveCurrentOrder() {
        val playlistId = currentPlaylist?.id ?: return
        val ordered = songAdapter.currentSongs.map { it.id }
        // Ids del array cuya canción ya no existe en el catálogo: se conservan al final
        // para no perder nada de la playlist al reordenar
        val orderedSet = ordered.toSet()
        val orphanIds = (currentSongIds ?: emptyList()).filter { it !in orderedSet }
        val newOrder = ordered + orphanIds

        songList.clear()
        songList.addAll(songAdapter.currentSongs)
        currentSongIds = newOrder

        val appContext = requireContext().applicationContext
        PlaylistRepository.reorderSongs(playlistId, newOrder) {
            Toast.makeText(appContext, appContext.getString(R.string.error_updating_playlist), Toast.LENGTH_SHORT).show()
        }
    }

    /** Menú de la barra. Con MenuProvider se quita solo al destruirse la vista. */
    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.playlist_detail_menu, menu)
                addToPlaylistMenuItem = menu.findItem(R.id.action_add_to_playlist)
                removeFromPlaylistMenuItem = menu.findItem(R.id.action_remove_from_playlist)
                playNextMenuItem = menu.findItem(R.id.action_play_next)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_add_to_playlist -> {
                        showPlaylistsDialog()
                        true
                    }
                    R.id.action_remove_from_playlist -> {
                        removeSelectedSongsFromPlaylist()
                        true
                    }
                    R.id.action_play_next -> {
                        (activity as? MainActivity)?.playNext(songAdapter.getSelectedSongs())
                        songAdapter.clearSelection()
                        true
                    }
                    R.id.action_delete_playlist -> {
                        confirmDeletePlaylist()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }


    private fun confirmDeletePlaylist() {
        val playlist = currentPlaylist ?: return
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
        Toast.makeText(appContext, appContext.getString(R.string.success_playlist_deleted, playlist.name), Toast.LENGTH_SHORT).show()
        findNavController().navigateUp()
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
        val songsRecyclerView: RecyclerView = view.findViewById(R.id.playlistSongsRecyclerView)
        songAdapter = SongAdapter(
            emptyList(),
            onSongClicked = { song, _ -> playFrom(song) },
            onSelectionChanged = { isInSelectionMode, _ ->
                removeFromPlaylistMenuItem?.isVisible = isInSelectionMode
                addToPlaylistMenuItem?.isVisible = isInSelectionMode && canAddToOtherPlaylists
                playNextMenuItem?.isVisible = isInSelectionMode
            },
            // Muestra el asa de arrastre en cada fila
            onStartDrag = { holder -> dragHelper.startDrag(holder) }
        )
        songsRecyclerView.layoutManager = LinearLayoutManager(context)
        songsRecyclerView.adapter = songAdapter
        dragHelper.attachToRecyclerView(songsRecyclerView)
    }

    /** Abre la lista de todas las canciones para añadir/quitar de esta playlist. */
    private fun openSongPicker() {
        val playlist = currentPlaylist ?: return
        // Pasamos las canciones actuales para que la pantalla se pinte bien desde el primer frame
        val withCurrentSongs = playlist.copy(canciones = currentSongIds)
        val action = PlaylistDetailFragmentDirections
            .actionPlaylistDetailFragmentToEditPlaylistSongsFragment(withCurrentSongs)
        findNavController().navigate(action)
    }

    private fun populatePlaylistDetails(view: View, playlist: Playlist) {
        nameEditText.setText(playlist.name)
        updateCoverImage(playlist.coverImageUrl)
    }

    private fun setupNameEditing() {
        nameEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                updatePlaylistName(v.text.toString())
                v.clearFocus()
                return@setOnEditorActionListener true
            }
            false
        }
    }

    private fun updatePlaylistName(newName: String) {
        val playlistId = currentPlaylist?.id ?: return
        if (newName.isBlank() || newName == currentPlaylist?.name) return

        firestore.collection("Playlist").document(playlistId).update("name", newName)
            .addOnSuccessListener {
                Toast.makeText(context, getString(R.string.success_name_updated), Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(context, getString(R.string.error_updating_name), Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateCoverImage(imageUrl: String?) {
        Glide.with(this).load(imageUrl)
            .placeholder(R.mipmap.ic_launcher)
            .error(R.mipmap.ic_launcher)
            .into(coverImageView)
    }

    private fun uploadImageAndUpdatePlaylist(playlist: Playlist, imageUri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val storageRef = storage.reference.child("portadasPlaylist/${playlist.id}.jpg")
                val uploadTask = storageRef.putFile(imageUri).await()
                val downloadUrl = uploadTask.storage.downloadUrl.await().toString()
                firestore.collection("Playlist").document(playlist.id)
                    .update("coverImageUrl", downloadUrl).await()
                currentPlaylist = currentPlaylist?.copy(coverImageUrl = downloadUrl)
                updateCoverImage(downloadUrl)
                Toast.makeText(context, getString(R.string.success_cover_updated), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, getString(R.string.error_uploading_image), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPlaylistsDialog() {
        val otherPlaylists = allPlaylists.filter { it.id != currentPlaylist?.id }
        val playlistNames = otherPlaylists.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_add_to_other_playlist))
            .setItems(playlistNames) { _, which ->
                addSelectedSongsToOtherPlaylist(otherPlaylists[which])
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun addSelectedSongsToOtherPlaylist(playlist: Playlist) {
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

    private fun removeSelectedSongsFromPlaylist() {
        val selectedSongIds = songAdapter.getSelectedSongs().map { it.id }
        if (selectedSongIds.isEmpty()) return
        val playlistId = currentPlaylist?.id ?: return

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_remove_songs_title))
            .setMessage(getString(R.string.dialog_remove_songs_message, selectedSongIds.size))
            .setPositiveButton(getString(R.string.action_remove)) { _, _ ->
                firestore.collection("Playlist").document(playlistId)
                    .update("canciones", FieldValue.arrayRemove(*selectedSongIds.toTypedArray()))
                    .addOnSuccessListener {
                        Toast.makeText(context, getString(R.string.success_songs_removed), Toast.LENGTH_SHORT).show()
                        songAdapter.clearSelection()
                    }
                    .addOnFailureListener {
                        Toast.makeText(context, getString(R.string.error_removing_songs), Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}
