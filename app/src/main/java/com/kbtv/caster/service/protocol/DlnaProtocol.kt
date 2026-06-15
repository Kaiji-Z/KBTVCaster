package com.kbtv.caster.service.protocol

import android.content.Context
import android.net.Uri
import com.kbtv.caster.dlna.DLNAUtils
import com.zxt.dlna.dmr.ZxtMediaPlayer
import timber.log.Timber

/**
 * DLNA/UPnP MediaRenderer protocol.
 *
 * Wraps the existing TVRemoteIME-based DLNA stack (DLNAUtils + Cling) behind
 * the [CastProtocol] surface. Acts as the [ZxtMediaPlayer.PlaybackListener]:
 * UPnP AVTransport commands (Play/Pause/Stop/Seek/SetAVTransportURI) arrive
 * from the phone and are translated into [CastProtocol.PlaybackCommand]s
 * forwarded to the coordinator service's shared ExoPlayer.
 *
 * The playback rendering (HLS detection, Baidu UA, ExoPlayer control) stays
 * in the service — this class only relays commands.
 */
class DlnaProtocol : CastProtocol, ZxtMediaPlayer.PlaybackListener {

    override val name: String = "DLNA"

    /** Set by [start]; also reachable from tests to verify command translation. */
    internal var sink: CastProtocol.PlaybackSink? = null

    override fun start(context: Context, sink: CastProtocol.PlaybackSink) {
        this.sink = sink
        try {
            DLNAUtils.setPlaybackListener(this)
            DLNAUtils.startDLNAService(context)
            Timber.d("DLNA protocol started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start DLNA protocol")
        }
    }

    override fun stop() {
        try {
            DLNAUtils.stopDLNAService()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping DLNA protocol")
        }
        sink = null
    }

    // ===== ZxtMediaPlayer.PlaybackListener =====

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
