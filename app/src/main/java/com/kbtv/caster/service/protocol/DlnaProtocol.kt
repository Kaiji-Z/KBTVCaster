package com.kbtv.caster.service.protocol

import android.content.Context
import android.net.Uri
import com.kbtv.caster.dlna.DlnaBootstrap
import com.kbtv.caster.dlna.renderer.DmrPlaybackListener
import timber.log.Timber

/**
 * DLNA/UPnP MediaRenderer protocol.
 *
 * Starts the Cling-based DMR stack ([DlnaBootstrap]) behind the
 * [CastProtocol] surface. Acts as a [DmrPlaybackListener]: UPnP AVTransport
 * commands (Play/Pause/Stop/Seek/SetAVTransportURI) arrive from the phone
 * and are translated into [CastProtocol.PlaybackCommand]s forwarded to the
 * coordinator service's shared ExoPlayer.
 *
 * The playback rendering (HLS detection, Baidu UA, ExoPlayer control) stays
 * in the service — this class only relays commands.
 */
class DlnaProtocol : CastProtocol, DmrPlaybackListener {

    override val name: String = "DLNA"

    /** Set by [start]; also reachable from tests to verify command translation. */
    internal var sink: CastProtocol.PlaybackSink? = null

    override fun start(context: Context, sink: CastProtocol.PlaybackSink) {
        this.sink = sink
        try {
            DlnaBootstrap.setPlaybackListener(this)
            DlnaBootstrap.start(context)
            Timber.d("DLNA protocol started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start DLNA protocol")
        }
    }

    override fun stop() {
        try {
            DlnaBootstrap.stop()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping DLNA protocol")
        }
        sink = null
    }

    // ===== DmrPlaybackListener =====

    override fun onPlay(uri: Uri) {
        Timber.d("DLNA onPlay: $uri")
        sink?.onCommand(CastProtocol.PlaybackCommand.Play(uri))
    }

    override fun onPause() {
        Timber.d("DLNA onPause")
        sink?.onCommand(CastProtocol.PlaybackCommand.Pause)
    }

    override fun onStop() {
        Timber.d("DLNA onStop")
        sink?.onCommand(CastProtocol.PlaybackCommand.Stop)
    }

    override fun onSeek(positionMs: Long) {
        Timber.d("DLNA onSeek: $positionMs")
        sink?.onCommand(CastProtocol.PlaybackCommand.Seek(positionMs))
    }
}
