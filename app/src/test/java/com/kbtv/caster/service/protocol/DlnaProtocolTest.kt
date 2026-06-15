package com.kbtv.caster.service.protocol

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for [DlnaProtocol]'s command translation.
 *
 * DlnaProtocol implements ZxtMediaPlayer.PlaybackListener: UPnP
 * AVTransport events arrive as onPlay/onPause/onStop/onSeek and must be
 * translated into the matching [CastProtocol.PlaybackCommand] and
 * forwarded to the sink. We verify that mapping here.
 *
 * We do NOT call start() — it binds the Cling UPnP service and touches
 * Android framework APIs that aren't available in a plain JVM test.
 * Instead we drive the listener methods directly with a capturing sink.
 */
class DlnaProtocolTest {

    private fun newProtocolWithSink(): Pair<DlnaProtocol, MutableList<CastProtocol.PlaybackCommand>> {
        val protocol = DlnaProtocol()
        val received = mutableListOf<CastProtocol.PlaybackCommand>()
        protocol.sink = CastProtocol.PlaybackSink { received += it }
        return protocol to received
    }

    @Test
    fun `onPlay should forward a Play command carrying the uri`() {
        val (protocol, received) = newProtocolWithSink()
        val uri: Uri = mock()

        protocol.onPlay(uri)

        assertEquals(1, received.size)
        val cmd = received.first()
        assertTrue(cmd is CastProtocol.PlaybackCommand.Play)
        assertEquals(uri, (cmd as CastProtocol.PlaybackCommand.Play).uri)
    }

    @Test
    fun `onPause should forward a Pause command`() {
        val (protocol, received) = newProtocolWithSink()

        protocol.onPause()

        assertEquals(1, received.size)
        assertEquals(CastProtocol.PlaybackCommand.Pause, received.first())
    }

    @Test
    fun `onStop should forward a Stop command`() {
        val (protocol, received) = newProtocolWithSink()

        protocol.onStop()

        assertEquals(1, received.size)
        assertEquals(CastProtocol.PlaybackCommand.Stop, received.first())
    }

    @Test
    fun `onSeek should forward a Seek command with the position in ms`() {
        val (protocol, received) = newProtocolWithSink()

        protocol.onSeek(42_000L)

        assertEquals(1, received.size)
        val cmd = received.first()
        assertTrue(cmd is CastProtocol.PlaybackCommand.Seek)
        assertEquals(42_000L, (cmd as CastProtocol.PlaybackCommand.Seek).positionMs)
    }

    @Test
    fun `commands should be forwarded in arrival order`() {
        val (protocol, received) = newProtocolWithSink()
        val uri: Uri = mock()

        protocol.onPlay(uri)
        protocol.onPause()
        protocol.onSeek(1000L)
        protocol.onStop()

        assertEquals(4, received.size)
        assertTrue(received[0] is CastProtocol.PlaybackCommand.Play)
        assertTrue(received[1] is CastProtocol.PlaybackCommand.Pause)
        assertTrue(received[2] is CastProtocol.PlaybackCommand.Seek)
        assertTrue(received[3] is CastProtocol.PlaybackCommand.Stop)
    }

    @Test
    fun `no sink set should not throw on any listener method`() {
        // Before start() runs, sink is null — listener calls must be no-ops.
        val protocol = DlnaProtocol()
        assertNull(protocol.sink)

        // None of these should throw
        protocol.onPlay(mock())
        protocol.onPause()
        protocol.onStop()
        protocol.onSeek(0L)
    }

    @Test
    fun `name should be DLNA`() {
        val protocol = DlnaProtocol()
        assertEquals("DLNA", protocol.name)
    }
}
