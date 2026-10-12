package com.kbtv.caster.service.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlaybackPlan.forUri] — the playback routing decision.
 *
 * Mirrors the acceptance criteria in VERIFICATION.md §8.5: HLS signals route
 * to HLS; Baidu-Netdisk HLS gets the UA override with 30s timeouts; anything
 * else stays progressive with no UA (reverse criteria: non-HLS never routes
 * to HlsMediaSource, non-Baidu never gets the UA).
 */
class PlaybackPlanTest {

    @Test
    fun `plain m3u8 URL should route to HLS without UA`() {
        val plan = PlaybackPlan.forUri("http://example.com/stream.m3u8")
        assertTrue(plan.isHls)
        assertEquals(PlaybackPlan.SourceType.HLS, plan.sourceType)
        assertNull(plan.userAgent)
        assertFalse(plan.usesBaiduUa)
    }

    @Test
    fun `m3u8 in a nested path should route to HLS`() {
        val plan = PlaybackPlan.forUri("https://cdn.example.com/hls/v1/index.m3u8")
        assertTrue(plan.isHls)
    }

    @Test
    fun `uppercase M3U8 should route to HLS via case-insensitive match`() {
        val plan = PlaybackPlan.forUri("http://example.com/STREAM.M3U8")
        assertTrue(plan.isHls)
    }

    @Test
    fun `m3u8_auto path should route to HLS`() {
        val plan = PlaybackPlan.forUri("http://example.com/m3u8_auto/play")
        assertTrue(plan.isHls)
    }

    @Test
    fun `type=m3u8 query parameter should route to HLS`() {
        val plan = PlaybackPlan.forUri("http://example.com/play?type=m3u8&token=abc")
        assertTrue(plan.isHls)
    }

    @Test
    fun `baidu netdisk HLS should get UA override and 30s timeouts`() {
        val plan = PlaybackPlan.forUri("https://pan.baidu.com/api/stream.m3u8")
        assertTrue(plan.isHls)
        assertTrue(plan.usesBaiduUa)
        assertEquals(PlaybackPlan.BAIDU_NETDISK_UA, plan.userAgent)
        assertEquals(30_000, plan.connectTimeoutMs)
        assertEquals(30_000, plan.readTimeoutMs)
    }

    @Test
    fun `plain mp4 URL should stay progressive`() {
        val plan = PlaybackPlan.forUri("http://example.com/video.mp4")
        assertEquals(PlaybackPlan.SourceType.PROGRESSIVE, plan.sourceType)
        assertNull(plan.userAgent)
    }

    @Test
    fun `mkv URL should stay progressive`() {
        val plan = PlaybackPlan.forUri("https://example.com/movie.mkv")
        assertEquals(PlaybackPlan.SourceType.PROGRESSIVE, plan.sourceType)
    }

    @Test
    fun `baidu netdisk non-HLS URL must not get the UA (legacy quirk, UA only on HLS path)`() {
        val plan = PlaybackPlan.forUri("https://pan.baidu.com/file/video.mp4")
        assertEquals(PlaybackPlan.SourceType.PROGRESSIVE, plan.sourceType)
        assertFalse(plan.usesBaiduUa)
    }

    @Test
    fun `substring false positive my3u8file must stay progressive`() {
        val plan = PlaybackPlan.forUri("http://example.com/my3u8file")
        assertEquals(PlaybackPlan.SourceType.PROGRESSIVE, plan.sourceType)
    }

    @Test
    fun `extension-less stream URL should stay progressive`() {
        val plan = PlaybackPlan.forUri("http://example.com/stream")
        assertEquals(PlaybackPlan.SourceType.PROGRESSIVE, plan.sourceType)
    }

    @Test
    fun `uppercase baidu host should miss the case-sensitive UA match like legacy`() {
        val plan = PlaybackPlan.forUri("https://PAN.BAIDU.COM/stream.m3u8")
        assertTrue(plan.isHls)
        assertFalse(plan.usesBaiduUa)
    }

    @Test
    fun `plan should carry the original uri unchanged`() {
        val uri = "http://example.com/stream.m3u8?token=x"
        assertEquals(uri, PlaybackPlan.forUri(uri).uri)
    }
}
