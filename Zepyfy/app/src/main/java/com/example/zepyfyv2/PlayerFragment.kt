package com.example.zepyfyv2

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.zepyfyv2.data.Album
import com.example.zepyfyv2.data.Artist
import com.example.zepyfyv2.data.MusicCatalog
import com.example.zepyfyv2.databinding.FragmentPlayerBinding
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private var mediaController: MediaController? = null
    private var playerListener: Player.Listener? = null
    private val firestore = FirebaseFirestore.getInstance()

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSlideNext: Boolean? = null

    // Fotos de artista ya pedidas a Firestore en esta pantalla (id -> url, puede ser null)
    private val fetchedArtistPhotos = mutableMapOf<String, String?>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.backButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top
            }
            insets
        }

        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x

                return if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > 100 && Math.abs(velocityX) > 500) {
                        changeSongWithAnimation(isNext = diffX < 0)
                        true
                    } else false
                } else {
                    if (diffY > 100 && Math.abs(velocityY) > 500) {
                        findNavController().navigateUp()
                        true
                    } else false
                }
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (diffY > 150 && Math.abs(diffY) > Math.abs(diffX)) {
                    findNavController().navigateUp()
                    return true
                }
                return false
            }
        })

        val swipeTouchListener = View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        binding.root.setOnTouchListener(swipeTouchListener)
        binding.playerCoverImageView.setOnTouchListener(swipeTouchListener)
        binding.playerCoverImageView.isClickable = true
        binding.playerSongTitle.setOnTouchListener(swipeTouchListener)
        binding.playerSongTitle.isClickable = true
        binding.playerArtistName.setOnTouchListener(swipeTouchListener)
        binding.playerArtistName.isClickable = true

        setupListeners()
        observeMediaController()
    }

    /**
     * MainActivity libera su MediaController en onStop y crea uno nuevo en onStart.
     * Como al volver de segundo plano la vista no se recrea, hay que seguir al controller
     * vigente: si no, el reproductor se quedaría con el antiguo (sin eventos, con la
     * canción y la barra de tiempo congeladas). Se observa mientras la vista está visible.
     */
    private fun observeMediaController() {
        val mainActivity = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    mainActivity.controllerFlow.collect { controller -> bindController(controller) }
                } finally {
                    bindController(null)
                }
            }
        }
    }

    private fun bindController(controller: MediaController?) {
        if (controller === mediaController) return

        // Soltar el controller anterior
        playerListener?.let { mediaController?.removeListener(it) }
        handler.removeCallbacks(updateSeekBarRunnable)
        mediaController = controller

        if (controller == null || _binding == null) return

        // Engancharse al nuevo y refrescar TODO con el estado real de la reproducción
        playerListener?.let { controller.addListener(it) }
        updateUIWithCurrentMedia()
        updateRepeatButtonIcon()
    }

    private fun changeSongWithAnimation(isNext: Boolean) {
        if (_binding == null) return
        pendingSlideNext = isNext
        val width = binding.playerCoverImageView.width.toFloat().takeIf { it > 0 } ?: 1000f
        val endX = if (isNext) -width else width

        binding.playerCoverImageView.animate()
            .translationX(endX)
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                if (isNext) mediaController?.seekToNextMediaItem()
                else mediaController?.seekToPreviousMediaItem()
            }
            .start()
    }

    private fun setupListeners() {
        playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (_binding == null) return
                binding.playerPlayPauseButton.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                )
                if (isPlaying) updateSeekBar() else handler.removeCallbacks(updateSeekBarRunnable)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (_binding == null) return
                updateUIWithCurrentMedia()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (_binding == null) return
                if (playbackState == Player.STATE_READY) {
                    val duration = safeDuration()
                    binding.playerSeekBar.max = duration.toInt()
                    binding.playerTotalTime.text = formatDuration(duration)
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (_binding == null) return
                updateRepeatButtonIcon()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (_binding == null) return
                updateRepeatButtonIcon()
            }
        }
        // El listener se añade al controller en bindController()

        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.playerPlayPauseButton.setOnClickListener {
            mediaController?.playWhenReady = !(mediaController?.playWhenReady ?: false)
        }
        binding.playerPreviousButton.setOnClickListener { changeSongWithAnimation(isNext = false) }
        binding.playerNextButton.setOnClickListener { changeSongWithAnimation(isNext = true) }

        binding.playerAddToPlaylistButton.setOnClickListener {
            (activity as? MainActivity)?.onAddCurrentSongToPlaylistRequested()
        }
        binding.playerRepeatButton.setOnClickListener { cycleRepeatMode() }

        binding.albumRedirect.setOnClickListener { navigateToAlbum() }
        binding.artistRedirect.setOnClickListener { navigateToArtist() }
        binding.lyricsButton.setOnClickListener { navigateToLyrics() }
        binding.queueButton.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(QueueBottomSheet.TAG) == null) {
                QueueBottomSheet().show(childFragmentManager, QueueBottomSheet.TAG)
            }
        }

        binding.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaController?.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateUIWithCurrentMedia() {
        val mediaItem = mediaController?.currentMediaItem
        if (mediaItem != null && _binding != null) {
            val extras = mediaItem.mediaMetadata.extras
            binding.playerSongTitle.text = mediaItem.mediaMetadata.title
            binding.playerArtistName.text = mediaItem.mediaMetadata.artist

            val slideNext = pendingSlideNext
            pendingSlideNext = null

            if (slideNext != null) {
                val width = binding.playerCoverImageView.width.toFloat().takeIf { it > 0 } ?: 1000f
                binding.playerCoverImageView.translationX = if (slideNext) width else -width
                binding.playerCoverImageView.alpha = 0f
                binding.playerCoverImageView.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(250)
                    .start()
            } else {
                binding.playerCoverImageView.translationX = 0f
                binding.playerCoverImageView.alpha = 1f
            }

            Glide.with(this).load(mediaItem.mediaMetadata.artworkUri).into(binding.playerCoverImageView)

            binding.albumRedirectText.text = extras?.getString("album_name")
            Glide.with(this).load(extras?.getString("album_cover_url")).into(binding.albumRedirectImage)

            binding.artistRedirectText.text = extras?.getString("artist_name_0")

            // Foto del artista: extras del MediaItem, catálogo en memoria y, como último
            // recurso, una consulta a Firestore (recordada para no repetirla)
            val artistId = extras?.getString("artist_id_0")
            val artistPhotoUrl = extras?.getString("artist_photo_url")
                ?: MusicCatalog.cachedArtist(artistId)?.url_fotoartista
                ?: artistId?.let { fetchedArtistPhotos[it] }
            if (!artistPhotoUrl.isNullOrEmpty()) {
                Glide.with(this)
                    .load(artistPhotoUrl)
                    .placeholder(R.drawable.artistadesconocido)
                    .error(R.drawable.artistadesconocido)
                    .into(binding.artistRedirectImage)
            } else {
                // Último recurso: el artista no está en el catálogo en memoria
                if (artistId != null && !fetchedArtistPhotos.containsKey(artistId)) {
                    firestore.collection("artistas").document(artistId).get()
                        .addOnSuccessListener { doc ->
                            val url = doc.getString("url_fotoartista")
                            // Recordarla para no repetir la consulta con la siguiente canción del mismo artista
                            fetchedArtistPhotos[artistId] = url
                            val stillSameArtist = mediaController?.currentMediaItem
                                ?.mediaMetadata?.extras?.getString("artist_id_0") == artistId
                            if (isAdded && _binding != null && stillSameArtist) {
                                Glide.with(this)
                                    .load(url)
                                    .placeholder(R.drawable.artistadesconocido)
                                    .error(R.drawable.artistadesconocido)
                                    .into(binding.artistRedirectImage)
                            }
                        }
                } else {
                    // Ya consultado y sin foto
                    Glide.with(this).load(R.drawable.artistadesconocido).into(binding.artistRedirectImage)
                }
            }

            val isPlaying = mediaController?.isPlaying == true
            binding.playerPlayPauseButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )
            if (isPlaying) updateSeekBar() else handler.removeCallbacks(updateSeekBarRunnable)

            val duration = safeDuration()
            binding.playerSeekBar.max = duration.toInt()
            binding.playerTotalTime.text = formatDuration(duration)
            val currentPosition = mediaController?.currentPosition ?: 0
            binding.playerSeekBar.progress = currentPosition.toInt()
            binding.playerCurrentTime.text = formatDuration(currentPosition)

        } else if (mediaController != null && isAdded &&
            findNavController().currentDestination?.id == R.id.playerFragment
        ) {
            // Solo cerramos si hay controller conectado y de verdad no hay nada sonando.
            // Si el controller aún no ha conectado (p. ej. al reabrir la app) esperamos.
            findNavController().navigateUp()
        }
    }

    private fun cycleRepeatMode() {
        mediaController?.let {
            when {
                it.shuffleModeEnabled -> {
                    it.shuffleModeEnabled = false
                    it.repeatMode = Player.REPEAT_MODE_ONE
                }
                it.repeatMode == Player.REPEAT_MODE_ONE -> {
                    it.repeatMode = Player.REPEAT_MODE_ALL
                }
                else -> {
                    it.repeatMode = Player.REPEAT_MODE_ALL
                    it.shuffleModeEnabled = true
                }
            }
        }
    }

    private fun updateRepeatButtonIcon() {
        if (_binding == null) return
        mediaController?.let {
            val iconRes = when {
                it.shuffleModeEnabled -> R.drawable.ic_shuffle
                it.repeatMode == Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
                else -> R.drawable.ic_repeat_all
            }
            binding.playerRepeatButton.setImageResource(iconRes)
        }
    }

    private fun navigateToAlbum() {
        val albumId = mediaController?.currentMediaItem?.mediaMetadata?.extras?.getString("album_id")
        if (albumId == null) {
            Toast.makeText(context, getString(R.string.error_album_not_available), Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Primero el catálogo en memoria; Firestore solo si no está cargado
                val album = MusicCatalog.albums.value?.firstOrNull { it.id == albumId }
                    ?: firestore.collection("albums").document(albumId).get().await().let { doc ->
                        doc.toObject(Album::class.java)?.copy(id = doc.id)
                    }
                if (album != null && isAdded) {
                    val action = PlayerFragmentDirections.actionPlayerFragmentToAlbumDetailFragment(album)
                    findNavController().navigate(action)
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(context, getString(R.string.error_album_not_available), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun navigateToArtist() {
        val artistId = mediaController?.currentMediaItem?.mediaMetadata?.extras?.getString("artist_id_0")
        if (artistId == null) {
            Toast.makeText(context, getString(R.string.error_artist_not_available), Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val artist = MusicCatalog.cachedArtist(artistId)
                    ?: firestore.collection("artistas").document(artistId).get().await().let { doc ->
                        doc.toObject(Artist::class.java)?.copy(id = doc.id)
                    }
                if (artist != null && isAdded) {
                    val action = PlayerFragmentDirections.actionPlayerFragmentToArtistDetailFragment(artist)
                    findNavController().navigate(action)
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(context, getString(R.string.error_artist_not_available), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun navigateToLyrics() {
        val lyricsUrl = mediaController?.currentMediaItem?.mediaMetadata?.extras?.getString("lyrics_url")
        if (!lyricsUrl.isNullOrEmpty()) {
            val action = PlayerFragmentDirections.actionPlayerFragmentToLyricsFragment(lyricsUrl)
            findNavController().navigate(action)
        }
    }

    // Refresco de la barra de tiempo cada 500 ms mientras suena
    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            if (_binding == null) return
            mediaController?.let {
                binding.playerSeekBar.progress = it.currentPosition.toInt()
                binding.playerCurrentTime.text = formatDuration(it.currentPosition)
                handler.postDelayed(this, 500)
            }
        }
    }

    private fun updateSeekBar() {
        handler.removeCallbacks(updateSeekBarRunnable)
        handler.post(updateSeekBarRunnable)
    }

    // duration vale C.TIME_UNSET (negativo) mientras la canción no está preparada
    private fun safeDuration(): Long = (mediaController?.duration ?: 0L).coerceAtLeast(0L)

    private fun formatDuration(duration: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) -
            TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(updateSeekBarRunnable)
        playerListener?.let { mediaController?.removeListener(it) }
        mediaController = null
        _binding = null
    }
}
