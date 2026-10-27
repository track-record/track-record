package com.trackrecord.data.deezer

/** A public Deezer playlist the library offers to everyone. */
data class DefaultPlaylist(val name: String, val deezerId: Long)

/** One playlist of hits per decade, from the 70s on (feedback round 48: the ones Felix picked). */
object DefaultPlaylists {
    val ALL = listOf(
        DefaultPlaylist("70s Hits", 1470022445),
        DefaultPlaylist("80s Hits", 867825522),
        DefaultPlaylist("90s Hits", 878989033),
        DefaultPlaylist("00s Hits", 248297032),
        DefaultPlaylist("10s Hits", 14917741483),
        DefaultPlaylist("20s Hits", 13650084141),
    )
}
