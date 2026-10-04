package com.akira.simpleplayer.data

data class Track(
    val id: String,
    val name: String,
    val title: String,
    val artist: String,
    val album: String,
    val folder: String,
    val ext: String,
    val size: Long,
    val mtime: Long,
    val hasCover: Boolean
)

data class LyricLine(val time: Double?, val text: String)


