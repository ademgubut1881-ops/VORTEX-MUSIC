package io.github.vortexmusic.player

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val added: Long,
    val duration: Long = 0L,
    val year: Int = 0,
    val track: Int = 0,
    val modified: Long = 0L,
    val fileName: String = "",
    val path: String = ""
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}