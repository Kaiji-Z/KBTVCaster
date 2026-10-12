package com.kbtv.caster.service.protocol

/**
 * Pure playback-routing decision for a received media URI.
 *
 * Extracted from CastCoordinatorService.handlePlay so the routing rules
 * (HLS vs progressive, Baidu-Netdisk User-Agent) are unit-testable without
 * an Android Service instance. The service executes the plan; it must not
 * re-derive routing on its own.
 *
 * Keep in sync with [isHlsStream]: a URI routed as HLS here must be one
 * ExoPlayer would play via HlsMediaSource. The Baidu host match is
 * case-sensitive on the original URI — same quirk as the legacy branch.
 */
internal data class PlaybackPlan(
    val uri: String,
    val sourceType: SourceType,
    val userAgent: String? = null,
    val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    enum class SourceType { PROGRESSIVE, HLS }

    val isHls: Boolean get() = sourceType == SourceType.HLS

    /** True only when the Baidu-Netdisk User-Agent override applies. */
    val usesBaiduUa: Boolean get() = userAgent == BAIDU_NETDISK_UA

    companion object {
        const val BAIDU_NETDISK_UA = "pan.baidu.com"
        const val BAIDU_NETDISK_HOST = "pan.baidu.com"
        const val DEFAULT_TIMEOUT_MS = 30_000

        fun forUri(uri: String): PlaybackPlan = when {
            isHlsStream(uri) && uri.contains(BAIDU_NETDISK_HOST) ->
                PlaybackPlan(uri, SourceType.HLS, userAgent = BAIDU_NETDISK_UA)
            isHlsStream(uri) ->
                PlaybackPlan(uri, SourceType.HLS)
            else ->
                PlaybackPlan(uri, SourceType.PROGRESSIVE)
        }
    }
}
