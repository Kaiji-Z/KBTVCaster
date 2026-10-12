package com.kbtv.caster.service.protocol

import com.kbtv.caster.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flag plumbing and routing equivalence.
 *
 * The regression rule (VERIFICATION.md §4): the same suite must pass with the
 * flag on and off, and flag=off must equal pre-change behavior. The legacy
 * handlePlay branch decided routing as `isHlsStream(uri)` plus a
 * case-sensitive `uri.contains("pan.baidu.com")`; [PlaybackPlan.forUri] must
 * agree on every input class for the refactor to be behavior-preserving.
 */
class FeatureFlagsTest {

    @Test
    fun `feature flag value should mirror the generated BuildConfig field`() {
        assertEquals(BuildConfig.FLAG_PLAYBACK_PLAN_ROUTING, FeatureFlags.playbackPlanRouting)
    }

    /**
     * The legacy handlePlay branch as control flow: the Baidu check lived
     * INSIDE the HLS arm, so a non-HLS URI never consulted it — non-HLS
     * routing is progressive with no UA regardless of host. (Round 2 note:
     * an eager `isHlsStream(uri) to uri.contains(...)` oracle wrongly flagged
     * pan.baidu.com .mp4 as (false, true); corrected to mirror the branch.)
     */
    private fun legacyRouting(uri: String): Pair<Boolean, Boolean> =
        if (isHlsStream(uri)) true to uri.contains("pan.baidu.com") else false to false

    private fun planRouting(uri: String): Pair<Boolean, Boolean> {
        val plan = PlaybackPlan.forUri(uri)
        return plan.isHls to plan.usesBaiduUa
    }

    @Test
    fun `plan routing should equal legacy routing across the URI matrix`() {
        val uris = listOf(
            "http://example.com/stream.m3u8",
            "https://cdn.example.com/hls/v1/index.m3u8",
            "http://example.com/STREAM.M3U8",
            "http://example.com/m3u8_auto/play",
            "http://example.com/play?type=m3u8&token=abc",
            "https://pan.baidu.com/api/stream.m3u8",
            "http://pan.baidu.com/x.m3u8",
            "http://example.com/video.mp4",
            "https://pan.baidu.com/file/video.mp4",
            "http://example.com/movie.mkv",
            "http://example.com/my3u8file",
            "http://example.com/stream",
            "https://PAN.BAIDU.COM/stream.m3u8",
        )
        uris.forEach { uri ->
            assertEquals("routing mismatch for $uri", legacyRouting(uri), planRouting(uri))
        }
    }

    @Test
    fun `equivalence should hold regardless of flag state`() {
        // The decision functions are pure; the flag only picks which path the
        // service executes. Both paths must stay interchangeable on this class of input.
        val uri = "https://pan.baidu.com/api/stream.m3u8"
        assertEquals(legacyRouting(uri), planRouting(uri))
        assertTrue(PlaybackPlan.forUri(uri).usesBaiduUa)
    }

    @Test
    fun `baidu UA plan should carry 30s timeouts`() {
        val plan = PlaybackPlan.forUri("https://pan.baidu.com/api/stream.m3u8")
        assertEquals(30_000, plan.connectTimeoutMs)
        assertEquals(30_000, plan.readTimeoutMs)
    }
}
