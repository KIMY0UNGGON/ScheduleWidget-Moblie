package com.schedulewidget.mobile.music

import android.content.Context
import com.schedulewidget.mobile.data.MusicPlaylist
import com.schedulewidget.mobile.data.MusicTrack
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.Spotify
import com.schedulewidget.mobile.data.YouTube

/** Turns a YouTube / YouTube Music or Spotify link into playlist tracks. Shared by the link dialog and the share sheet. */
object YouTubeImport {
    data class Result(val tracks: List<MusicTrack>, val message: String)

    /** Title of a (normalized) YouTube or Spotify link, or null. */
    suspend fun title(link: String): String? =
        Spotify.parse(link)?.let { SpotifyFetch.title(it) } ?: YouTubeFetch.title(link)

    /**
     * [link] must already be normalized ([com.schedulewidget.mobile.data.MusicLinks.normalize]). With [expand], a
     * playlist / album link becomes one track per song.
     */
    suspend fun resolve(link: String, expand: Boolean): Result {
        Spotify.parse(link)?.let { return resolveSpotify(it, expand) }
        val listId = YouTube.playlistId(link)
        if (expand && listId != null) {
            val fetched = runCatching { YouTubeFetch.playlist(listId) }
            fetched.getOrNull()?.let { videos ->
                return Result(
                    videos.map { MusicTrack(it.title, "https://www.youtube.com/watch?v=${it.id}") },
                    "재생목록에서 ${videos.size}곡을 추가했습니다.",
                )
            }
            val reason = fetched.exceptionOrNull()?.message ?: "알 수 없는 오류"
            val title = YouTubeFetch.title(link) ?: "YouTube 재생목록"
            return Result(listOf(MusicTrack(title, link)), "곡별로 가져오지 못해 링크 하나로 추가했습니다. ($reason)")
        }
        val title = YouTubeFetch.title(link) ?: YouTube.videoId(link)?.let { "YouTube · $it" } ?: "YouTube 재생목록"
        return Result(listOf(MusicTrack(title, link)), "‘$title’을(를) 추가했습니다.")
    }

    private suspend fun resolveSpotify(ref: Spotify.Ref, expand: Boolean): Result {
        if (expand && ref.isList) {
            val fetched = runCatching { SpotifyFetch.entity(ref) }
            fetched.getOrNull()?.takeIf { it.tracks.isNotEmpty() }?.let { e ->
                val kind = if (ref.type == "album") "앨범" else "재생목록"
                return Result(
                    e.tracks.map { t -> MusicTrack(t.label.ifBlank { t.uri }, Spotify.parse(t.uri)!!.url) },
                    "Spotify ${kind}에서 ${e.tracks.size}곡을 추가했습니다.",
                )
            }
            val reason = fetched.exceptionOrNull()?.message ?: "곡 목록이 비어 있음"
            val title = SpotifyFetch.title(ref) ?: "Spotify 재생목록"
            return Result(listOf(MusicTrack(title, ref.url)), "곡별로 가져오지 못해 링크 하나로 추가했습니다. ($reason)")
        }
        val title = SpotifyFetch.title(ref) ?: "Spotify · ${ref.id}"
        return Result(listOf(MusicTrack(title, ref.url)), "‘$title’을(를) 추가했습니다.")
    }

    /** Appends [tracks] to [playlistId] (or a new playlist named [newName] when null) and selects it. */
    fun addTo(context: Context, playlistId: String?, tracks: List<MusicTrack>, newName: String = "내 플레이리스트") {
        Repository.get(context).update { d ->
            val m = d.music
            val target = m.playlists.firstOrNull { it.id == playlistId }
            val playlists = if (target != null) {
                m.playlists.map { if (it.id == target.id) it.copy(tracks = it.tracks + tracks) else it }
            } else {
                m.playlists + MusicPlaylist(name = newName, tracks = tracks)
            }
            val selected = target?.id ?: playlists.last().id
            d.copy(music = m.copy(playlists = playlists, selectedPlaylistId = selected))
        }
    }
}
