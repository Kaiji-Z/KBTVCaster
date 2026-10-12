package com.kbtv.caster.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the AirPlay request/plist parsing layer — the pure,
 * android-free half of VERIFICATION.md §3.1 Layer 1 for the AirPlay module.
 */
class AirPlayRequestTest {

    @Test
    fun `full request should parse method path headers and body`() {
        val req = AirPlayRequest.parse(
            "POST /play HTTP/1.1\r\nContent-Type: text/x-apple-plist+xml\r\nUser-Agent: AirPlay/170.22\r\n\r\n<plist/>"
        )
        assertEquals("POST", req!!.method)
        assertEquals("/play", req.path)
        assertEquals("text/x-apple-plist+xml", req.headers["content-type"])
        assertEquals("AirPlay/170.22", req.headers["user-agent"])
        assertEquals("<plist/>", req.body)
    }

    @Test
    fun `header lookup should be case-insensitive with trimmed values`() {
        val req = AirPlayRequest.parse("POST /rate HTTP/1.1\r\nX-SeSSION-Id:   abc  \r\n\r\n")
        assertEquals("abc", req!!.headers["x-session-id"])
    }

    @Test
    fun `header value containing colons should survive`() {
        val req = AirPlayRequest.parse("POST /play HTTP/1.1\r\nUser-Agent: My:Agent/1.0\r\n\r\n")
        assertEquals("My:Agent/1.0", req!!.headers["user-agent"])
    }

    @Test
    fun `query string should parse with url decoding`() {
        val req = AirPlayRequest.parse(
            "POST /play?Content-Location=https%3A%2F%2Fexample.com%2Fv.m3u8&Start-Position=1.5 HTTP/1.1\r\n\r\n"
        )
        assertEquals("https://example.com/v.m3u8", req!!.query["Content-Location"])
        assertEquals("1.5", req.query["Start-Position"])
    }

    @Test
    fun `request without body should parse with empty body`() {
        val req = AirPlayRequest.parse("POST /stop HTTP/1.1\r\nX: y\r\n")
        assertEquals("/stop", req!!.path)
        assertEquals("", req.body)
    }

    @Test
    fun `garbage input should parse to null not throw`() {
        assertNull(AirPlayRequest.parse(""))
        assertNull(AirPlayRequest.parse("GARBAGE"))
        assertNull(AirPlayRequest.parse("\r\n\r\n"))
    }

    @Test
    fun `text plist should parse flat dict of strings and numbers`() {
        val plist = parseTextPlist(
            """<?xml version="1.0" encoding="UTF-8"?>
            <plist version="0.9"><dict>
                <key>Content-Location</key><string>http://example.com/v.mp4</string>
                <key>Start-Position</key><real>1.5</real>
            </dict></plist>"""
        )
        assertEquals("http://example.com/v.mp4", plist["Content-Location"])
        assertEquals("1.5", plist["Start-Position"])
    }

    @Test
    fun `text plist booleans should map to true false strings`() {
        val plist = parseTextPlist(
            "<plist><dict><key>a</key><true/><key>b</key><false/></dict></plist>"
        )
        assertEquals("true", plist["a"])
        assertEquals("false", plist["b"])
    }

    @Test
    fun `malformed plist should yield empty map not throw`() {
        assertEquals(emptyMap<String, String>(), parseTextPlist("not xml at all <<"))
        assertEquals(emptyMap<String, String>(), parseTextPlist("<plist><dict>"))
    }
}
