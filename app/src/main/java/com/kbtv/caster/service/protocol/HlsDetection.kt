package com.kbtv.caster.service.protocol

/**
 * Pure function: decide whether a media URI points at an HLS stream.
 *
 * Extracted from CastCoordinatorService so the detection rule is unit-
 * testable without an Android Service instance. Keep this in sync with
 * how ExoPlayer media-source selection is done in
 * [com.kbtv.caster.service.CastCoordinatorService.handlePlay].
 *
 * Heuristics (case-insensitive, substring):
 *  - `.m3u8` extension
 *  - `m3u8_auto` path segment (used by some Chinese CDNs)
 *  - `type=m3u8` query parameter
 */
internal fun isHlsStream(uri: String): Boolean {
    val lower = uri.lowercase()
    return lower.contains(".m3u8") ||
        lower.contains("m3u8_auto") ||
        lower.contains("type=m3u8")
}
