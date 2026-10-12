package com.kbtv.caster.dlna.renderer

import org.fourthline.cling.model.ModelUtil
import org.fourthline.cling.model.types.ErrorCode
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes
import org.fourthline.cling.support.avtransport.AbstractAVTransportService
import org.fourthline.cling.support.avtransport.AVTransportErrorCode
import org.fourthline.cling.support.avtransport.AVTransportException
import org.fourthline.cling.support.lastchange.LastChange
import org.fourthline.cling.support.model.DeviceCapabilities
import org.fourthline.cling.support.model.MediaInfo
import org.fourthline.cling.support.model.PlayMode
import org.fourthline.cling.support.model.PositionInfo
import org.fourthline.cling.support.model.SeekMode
import org.fourthline.cling.support.model.StorageMedium
import org.fourthline.cling.support.model.TransportAction
import org.fourthline.cling.support.model.TransportInfo
import org.fourthline.cling.support.model.TransportSettings
import timber.log.Timber
import java.net.URI

/**
 * AVTransport:1 service. Each action resolves its instance via [players] and
 * delegates to that [RendererPlayer]. URI scheme is validated against what
 * the player can consume (http/https/file); seek supports REL_TIME only
 * ("H:MM:SS" per the AVTransport spec).
 */
class RendererTransport(
    lastChange: LastChange,
    private val players: Map<UnsignedIntegerFourBytes, RendererPlayer>,
) : AbstractAVTransportService(lastChange) {

    private fun player(instanceId: UnsignedIntegerFourBytes): RendererPlayer =
        players[instanceId]
            ?: throw AVTransportException(AVTransportErrorCode.INVALID_INSTANCE_ID)

    override fun setAVTransportURI(
        instanceId: UnsignedIntegerFourBytes,
        currentURI: String?,
        currentURIMetaData: String?,
    ) {
        if (currentURI.isNullOrEmpty()) {
            throw AVTransportException(ErrorCode.INVALID_ARGS, "CurrentURI can not be null or malformed")
        }
        val uri = try {
            URI(currentURI)
        } catch (e: Exception) {
            throw AVTransportException(ErrorCode.INVALID_ARGS, "CurrentURI can not be null or malformed")
        }
        when (uri.scheme?.lowercase()) {
            "http", "https", "file" -> Unit
            else -> throw AVTransportException(
                ErrorCode.INVALID_ARGS,
                "Only HTTP, HTTPS and file: resource identifiers are supported",
            )
        }
        player(instanceId).setUri(uri, currentURIMetaData)
    }

    override fun getMediaInfo(instanceId: UnsignedIntegerFourBytes): MediaInfo =
        player(instanceId).mediaInfo

    override fun getTransportInfo(instanceId: UnsignedIntegerFourBytes): TransportInfo =
        player(instanceId).transportInfo

    override fun getPositionInfo(instanceId: UnsignedIntegerFourBytes): PositionInfo =
        player(instanceId).positionInfo

    override fun getDeviceCapabilities(instanceId: UnsignedIntegerFourBytes): DeviceCapabilities {
        player(instanceId)
        return DeviceCapabilities(arrayOf(StorageMedium.NETWORK))
    }

    override fun getTransportSettings(instanceId: UnsignedIntegerFourBytes): TransportSettings {
        player(instanceId)
        return TransportSettings(PlayMode.NORMAL)
    }

    override fun stop(instanceId: UnsignedIntegerFourBytes) {
        player(instanceId).stop()
    }

    override fun play(instanceId: UnsignedIntegerFourBytes, speed: String?) {
        player(instanceId).play()
    }

    override fun pause(instanceId: UnsignedIntegerFourBytes) {
        player(instanceId).pause()
    }

    override fun record(instanceId: UnsignedIntegerFourBytes) {
        Timber.d("Record not implemented")
    }

    override fun seek(instanceId: UnsignedIntegerFourBytes, unit: String?, target: String?) {
        val mode = try {
            SeekMode.valueOrExceptionOf(unit)
        } catch (e: IllegalArgumentException) {
            throw AVTransportException(AVTransportErrorCode.SEEKMODE_NOT_SUPPORTED, "Unsupported seek mode: $unit")
        }
        if (mode != SeekMode.REL_TIME) {
            throw AVTransportException(AVTransportErrorCode.SEEKMODE_NOT_SUPPORTED, "Unsupported seek mode: $unit")
        }
        val seconds = try {
            ModelUtil.fromTimeString(target)
        } catch (e: Exception) {
            throw AVTransportException(ErrorCode.INVALID_ARGS, "Malformed REL_TIME target: $target")
        }
        Timber.i("seek $unit target=$target pos=${seconds * 1000}ms")
        player(instanceId).seek(seconds * 1000L)
    }

    override fun next(instanceId: UnsignedIntegerFourBytes) {
        Timber.d("Next not implemented")
    }

    override fun previous(instanceId: UnsignedIntegerFourBytes) {
        Timber.d("Previous not implemented")
    }

    override fun setNextAVTransportURI(
        instanceId: UnsignedIntegerFourBytes,
        nextURI: String?,
        nextURIMetaData: String?,
    ) {
        Timber.d("SetNextAVTransportURI not implemented")
    }

    override fun setPlayMode(instanceId: UnsignedIntegerFourBytes, newPlayMode: String?) {
        Timber.d("SetPlayMode not implemented")
    }

    override fun setRecordQualityMode(instanceId: UnsignedIntegerFourBytes, newRecordQualityMode: String?) {
        Timber.d("SetRecordQualityMode not implemented")
    }

    override fun getCurrentTransportActions(instanceId: UnsignedIntegerFourBytes): Array<TransportAction> =
        player(instanceId).currentTransportActions()

    override fun getCurrentInstanceIds(): Array<UnsignedIntegerFourBytes> =
        players.keys.toTypedArray()
}
