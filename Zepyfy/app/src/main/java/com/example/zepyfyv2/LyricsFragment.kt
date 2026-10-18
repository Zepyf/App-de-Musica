package com.example.zepyfyv2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.example.zepyfyv2.databinding.FragmentLyricsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class LyricsFragment : Fragment() {

    private var _binding: FragmentLyricsBinding? = null
    private val binding get() = _binding!!

    private var mediaController: MediaController? = null
    private var playerListener: Player.Listener? = null
    private var lyricsJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLyricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupToolbar()
        setupListeners()
        observeMediaController()
    }

    // Igual que en PlayerFragment: seguir al controller vigente de MainActivity
    // (se recrea cada vez que la app vuelve de segundo plano).
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
        playerListener?.let { mediaController?.removeListener(it) }
        mediaController = controller
        if (controller == null || _binding == null) return
        playerListener?.let { controller.addListener(it) }
        loadLyricsForCurrentSong()
    }

    private fun setupToolbar() {
        (activity as AppCompatActivity).setSupportActionBar(binding.toolbar)
        (activity as AppCompatActivity).supportActionBar?.setDisplayHomeAsUpEnabled(true)
        (activity as AppCompatActivity).supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.navigationIcon?.setTint(ContextCompat.getColor(requireContext(), R.color.zepyfy_accent))
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
    }

    private fun setupListeners() {
        playerListener = object: Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (_binding == null) return
                loadLyricsForCurrentSong()
            }
        }
        // El listener se añade al controller en bindController()
    }

    private fun loadLyricsForCurrentSong() {
        // Cancelar la descarga anterior si se cambia de canción antes de que termine
        lyricsJob?.cancel()
        val lyricsUrl = mediaController?.currentMediaItem?.mediaMetadata?.extras?.getString("lyrics_url")

        if (lyricsUrl.isNullOrEmpty()) {
            binding.lyricsTextView.text = getString(R.string.lyrics_not_available)
            return
        }

        binding.lyricsTextView.text = getString(R.string.lyrics_loading)
        // En el scope de la vista (se cancela al salir); solo la lectura de red va en IO.
        lyricsJob = viewLifecycleOwner.lifecycleScope.launch {
            val text = try {
                withContext(Dispatchers.IO) { downloadText(lyricsUrl) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _binding?.lyricsTextView?.text = text ?: getString(R.string.lyrics_error)
        }
    }

    /** Descarga con tiempo límite (URL.readText() podía quedarse colgado indefinidamente). */
    private fun downloadText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        playerListener?.let { mediaController?.removeListener(it) }
        mediaController = null
        _binding = null
    }
}