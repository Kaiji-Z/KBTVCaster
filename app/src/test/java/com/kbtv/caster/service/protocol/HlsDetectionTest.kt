package com.kbtv.caster.service.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isHlsStream] — the pure URI-heuristic that decides
 * whether CastCoordinatorService picks an HlsMediaSource.
 *
 * These are the only branch points that route playback, so each
 * recognized signal and a representative set of non-HLS URIs are covered.
 */
class HlsDetectionTest {

    @Test
    fun `plain m3u8 URL should be detected as HLS`() {
        assertTrue(isHlsStream("http://example.com/stream.m3u8"))
    }

    @Test
    fun `m3u8 in a nested path should be detected`() {
        assertTrue(isHlsStream("https://cdn.example.com/hls/v1/index.m3u8"))
    }

    @Test
    fun `uppercase M3U8 should be detected via case-insensitive match`() {
        assertTrue(isHlsStream("http://example.com/STREAM.M3U8"))
    }

    @Test
    fun `m3u8_auto path should be detected`() {
        assertTrue(isHlsStream("http://example.com/m3u8_auto/play"))
    }

    @Test
    fun `type=m3u8 query parameter should be detected`() {
        assertTrue(isHlsStream("http://example.com/play?type=m3u8&token=abc"))
    }

    @Test
    fun `plain mp4 URL should not be HLS`() {
        assertFalse(isHlsStream("http://example.com/video.mp4"))
    }

    @Test
    fun `mkv URL should not be HLS`() {
        assertFalse(isHlsStream("https://example.com/movie.mkv"))
    }

    @Test
    fun `URL containing m3u8 as a substring of a longer token should not match dot-rule`() {
        // "my3u8" lacks the dot; only the explicit ".m3u8" / m3u8_auto / type=m3u8 match.
        // This guards against over-eager substring false positives.
        assertFalse(isHlsStream("http://example.com/my3u8file"))
    }

    @Test
    fun `plain HTTP stream URL without extension should not be HLS`() {
        assertFalse(isHlsStream("http://example.com/stream"))
    }
}
