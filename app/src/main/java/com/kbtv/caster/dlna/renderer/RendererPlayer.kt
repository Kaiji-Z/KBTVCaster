package com.kbtv.caster.dlna.renderer

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import org.fourthline.cling.model.ModelUtil
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable
import org.fourthline.cling.support.lastchange.LastChange
import org.fourthline.cling.support.model.Channel
import org.fourthline.cling.support.model.MediaInfo
import org.fourthline.cling.support.model.PositionInfo
import org.fourthline.cling.support.model.TransportAction
import org.fourthline.cling.support.model.TransportInfo
import org.fourthline.cling.support.model.TransportState
import org.fourthline.cling.support.renderingcontrol.lastchange.ChannelMute
import org.fourthline.cling.support.renderingcontrol.lastchange.ChannelVolume
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlVariable
import timber.log.Timber
import java.net.URI

/**
 * One AVTransport instance. Holds the UPnP-visible transport state machine
 * and forwards transport commands to [DmrPlaybackListener]; volume changes
 * are applied to the system [AudioManager] music stream. Position and
 * duration stay at their setURI values — no playback feedback loop exists.
 */
class RendererPlayer(
    val instanceId: UnsignedIntegerFourBytes,
    private val context: Context,
    private val avTransportLastChange: LastChange,
    private val renderingControlLastChange: LastChange,
) {
    @Volatile
    var playbackListener: DmrPlaybackListener? = null

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var storedVolume = 0.0
    private var currentUri = ""

    var transportInfo = TransportInfo()
        private set
    var positionInfo = PositionInfo()
        private set
    var mediaInfo = MediaInfo()
        private set

    @Synchronized
    fun setUri(uri: URI, metadata: String?) {
        Timber.i("setUri $uri")
        currentUri = uri.toString()
        mediaInfo = MediaInfo(uri.toString(), metadata ?: "")
        positionInfo = PositionInfo(1, "", uri.toString())

        avTransportLastChange.setEventedValue(
            instanceId,
            AVTransportVariable.AVTransportURI(uri),
            AVTransportVariable.CurrentTrackURI(uri),
        )
        transportStateChanged(TransportState.STOPPED)

        // Control points send SetAVTransportURI then Play, but apps that only
        // send SetAVTransportURI expect playback to start immediately.
        playbackListener?.onPlay(Uri.parse(currentUri))
    }

    @Synchronized
    fun play() {
        Timber.i("play")
        transportStateChanged(TransportState.PLAYING)
        if (currentUri.isNotEmpty()) {
            playbackListener?.onPlay(Uri.parse(currentUri))
        }
    }

    @Synchronized
    fun pause() {
        Timber.i("pause")
        transportStateChanged(TransportState.PAUSED_PLAYBACK)
        playbackListener?.onPause()
    }

    @Synchronized
    fun stop() {
        Timber.i("stop")
        transportStateChanged(TransportState.STOPPED)
        playbackListener?.onStop()
    }

    @Synchronized
    fun seek(positionMs: Long) {
        Timber.i("seek $positionMs")
        val relTime = ModelUtil.toTimeString(positionMs / 1000)
        positionInfo = PositionInfo(
            1,
            mediaInfo.mediaDuration,
            currentUri,
            relTime,
            relTime,
        )
        playbackListener?.onSeek(positionMs)
    }

    /** Desired volume in 0.0..1.0 of the music stream range. */
    @Synchronized
    fun setVolume(volume: Double) {
        Timber.i("setVolume $volume")
        val previous = volumeOfMusicStream
        storedVolume = previous

        try {
            val manager = audioManager ?: return
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * max).toInt(), 0)
        } catch (e: Exception) {
            Timber.w(e, "Failed to set system volume")
        }

        val muteCrossed = (previous == 0.0 && volume > 0) || (previous > 0 && volume == 0.0)
        renderingControlLastChange.setEventedValue(
            instanceId,
            RenderingControlVariable.Volume(ChannelVolume(Channel.Master, (volume * 100).toInt())),
            if (muteCrossed) {
                RenderingControlVariable.Mute(ChannelMute(Channel.Master, volume == 0.0))
            } else {
                null
            },
        )
    }

    @Synchronized
    fun setMute(desiredMute: Boolean) {
        when {
            desiredMute && volumeOfMusicStream > 0 -> setVolume(0.0)
            !desiredMute && volumeOfMusicStream == 0.0 -> {
                Timber.d("Unmuting, restoring volume %s", storedVolume)
                setVolume(storedVolume)
            }
        }
    }

    val volumeOfMusicStream: Double
        get() {
            val manager = audioManager ?: return 1.0
            return try {
                manager.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() /
                    manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            } catch (e: Exception) {
                Timber.w(e, "Failed to read system volume")
                1.0
            }
        }

    fun currentTransportActions(): Array<TransportAction> = when (transportInfo.currentTransportState) {
        TransportState.STOPPED, TransportState.NO_MEDIA_PRESENT ->
            arrayOf(TransportAction.Play)
        TransportState.PLAYING ->
            arrayOf(TransportAction.Stop, TransportAction.Pause, TransportAction.Seek)
        TransportState.PAUSED_PLAYBACK ->
            arrayOf(TransportAction.Stop, TransportAction.Pause, TransportAction.Seek, TransportAction.Play)
        TransportState.TRANSITIONING ->
            arrayOf(TransportAction.Stop)
        else -> arrayOf()
    }

    @Synchronized
    private fun transportStateChanged(newState: TransportState) {
        transportInfo = TransportInfo(newState)
        avTransportLastChange.setEventedValue(
            instanceId,
            AVTransportVariable.TransportState(newState),
            AVTransportVariable.CurrentTransportActions(currentTransportActions()),
        )
    }
}
