package com.notedown.audio
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
class AudioPlaybackManager(context: Context) {
    private val player = ExoPlayer.Builder(context).build()
    var current: String? by mutableStateOf(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    val position: Long get() = player.currentPosition.coerceAtLeast(0)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(error: PlaybackException) { stop() }
        })
    }
    fun play(path: String) {
        if (current == path && player.isPlaying) { player.pause(); return }
        if (current != path) { current = path; player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(path)))); player.prepare() }
        else if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }
    fun seekTo(positionMs: Long) { player.seekTo(positionMs) }
    fun stop() { player.stop(); current = null }
    fun release() = player.release()
}
