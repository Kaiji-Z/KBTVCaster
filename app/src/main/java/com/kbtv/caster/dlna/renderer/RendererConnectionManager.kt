package com.kbtv.caster.dlna.renderer

import org.fourthline.cling.support.connectionmanager.ConnectionManagerService
import org.fourthline.cling.support.model.ProtocolInfo
import timber.log.Timber

/**
 * ConnectionManager:1 service declaring the sink (playable) protocol list.
 *
 * Real control points (Baidu Netdisk via the [vendor] SDK — measured 2026-10-13,
 * see docs/research/baidu-layer-2026-10.md) match candidates against
 * SinkProtocolInfo *including the DLNA.ORG_PN profile field*; a bare-MIME
 * list reads as "cannot play" and the push never arrives. The universal
 * wildcard entry covers sloppy matchers, and ExoPlayer handles every format
 * listed here.
 */
class RendererConnectionManager : ConnectionManagerService() {

    init {
        SINK_PROTOCOL_INFO.forEach { info ->
            try {
                sinkProtocolInfo.add(ProtocolInfo(info))
            } catch (e: Exception) {
                Timber.w(e, "Unparseable sink protocol info: $info")
            }
        }
        Timber.i("SinkProtocolInfo entries: ${sinkProtocolInfo.size}")
    }

    private companion object {
        val SINK_PROTOCOL_INFO = listOf(
            // Universal wildcard — matches any http-get resource including
            // quirky PN values (Baidu pushes video/mp4 with PN=MP3).
            "http-get:*:*:*",
            // Video with DLNA profiles
            "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_EU;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01500000000000000000000000000000",
            "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_HP_HD;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01500000000000000000000000000000",
            "http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_SD_EU;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01500000000000000000000000000000",
            "http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_HD_EU;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01500000000000000000000000000000",
            // Video by MIME
            "http-get:*:video/mp4:*",
            "http-get:*:video/mpeg:*",
            "http-get:*:video/x-matroska:*",
            "http-get:*:video/x-mkv:*",
            "http-get:*:video/avi:*",
            "http-get:*:video/x-msvideo:*",
            "http-get:*:video/x-ms-wmv:*",
            "http-get:*:video/3gpp:*",
            "http-get:*:video/quicktime:*",
            "http-get:*:video/flv:*",
            // HLS
            "http-get:*:application/vnd.apple.mpegurl:*",
            "http-get:*:application/x-mpegurl:*",
            // Audio
            "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01500000000000000000000000000000",
            "http-get:*:audio/mpeg:*",
            "http-get:*:audio/aac:*",
            "http-get:*:audio/x-wav:*",
            "http-get:*:audio/x-ms-wma:*",
            "http-get:*:audio/ogg:*",
            // Images
            "http-get:*:image/jpeg:*",
            "http-get:*:image/png:*",
        )
    }
}
