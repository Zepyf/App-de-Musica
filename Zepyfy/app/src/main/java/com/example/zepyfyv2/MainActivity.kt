package com.example.zepyfyv2

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.bumptech.glide.Glide
import com.example.zepyfyv2.auth.GoogleAuth
import com.example.zepyfyv2.auth.SessionCleanup
import com.example.zepyfyv2.data.MusicCatalog
import com.example.zepyfyv2.data.Playlist
import com.example.zepyfyv2.data.PlaylistRepository
import com.example.zepyfyv2.data.Song
import com.example.zepyfyv2.download.OfflineDownloads
import com.example.zepyfyv2.media.MediaItems
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), SongsFragment.OnSongInteractionListener {

    private lateinit var navController: NavController
    var mediaController: MediaController? = null
        private set
    private lateinit var controllerFuture: ListenableFuture<MediaController>

    // El MediaController se libera en onStop y se crea uno nuevo en onStart. Los fragments
    // (reproductor, letras, cola) observan este flujo para usar siempre el controller vigente.
    private val _controllerFlow = MutableStateFlow<MediaController?>(null)
    val controllerFlow: StateFlow<MediaController?> = _controllerFlow.asStateFlow()

    // Player UI Views
    private lateinit var playerControls: View
    private lateinit var playerCoverImageView: ImageView
    private lateinit var playerSongTitleTextView: TextView
    private lateinit var playerArtistNameTextView: TextView
    private lateinit var playerPlayPauseButton: ImageView
    private lateinit var playerNextButton: ImageView
    private lateinit var playerPreviousButton: ImageView
    private lateinit var playerSeekbar: SeekBar

    private val handler = Handler(Looper.getMainLooper())
    private var accessDeniedDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val signedIn = GoogleAuth.currentUser != null
        enableEdgeToEdge()
        // Sin sesión no restauramos fragments: vamos directos a la pantalla de login
        super.onCreate(if (signedIn) savedInstanceState else null)
        if (!signedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nav_host_fragment)) { v, insets ->
            insets.toWindowInsets()?.let { platformInsets ->
                v.onApplyWindowInsets(platformInsets)
            }
            insets
        }

        initPlayerViews()

        navController.addOnDestinationChangedListener { _, _, _ ->
            updateMiniPlayerVisibility()
        }

        observeAccessDenied()
        keepOfflineDownloadsInSync()
    }

    // ---------- Descargas sin conexión ----------

    /**
     * Cada vez que cambia lo marcado para escuchar sin conexión, el catálogo de
     * canciones o las playlists, pone las descargas al día (descarga lo nuevo,
     * borra lo que ya no hace falta).
     */
    private fun keepOfflineDownloadsInSync() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    OfflineDownloads.markedKeys(this@MainActivity),
                    MusicCatalog.songs
                ) { marked, songs -> marked to songs }
                    .collectLatest { (_, songs) ->
                        // Esperar a tener el catálogo: sin él se borrarían todas las descargas
                        if (songs == null) return@collectLatest
                        val allowRemovals = !MusicCatalog.accessDenied.value
                        OfflineDownloads.sync(this@MainActivity, songs, allowRemovals)
                    }
            }
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* sin acción */ }

    /** Pide permiso de notificaciones (Android 13+) para mostrar el progreso de las descargas. */
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---------- Cuenta y permisos ----------

    /**
     * Si las reglas de Firebase rechazan a esta cuenta, avisa y muestra su UID,
     * que es justo lo que hay que añadir a firestore.rules y storage.rules.
     */
    private fun observeAccessDenied() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MusicCatalog.accessDenied.collect { denied ->
                    if (denied) showAccessDeniedDialog() else accessDeniedDialog?.dismiss()
                }
            }
        }
    }

    private fun showAccessDeniedDialog() {
        if (accessDeniedDialog?.isShowing == true) return
        val user = GoogleAuth.currentUser ?: return
        accessDeniedDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.access_denied_title))
            .setMessage(getString(R.string.access_denied_message, user.email ?: "-", user.uid))
            .setPositiveButton(getString(R.string.action_copy_uid)) { _, _ -> copyToClipboard("UID", user.uid) }
            .setNegativeButton(getString(R.string.action_sign_out)) { _, _ -> signOut() }
            .setNeutralButton(getString(R.string.action_close), null)
            .show()
    }

    /** Diálogo de cuenta (botón de perfil de la pantalla principal). */
    fun showAccountDialog() {
        val user = GoogleAuth.currentUser ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.account_title))
            .setMessage(getString(R.string.account_message, user.email ?: "-", user.uid))
            .setPositiveButton(getString(R.string.action_copy_uid)) { _, _ -> copyToClipboard("UID", user.uid) }
            .setNegativeButton(getString(R.string.action_sign_out)) { _, _ -> confirmSignOut() }
            .setNeutralButton(getString(R.string.action_close), null)
            .show()
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.sign_out_confirm))
            .setPositiveButton(getString(R.string.action_sign_out)) { _, _ -> signOut() }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun signOut() {
        lifecycleScope.launch {
            // Parar la música: la cola pertenece a la sesión que se cierra
            mediaController?.run {
                stop()
                clearMediaItems()
            }
            // Borrar todo lo local de esta sesión (catálogo en memoria, caché de Firestore,
            // cola guardada y descargas) para que otra cuenta no vea nada de esta
            SessionCleanup.clearLocalData(this@MainActivity)
            GoogleAuth.signOut(this@MainActivity)
            startActivity(Intent(this@MainActivity, LoginActivity::class.java))
            finish()
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.uid_copied), Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                val controller = try {
                    controllerFuture.get()
                } catch (e: Exception) {
                    return@addListener // future cancelado (p. ej. onStop antes de conectar)
                }
                mediaController = controller
                updateMiniPlayerVisibility()
                updatePlayerUIWithCurrentMedia()
                setupPlayerListeners()
                syncMiniPlayerSeekBar()
                _controllerFlow.value = controller
            },
            MoreExecutors.directExecutor()
        )
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(updateSeekBarRunnable)
        // Avisar a los fragments ANTES de liberar para que se desenganchen del controller viejo
        _controllerFlow.value = null
        mediaController = null
        MediaController.releaseFuture(controllerFuture)
    }

    private fun initPlayerViews() {
        playerControls = findViewById(R.id.player_controls)
        playerControls.setOnClickListener {
            navController.navigate(R.id.action_global_playerFragment)
        }
        playerCoverImageView = playerControls.findViewById(R.id.playerCoverImageView)
        playerSongTitleTextView = playerControls.findViewById(R.id.playerSongTitleTextView)
        playerArtistNameTextView = playerControls.findViewById(R.id.playerArtistNameTextView)
        playerPlayPauseButton = playerControls.findViewById(R.id.playerPlayPauseButton)
        playerNextButton = playerControls.findViewById(R.id.playerNextButton)
        playerPreviousButton = playerControls.findViewById(R.id.playerPreviousButton)
        playerSeekbar = playerControls.findViewById(R.id.playerSeekbar)

        ViewCompat.setOnApplyWindowInsetsListener(playerControls) { v, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navigationBars.bottom
            }
            insets
        }
    }

    private fun setupPlayerListeners() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerPlayPauseButton.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                )
                if (isPlaying) updateSeekBar() else handler.removeCallbacks(updateSeekBarRunnable)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateMiniPlayerVisibility()
                updatePlayerUIWithCurrentMedia()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    playerSeekbar.max = safeDuration().toInt()
                }
            }
        })

        playerPlayPauseButton.setOnClickListener {
            mediaController?.playWhenReady = !(mediaController?.playWhenReady ?: false)
        }
        playerNextButton.setOnClickListener { mediaController?.seekToNextMediaItem() }
        playerPreviousButton.setOnClickListener { mediaController?.seekToPreviousMediaItem() }
        playerSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaController?.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateMiniPlayerVisibility() {
        val isPlayerFragmentVisible = navController.currentDestination?.id == R.id.playerFragment
        val isMediaAvailable = mediaController?.currentMediaItem != null

        playerControls.visibility = if (isPlayerFragmentVisible || !isMediaAvailable) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    fun onAddCurrentSongToPlaylistRequested() {
        // Playlists del catálogo compartido (sin lectura extra si ya están cargadas)
        lifecycleScope.launch {
            val playlists = MusicCatalog.awaitPlaylists()
            if (playlists.isEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.error_no_playlists), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val playlistNames = playlists.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.dialog_add_to_playlist))
                .setItems(playlistNames) { _, which ->
                    addSongToPlaylist(playlists[which])
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
        }
    }

    private fun addSongToPlaylist(playlist: Playlist) {
        val songId = mediaController?.currentMediaItem?.mediaId
        if (songId == null) {
            Toast.makeText(this, getString(R.string.error_no_song_playing), Toast.LENGTH_SHORT).show()
            return
        }

        // Comprueba si ya está antes de escribir (ver PlaylistRepository.addSongs)
        lifecycleScope.launch {
            try {
                val result = PlaylistRepository.addSongs(playlist.id, listOf(songId))
                Toast.makeText(
                    this@MainActivity,
                    PlaylistRepository.buildAddMessage(this@MainActivity, result, playlist.name),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.error_adding_song), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Refresco de la barra del mini reproductor cada 500 ms mientras suena
    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            mediaController?.let {
                playerSeekbar.progress = it.currentPosition.toInt()
                handler.postDelayed(this, 500)
            }
        }
    }

    private fun updateSeekBar() {
        handler.removeCallbacks(updateSeekBarRunnable)
        handler.post(updateSeekBarRunnable)
    }

    // Al volver a la app no llega ningún onIsPlayingChanged (la música ya sonaba),
    // así que hay que colocar la barra y reactivar el refresco a mano.
    private fun syncMiniPlayerSeekBar() {
        val controller = mediaController ?: return
        playerSeekbar.max = safeDuration().toInt()
        playerSeekbar.progress = controller.currentPosition.toInt()
        if (controller.isPlaying) updateSeekBar() else handler.removeCallbacks(updateSeekBarRunnable)
    }

    // duration vale C.TIME_UNSET (un número negativo enorme) mientras no está preparada
    private fun safeDuration(): Long = (mediaController?.duration ?: 0L).coerceAtLeast(0L)

    private fun updatePlayerUIWithCurrentMedia() {
        val mediaItem = mediaController?.currentMediaItem
        if (mediaItem != null) {
            playerSongTitleTextView.text = mediaItem.mediaMetadata.title
            playerSongTitleTextView.isSelected = true
            playerArtistNameTextView.text = mediaItem.mediaMetadata.artist
            playerArtistNameTextView.isSelected = true
            Glide.with(this).load(mediaItem.mediaMetadata.artworkUri).into(playerCoverImageView)

            playerPlayPauseButton.setImageResource(
                if (mediaController?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )
        }
    }

    override fun onSongClicked(songs: List<Song>, position: Int) {
        val controller = mediaController ?: return
        controller.setMediaItems(MediaItems.fromSongs(songs), position, 0)
        controller.prepare()
        controller.play()
    }

    /**
     * Inserta [songs] justo después de la canción actual, en el orden recibido.
     * Si no hay nada sonando, empieza a reproducirlas directamente.
     */
    fun playNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        val controller = mediaController ?: return
        val items = MediaItems.fromSongs(songs)
        if (controller.mediaItemCount == 0) {
            controller.setMediaItems(items)
            controller.prepare()
            controller.play()
        } else {
            controller.addMediaItems(controller.currentMediaItemIndex + 1, items)
        }
        Toast.makeText(
            this,
            resources.getQuantityString(R.plurals.play_next_added, songs.size, songs.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onSelectionModeChange(isActive: Boolean, selectedCount: Int) {
        // Handled by MainFragment
    }
}
