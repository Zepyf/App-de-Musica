package com.example.zepyfyv2.data

import android.os.Parcelable
import com.google.firebase.firestore.Exclude
import kotlinx.parcelize.Parcelize

@Parcelize
data class Playlist(
    @get:Exclude val id: String = "",
    val name: String = "",
    val coverImageUrl: String? = null,
    val canciones: List<String>? = null
) : Parcelable
