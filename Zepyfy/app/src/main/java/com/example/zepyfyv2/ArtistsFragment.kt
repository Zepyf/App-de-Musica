package com.example.zepyfyv2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.zepyfyv2.adapters.ArtistAdapter
import com.example.zepyfyv2.data.Artist
import com.example.zepyfyv2.data.MusicCatalog
import com.google.android.material.button.MaterialButton

class ArtistsFragment : Fragment(), SearchableFragment {

    private lateinit var artistsRecyclerView: RecyclerView
    private lateinit var artistAdapter: ArtistAdapter
    private lateinit var filterArtistsButton: MaterialButton
    private var allArtists = listOf<Artist>()
    private var currentQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_artists, container, false)
        artistsRecyclerView = view.findViewById(R.id.artistsRecyclerView)
        filterArtistsButton = view.findViewById(R.id.filterArtistsButton)
        
        setupRecyclerView()
        setupFilter()
        
        return view
    }

    private fun setupRecyclerView() {
        artistAdapter = ArtistAdapter(emptyList()) { artist ->
            val action = MainFragmentDirections.actionMainFragmentToArtistDetailFragment(artist)
            findNavController().navigate(action)
        }
        artistsRecyclerView.layoutManager = GridLayoutManager(context, 2)
        artistsRecyclerView.adapter = artistAdapter
    }

    private fun setupFilter() {
        filterArtistsButton.setOnClickListener { 
            applyFilters()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectWhileStarted(MusicCatalog.artists) { artists ->
            allArtists = artists
            applyFilters()
        }
    }

    private fun applyFilters() {
        var filteredList = allArtists

        if (currentQuery.isNotEmpty()) {
            filteredList = filteredList.filter { it.nombre.contains(currentQuery, ignoreCase = true) }
        }

        if (filterArtistsButton.isChecked) {
            filteredList = filteredList.filter { !it.url_fotoartista.isNullOrBlank() }
        }

        artistAdapter.updateArtists(filteredList)
    }

    override fun search(query: String) {
        currentQuery = query
        if (view != null) applyFilters()
    }
}