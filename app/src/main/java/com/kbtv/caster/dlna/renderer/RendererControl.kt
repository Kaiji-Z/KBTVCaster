package com.kbtv.caster.dlna.renderer

import org.fourthline.cling.model.types.ErrorCode
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes
import org.fourthline.cling.model.types.UnsignedIntegerTwoBytes
import org.fourthline.cling.support.lastchange.LastChange
import org.fourthline.cling.support.model.Channel
import org.fourthline.cling.support.renderingcontrol.AbstractAudioRenderingControl
import org.fourthline.cling.support.renderingcontrol.RenderingControlErrorCode
import org.fourthline.cling.support.renderingcontrol.RenderingControlException
import timber.log.Timber

/**
 * RenderingControl:1 service exposing the Master channel. Volume is a 0-100
 * UPnP integer mapped onto the system music stream's 0.0-1.0 range; mute is
 * volume zero with restore.
 */
class RendererControl(
    lastChange: LastChange,
    private val players: Map<UnsignedIntegerFourBytes, RendererPlayer>,
) : AbstractAudioRenderingControl(lastChange) {

    private fun player(instanceId: UnsignedIntegerFourBytes): RendererPlayer =
        players[instanceId]
            ?: throw RenderingControlException(RenderingControlErrorCode.INVALID_INSTANCE_ID)

    private fun checkChannel(channelName: String?) {
        if (getChannel(channelName) != Channel.Master) {
            throw RenderingControlException(ErrorCode.ARGUMENT_VALUE_INVALID, "Unsupported audio channel: $channelName")
        }
    }

    override fun getMute(instanceId: UnsignedIntegerFourBytes, channelName: String?): Boolean {
        checkChannel(channelName)
        return player(instanceId).volumeOfMusicStream == 0.0
    }

    override fun setMute(instanceId: UnsignedIntegerFourBytes, channelName: String?, desiredMute: Boolean) {
        checkChannel(channelName)
        Timber.d("setMute $desiredMute")
        player(instanceId).setMute(desiredMute)
    }

    override fun getVolume(instanceId: UnsignedIntegerFourBytes, channelName: String?): UnsignedIntegerTwoBytes {
        checkChannel(channelName)
        val volume = (player(instanceId).volumeOfMusicStream * 100).toInt()
        Timber.d("getVolume $volume")
        return UnsignedIntegerTwoBytes(volume.toLong())
    }

    override fun setVolume(
        instanceId: UnsignedIntegerFourBytes,
        channelName: String?,
        desiredVolume: UnsignedIntegerTwoBytes,
    ) {
        checkChannel(channelName)
        val volume = desiredVolume.value / 100.0
        Timber.d("setVolume $volume")
        player(instanceId).setVolume(volume)
    }

    override fun getCurrentChannels(): Array<Channel> = arrayOf(Channel.Master)

    override fun getCurrentInstanceIds(): Array<UnsignedIntegerFourBytes> =
        players.keys.toTypedArray()
}
