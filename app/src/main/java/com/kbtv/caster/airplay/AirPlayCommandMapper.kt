package com.kbtv.caster.airplay

/**
 * Intent produced by the AirPlay parsing layer. Deliberately free of
 * android.net.Uri so this layer runs in plain JVM tests; the service-side
 * adapter (B1/B4 wiring) converts these to CastProtocol.PlaybackCommand.
 */
internal sealed class AirPlayIntent {
    data class PlayUrl(val url: String, val startPositionSec: Double = 0.0) : AirPlayIntent()
    data object Pause : AirPlayIntent()
    data object Resume : AirPlayIntent()
    data object Stop : AirPlayIntent()
    data class SeekSec(val positionSec: Double) : AirPlayIntent()
}

/**
 * Maps a parsed AirPlay request to the playback intent it represents,
 * mirroring the video-push surface real senders exercise:
 * /play (URL push), /rate (pause/resume), /stop, /scrub (seek).
 * Unknown or incomplete requests map to null — never throw.
 */
internal object AirPlayCommandMapper {

    fun toIntent(req: AirPlayRequest): AirPlayIntent? = when (req.path) {
        "/play" -> playIntent(req)
        "/rate" -> req.query["value"]?.toDoubleOrNull()?.let {
            if (it <= 0.0) AirPlayIntent.Pause else AirPlayIntent.Resume
        }
        "/stop" -> AirPlayIntent.Stop
        "/scrub" -> req.query["position"]?.toDoubleOrNull()?.let { AirPlayIntent.SeekSec(it) }
        else -> null
    }

    private fun playIntent(req: AirPlayRequest): AirPlayIntent? {
        val plist = parseTextPlist(req.body)
        val url = plist["Content-Location"]
            ?: req.query["Content-Location"]
            ?: req.query["url"]
            ?: return null
        val start = plist["Start-Position"]?.toDoubleOrNull() ?: 0.0
        return AirPlayIntent.PlayUrl(url, start)
    }
}
