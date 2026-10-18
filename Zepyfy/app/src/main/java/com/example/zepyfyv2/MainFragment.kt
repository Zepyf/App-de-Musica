package com.example.zepyfyv2

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.zepyfyv2.adapters.ViewPagerAdapter
import com.example.zepyfyv2.data.MusicCatalog
import com.example.zepyfyv2.data.Playlist
import com.example.zepyfyv2.data.PlaylistRepository
import com.example.zepyfyv2.data.Song
import com.example.zepyfyv2.databinding.FragmentMainBinding
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class MainFragment : Fragment(), SongsFragment.OnSongInteractionListener {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val firestore = FirebaseFirestore.getInstance()
    private var mainActivityListener: SongsFragment.OnSongInteractionListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is SongsFragment.OnSongInteractionListener) {
            mainActivityListener = context
        } else {
            throw RuntimeException("$context must implement OnSongInteractionListener")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        val adapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_songs)
                1 -> getString(R.string.tab_artists)
                2 -> getString(R.string.tab_albums)
                3 -> getString(R.string.tab_playlists)
                else -> null
            }
        }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                binding.addPlaylistFab.visibility = if (position == 3) View.VISIBLE else View.GONE
                onSelectionModeChange(false, 0)
            }
        })

        binding.addPlaylistFab.setOnClickListener { showAddPlaylistDialog() }
        binding.accountButton.setOnClickListener { (activity as? MainActivity)?.showAccountDialog() }
        binding.actionAddToPlaylist.setOnClickListener { showPlaylistsDialogForAdding() }
        binding.actionPlayNext.setOnClickListener { playSelectedNext() }

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val tag = "f" + binding.viewPager.currentItem
                val currentFragment = childFragmentManager.findFragmentByTag(tag) as? SearchableFragment
                currentFragment?.search(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onSongClicked(songs: List<Song>, position: Int) {
        mainActivityListener?.onSongClicked(songs, position)
    }

    override fun onSelectionModeChange(isActive: Boolean, selectedCount: Int) {
        val visibility = if (isActive) View.VISIBLE else View.GONE
        binding.actionAddToPlaylist.visibility = visibility
        binding.actionPlayNext.visibility = visibility
    }

    private fun playSelectedNext() {
        val tag = "f" + binding.viewPager.currentItem
        val songsFragment = childFragmentManager.findFragmentByTag(tag) as? SongsFragment ?: return
        (activity as? MainActivity)?.playNext(songsFragment.getSelectedSongs())
        songsFragment.clearSongSelection()
    }

    private fun showAddPlaylistDialog() {
        val dialogLayout = layoutInflater.inflate(R.layout.dialog_add_playlist, null)
        val editText = dialogLayout.findViewById<EditText>(R.id.playlistNameEditText)

        AlertDialog.Builder(requireContext())
            .setView(dialogLayout)
            .setPositiveButton(getString(R.string.action_create)) { _, _ ->
                val playlistName = editText.text.toString()
                if (playlistName.isNotBlank()) {
                    createNewPlaylist(playlistName)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun createNewPlaylist(playlistName: String) {
        val newPlaylist = hashMapOf(
            "name" to playlistName,
            "coverImageUrl" to null,
            "canciones" to emptyList<String>()
        )
        // No se espera al servidor: la playlist aparece sola gracias al listener de
        // MusicCatalog (también sin conexión, porque Firestore aplica la escritura en local).
        val appContext = requireContext().applicationContext
        firestore.collection("Playlist").add(newPlaylist)
            .addOnFailureListener {
                Toast.makeText(appContext, appContext.getString(R.string.error_creating_playlist), Toast.LENGTH_SHORT).show()
            }
    }

    private fun findActiveSongsFragment(): SongsFragment? {
        val tag = "f" + (_binding?.viewPager?.currentItem ?: 0)
        return (childFragmentManager.findFragmentByTag(tag) as? SongsFragment)
            ?: childFragmentManager.fragments.filterIsInstance<SongsFragment>().firstOrNull()
    }

    private fun showPlaylistsDialogForAdding() {
        val songsFragment = findActiveSongsFragment()
        val selectedSongIds = songsFragment?.getSelectedSongIds() ?: emptyList()
        if (selectedSongIds.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            // Del catálogo compartido: sin lectura extra si ya está cargado
            val playlists = MusicCatalog.awaitPlaylists()
            if (playlists.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.error_no_playlists), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val playlistNames = playlists.map { it.name }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.dialog_add_to_playlist))
                .setItems(playlistNames) { _, which ->
                    addSongsToPlaylist(playlists[which], selectedSongIds)
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
        }
    }

    private fun addSongsToPlaylist(playlist: Playlist, songIds: List<String>) {
        // Solo añade las que no estén ya (ver PlaylistRepository.addSongs)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = PlaylistRepository.addSongs(playlist.id, songIds)
                context?.let {
                    Toast.makeText(it, PlaylistRepository.buildAddMessage(it, result, playlist.name), Toast.LENGTH_SHORT).show()
                }
                findActiveSongsFragment()?.clearSongSelection()
            } catch (e: Exception) {
                context?.let { Toast.makeText(it, getString(R.string.error_adding_song), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDetach() {
        super.onDetach()
        mainActivityListener = null
    }
}
