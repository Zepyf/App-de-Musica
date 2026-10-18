package com.example.zepyfyv2.data

import android.os.Parcelable
import com.google.firebase.firestore.Exclude
import kotlinx.parcelize.Parcelize

@Parcelize
data class Artist(
    @get:Exclude val id: String = "",
    val nombre: String = "",
    val url_fotoartista: String? = null
) : Parcelable
