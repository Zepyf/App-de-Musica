package com.example.zepyfyv2.data

import android.os.Parcelable
import com.google.firebase.firestore.Exclude
import kotlinx.parcelize.Parcelize

@Parcelize
data class Song(
    @get:Exclude val id: String = "",
    val titulo: String = "",
    val duracion: Int = 0,
    val url_cancion: String? = null,
    val url_portada: String? = null,
    val url_letra: String? = null,
    val album: String? = null,
    val album_nombre: String? = null,
    val artistas: List<String>? = null,
    val artistas_nombres: List<String>? = null
) : Parcelable
