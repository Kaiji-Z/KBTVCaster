package com.kbtv.caster.airplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JVM round-trip tests for [AirPlayServer]: a real client socket talks to
 * the real server socket on 127.0.0.1 — request in, intent out, HTTP status
 * back. This is the push-subset chain minus Android (no NsdManager here).
 */
class AirPlayServerTest {

    private val intents = CopyOnWriteArrayList<AirPlayIntent>()
    private var server: AirPlayServer? = null
    private var port = 0

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun startServer() {
        val s = AirPlayServer(onIntent = { intents += it })
        port = s.start()
        server = s
    }

    /** Sends raw bytes, returns the first response line. */
    private fun exchange(raw: String): String? {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write(raw.toByteArray(StandardCharsets.ISO_8859_1))
                flush()
            }
            socket.shutdownOutput()
            return BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1)
            ).readLine()
        }
    }

    private fun awaitIntents(expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (intents.size < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("expected $expected intents, got ${intents.size}", intents.size >= expected)
    }

    @Test
    fun `POST play with plist body should answer 200 and deliver PlayUrl`() {
        startServer()
        val body = "<plist><dict><key>Content-Location</key><string>http://example.com/v.mp4</string>" +
            "<key>Start-Position</key><real>2.0</real></dict></plist>"
        val status = exchange("POST /play HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertTrue("got $status", status!!.startsWith("HTTP/1.1 200"))
        awaitIntents(1)
        assertEquals(AirPlayIntent.PlayUrl("http://example.com/v.mp4", 2.0), intents[0])
    }

    @Test
    fun `POST rate 0 should answer 200 and deliver Pause`() {
        startServer()
        val status = exchange("POST /rate?value=0.000000 HTTP/1.1\r\n\r\n")
        assertTrue(status!!.startsWith("HTTP/1.1 200"))
        awaitIntents(1)
        assertEquals(AirPlayIntent.Pause, intents[0])
    }

    @Test
    fun `POST stop should answer 200 and deliver Stop`() {
        startServer()
        val status = exchange("POST /stop HTTP/1.1\r\n\r\n")
        assertTrue(status!!.startsWith("HTTP/1.1 200"))
        awaitIntents(1)
        assertEquals(AirPlayIntent.Stop, intents[0])
    }

    @Test
    fun `unknown path should answer 404 and deliver nothing`() {
        startServer()
        val status = exchange("GET /server-info HTTP/1.1\r\n\r\n")
        assertTrue(status!!.startsWith("HTTP/1.1 404"))
        assertTrue("no intent expected, got $intents", intents.isEmpty())
    }

    @Test
    fun `garbage bytes should answer 400 and not kill the server`() {
        startServer()
        val bad = exchange("GARBAGE\r\n\r\n")
        assertTrue(bad!!.startsWith("HTTP/1.1 400"))
        // The very next request must still be served.
        val status = exchange("POST /stop HTTP/1.1\r\n\r\n")
        assertTrue(status!!.startsWith("HTTP/1.1 200"))
        awaitIntents(1)
        assertEquals(AirPlayIntent.Stop, intents[0])
    }

    @Test
    fun `stop should close the listener`() {
        startServer()
        val s = server!!
        s.stop()
        assertFalse(s.isRunning)
        // The listener must have released the port. Assert the positive form
        // (an immediate rebind succeeds) — connect-refused is flaky on shared
        // CI hosts, where an unrelated process can briefly occupy the freshly
        // freed ephemeral port.
        val rebound = try {
            ServerSocket().use { fresh ->
                fresh.reuseAddress = true
                fresh.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 8)
                true
            }
        } catch (_: Exception) {
            false
        }
        assertTrue("listener port must be releasable right after stop", rebound)
    }
}
