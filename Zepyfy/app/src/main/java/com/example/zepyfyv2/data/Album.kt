package com.example.zepyfyv2.data

import android.os.Parcelable
import com.google.firebase.firestore.Exclude
import kotlinx.parcelize.Parcelize

@Parcelize
data class Album(
    @get:Exclude val id: String = "",
    val titulo: String = "",
    val url_portada: String = "",
    val artistas_principales: List<String> = emptyList()
) : Parcelable
