package com.notedown.audio
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
class AudioPlaybackManager(context: Context) {
    private val player = ExoPlayer.Builder(context).build()
    private var current: String? = null
    fun play(path: String) {
        if (current == path && player.isPlaying) { player.pause(); return }
        if (current != path) { current = path; player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(path)))); player.prepare() }
        else if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }
    fun stop() { player.stop(); current = null }
    fun release() = player.release()
}
