package com.kbtv.caster.dlna.renderer

import org.fourthline.cling.support.connectionmanager.ConnectionManagerService
import org.fourthline.cling.support.model.ProtocolInfo
import org.seamless.util.MimeType

/**
 * ConnectionManager:1 service declaring the sink (playable) protocol list.
 * ExoPlayer handles all of these container types.
 */
class RendererConnectionManager : ConnectionManagerService() {

    init {
        SINK_MIME_TYPES.forEach { mime ->
            sinkProtocolInfo.add(ProtocolInfo(MimeType.valueOf(mime)))
        }
    }

    private companion object {
        val SINK_MIME_TYPES = listOf(
            // Images
            "image/jpeg", "image/png", "image/gif", "image/bmp",
            "image/pjpeg", "image/tiff", "image/x-ms-bmp",
            // Video
            "video/3gpp", "video/mp4", "video/3gp2", "video/avi",
            "video/flv", "video/mpeg", "video/x-mkv", "video/x-matroska",
            "video/msvideo", "video/quicktime", "video/x-msvideo", "video/x-ms-wmv",
            // Audio
            "audio/aac", "audio/3gpp", "audio/amr", "audio/ogg",
            "audio/mpeg", "audio/midi", "audio/x-midi", "audio/x-mid",
            "audio/x-wav", "audio/x-ms-wma",
        )
    }
}
