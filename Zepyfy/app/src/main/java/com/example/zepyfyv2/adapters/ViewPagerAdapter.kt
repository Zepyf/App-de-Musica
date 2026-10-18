package com.example.zepyfyv2.adapters

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.zepyfyv2.AlbumsFragment
import com.example.zepyfyv2.ArtistsFragment
import com.example.zepyfyv2.PlaylistsFragment
import com.example.zepyfyv2.SongsFragment

class ViewPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> SongsFragment()
            1 -> ArtistsFragment()
            2 -> AlbumsFragment()
            3 -> PlaylistsFragment()
            else -> throw IllegalStateException("Invalid position: $position")
        }
    }
}