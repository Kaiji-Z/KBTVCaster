package com.kbtv.caster.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AirPlayCommandMapper] — request → intent mapping for the
 * AirPlay video-push surface (/play /rate /stop /scrub).
 */
class AirPlayCommandMapperTest {

    private fun req(raw: String): AirPlayRequest = AirPlayRequest.parse(raw)!!

    @Test
    fun `play with plist body should map to PlayUrl with start position`() {
        val intent = AirPlayCommandMapper.toIntent(
            req("POST /play HTTP/1.1\r\nContent-Type: text/x-apple-plist+xml\r\n\r\n" +
                "<plist><dict><key>Content-Location</key><string>http://a/v.m3u8</string>" +
                "<key>Start-Position</key><real>1.5</real></dict></plist>")
        )
        assertEquals(AirPlayIntent.PlayUrl("http://a/v.m3u8", 1.5), intent)
    }

    @Test
    fun `play with query Content-Location should map to PlayUrl`() {
        val intent = AirPlayCommandMapper.toIntent(
            req("POST /play?Content-Location=http%3A%2F%2Fa%2Fv.mp4 HTTP/1.1\r\n\r\n")
        )
        assertEquals(AirPlayIntent.PlayUrl("http://a/v.mp4", 0.0), intent)
    }

    @Test
    fun `play with no url anywhere should map to null`() {
        assertNull(AirPlayCommandMapper.toIntent(req("POST /play HTTP/1.1\r\n\r\n")))
    }

    @Test
    fun `play with malformed plist body should map to null not throw`() {
        assertNull(AirPlayCommandMapper.toIntent(
            req("POST /play HTTP/1.1\r\n\r\n<<<not-xml>>>")
        ))
    }

    @Test
    fun `rate zero should map to Pause`() {
        assertEquals(
            AirPlayIntent.Pause,
            AirPlayCommandMapper.toIntent(req("POST /rate?value=0.000000 HTTP/1.1\r\n\r\n"))
        )
    }

    @Test
    fun `rate one should map to Resume`() {
        assertEquals(
            AirPlayIntent.Resume,
            AirPlayCommandMapper.toIntent(req("POST /rate?value=1.000000 HTTP/1.1\r\n\r\n"))
        )
    }

    @Test
    fun `rate with garbage value should map to null`() {
        assertNull(AirPlayCommandMapper.toIntent(req("POST /rate?value=fast HTTP/1.1\r\n\r\n")))
    }

    @Test
    fun `stop should map to Stop`() {
        assertEquals(
            AirPlayIntent.Stop,
            AirPlayCommandMapper.toIntent(req("POST /stop HTTP/1.1\r\n\r\n"))
        )
    }

    @Test
    fun `scrub with position should map to SeekSec`() {
        assertEquals(
            AirPlayIntent.SeekSec(10.5),
            AirPlayCommandMapper.toIntent(req("POST /scrub?position=10.500000 HTTP/1.1\r\n\r\n"))
        )
    }

    @Test
    fun `scrub without position should map to null`() {
        assertNull(AirPlayCommandMapper.toIntent(req("POST /scrub HTTP/1.1\r\n\r\n")))
    }

    @Test
    fun `unknown path should map to null`() {
        assertNull(AirPlayCommandMapper.toIntent(req("POST /server-info HTTP/1.1\r\n\r\n")))
        assertNull(AirPlayCommandMapper.toIntent(req("GET /slideshow-features HTTP/1.1\r\n\r\n")))
    }

    @Test
    fun `mapped intents should be the sealed AirPlayIntent family`() {
        val intent = AirPlayCommandMapper.toIntent(req("POST /stop HTTP/1.1\r\n\r\n"))
        assertTrue(intent is AirPlayIntent.Stop)
    }
}
