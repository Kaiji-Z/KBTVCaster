package com.kbtv.caster.dlna.renderer

import android.net.Uri

/**
 * Transport events surfaced by the DMR to the protocol layer. The coordinator
 * service's shared ExoPlayer executes them; the renderer itself never
 * touches playback directly.
 */
interface DmrPlaybackListener {
    fun onPlay(uri: Uri)
    fun onPause()
    fun onStop()
    fun onSeek(positionMs: Long)
}
