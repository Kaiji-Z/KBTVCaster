package com.kbtv.caster.service.protocol

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for [CastProtocol.PlaybackCommand] sealed hierarchy.
 *
 * Pure-JVM (no Robolectric). android.net.Uri is a stub that throws
 * "not mocked" in plain unit tests, so we inject a Mockito mock Uri.
 * What we verify: the command model's identity/equality and the sink
 * forwarding — these are load-bearing for protocol ↔ service dispatch.
 */
class CastProtocolTest {

    private val fakeUri: Uri = mock()

    @Test
    fun `Play command should carry its uri`() {
        val cmd = CastProtocol.PlaybackCommand.Play(fakeUri)
        assertSame(fakeUri, cmd.uri)
    }

    @Test
    fun `Seek command should carry its position in ms`() {
        val cmd = CastProtocol.PlaybackCommand.Seek(12_500L)
        assertEquals(12_500L, cmd.positionMs)
    }

    @Test
    fun `Pause Resume Stop should be singleton objects`() {
        assertSame(CastProtocol.PlaybackCommand.Pause, CastProtocol.PlaybackCommand.Pause)
        assertSame(CastProtocol.PlaybackCommand.Resume, CastProtocol.PlaybackCommand.Resume)
        assertSame(CastProtocol.PlaybackCommand.Stop, CastProtocol.PlaybackCommand.Stop)
    }

    @Test
    fun `Pause Resume Stop should be mutually distinct`() {
        val set = setOf(
            CastProtocol.PlaybackCommand.Pause,
            CastProtocol.PlaybackCommand.Resume,
            CastProtocol.PlaybackCommand.Stop
        )
        assertEquals(3, set.size)
    }

    @Test
    fun `Play with the same uri should be equal`() {
        assertEquals(
            CastProtocol.PlaybackCommand.Play(fakeUri),
            CastProtocol.PlaybackCommand.Play(fakeUri)
        )
    }

    @Test
    fun `Seek with different positions should not be equal`() {
        assertFalse(
            CastProtocol.PlaybackCommand.Seek(1000L) ==
                CastProtocol.PlaybackCommand.Seek(2000L)
        )
    }

    @Test
    fun `all five command subtypes should be distinct branches of the sealed class`() {
        val samples: List<CastProtocol.PlaybackCommand> = listOf(
            CastProtocol.PlaybackCommand.Play(fakeUri),
            CastProtocol.PlaybackCommand.Pause,
            CastProtocol.PlaybackCommand.Resume,
            CastProtocol.PlaybackCommand.Stop,
            CastProtocol.PlaybackCommand.Seek(0L)
        )
        assertEquals(5, samples.map { it::class }.toSet().size)
        samples.forEach { assertNotNull(it) }
    }

    @Test
    fun `PlaybackSink should receive the exact command forwarded to it`() {
        val received = mutableListOf<CastProtocol.PlaybackCommand>()
        val sink = CastProtocol.PlaybackSink { received += it }

        val play = CastProtocol.PlaybackCommand.Play(fakeUri)
        sink.onCommand(play)
        sink.onCommand(CastProtocol.PlaybackCommand.Pause)

        assertEquals(2, received.size)
        assertSame(play, received[0])
        assertSame(CastProtocol.PlaybackCommand.Pause, received[1])
        assertTrue(received[0] is CastProtocol.PlaybackCommand.Play)
    }
}
