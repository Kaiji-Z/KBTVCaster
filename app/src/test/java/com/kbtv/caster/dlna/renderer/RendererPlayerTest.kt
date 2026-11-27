package com.kbtv.caster.dlna.renderer

import android.content.Context
import android.net.Uri
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser
import org.fourthline.cling.support.lastchange.LastChange
import org.fourthline.cling.support.model.TransportState
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlLastChangeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.mock
import java.net.URI
import javax.xml.transform.Source

/**
 * Unit tests for [RendererPlayer] — the clean-room DMR transport state machine.
 *
 * Replaces the old TransportStateTest, which only asserted vendored Cling's
 * enum constants. These tests drive the REAL state machine the way a control
 * point does (SetAVTransportURI → Play → Pause → Seek → Stop) and pin the
 * behaviors the AVTransport contract depends on: auto-play on SetAVTransportURI
 * (apps that never send Play still get playback), command forwarding to the
 * [DmrPlaybackListener], TransportAction advertising per state, and the
 * position bookkeeping behind GetPositionInfo.
 *
 * Pure JVM: the Context mock degrades the volume paths gracefully (no
 * AudioManager in unit tests); android.net.Uri.parse is statically mocked
 * because the unit-test android.jar stub throws "not mocked".
 */
class RendererPlayerTest {

    private val instanceId = UnsignedIntegerFourBytes(0)

    /**
     * The AVT/RC LastChange XSDs are not packaged anywhere in this repo — on
     * Android production skips validation (ModelUtil.ANDROID_RUNTIME → null
     * schema sources). Mirror exactly that on the JVM: without this, the
     * seamless base constructor eagerly loads the schema and dies on the
     * missing resource before any test can run.
     */
    private class NoSchemaAVTParser : AVTransportLastChangeParser() {
        override fun getSchemaSources(): Array<Source>? = null
    }

    private class NoSchemaRCParser : RenderingControlLastChangeParser() {
        override fun getSchemaSources(): Array<Source>? = null
    }

    private fun newPlayer(): RendererPlayer = RendererPlayer(
        instanceId,
        mock<Context>(), // getSystemService returns null → volume ops no-op safely
        LastChange(NoSchemaAVTParser()),
        LastChange(NoSchemaRCParser()),
    )

    /**
     * Runs [block] with a player whose DmrPlaybackListener forwards event
     * names into a captured list. Uri.parse is intercepted on the stub
     * android.jar for the whole block (mockito-core 5.x inline mock maker).
     */
    private fun withPlayer(block: (RendererPlayer, MutableList<String>) -> Unit) {
        val forwarded = mutableListOf<String>()
        val uriParse = Mockito.mockStatic(Uri::class.java)
        try {
            uriParse.`when`<Uri> { Uri.parse(anyString()) }.thenAnswer { mock<Uri>() }
            val player = newPlayer()
            player.playbackListener = object : DmrPlaybackListener {
                override fun onPlay(uri: Uri) { forwarded += "play" }
                override fun onPause() { forwarded += "pause" }
                override fun onStop() { forwarded += "stop" }
                override fun onSeek(positionMs: Long) { forwarded += "seek:$positionMs" }
            }
            block(player, forwarded)
        } finally {
            uriParse.close()
        }
    }

    // ===== state machine + forwarding =====

    @Test
    fun `setUri stores the uri and auto-plays`() = withPlayer { player, forwarded ->
        player.setUri(URI("http://example.com/v.mp4"), "<meta/>")

        assertEquals("http://example.com/v.mp4", player.mediaInfo.currentURI)
        assertEquals("http://example.com/v.mp4", player.positionInfo.trackURI)
        assertEquals(TransportState.STOPPED, player.transportInfo.currentTransportState)
        // Apps that only send SetAVTransportURI expect playback to start —
        // the listener must have been notified once, immediately.
        assertEquals(listOf("play"), forwarded)
        assertEquals("<meta/>", player.mediaInfo.currentURIMetaData)
    }

    @Test
    fun `play with a stored uri forwards onPlay and reports PLAYING`() = withPlayer { player, forwarded ->
        player.setUri(URI("http://example.com/v.mp4"), null)
        val afterSetUri = forwarded.size
        player.play()

        assertEquals(afterSetUri + 1, forwarded.size)
        assertEquals("play", forwarded.last())
        assertEquals(TransportState.PLAYING, player.transportInfo.currentTransportState)
    }

    @Test
    fun `play without any uri must not forward onPlay`() = withPlayer { player, forwarded ->
        player.play()

        assertTrue(forwarded.isEmpty())
        assertEquals(TransportState.PLAYING, player.transportInfo.currentTransportState)
    }

    @Test
    fun `pause forwards onPause and reports PAUSED_PLAYBACK`() = withPlayer { player, forwarded ->
        player.pause()

        assertEquals(listOf("pause"), forwarded)
        assertEquals(TransportState.PAUSED_PLAYBACK, player.transportInfo.currentTransportState)
    }

    @Test
    fun `stop forwards onStop and reports STOPPED`() = withPlayer { player, forwarded ->
        player.stop()

        assertEquals(listOf("stop"), forwarded)
        assertEquals(TransportState.STOPPED, player.transportInfo.currentTransportState)
    }

    @Test
    fun `commands forward in arrival order`() = withPlayer { player, forwarded ->
        player.setUri(URI("http://example.com/v.mp4"), null)
        player.pause()
        player.seek(1_000)
        player.stop()

        assertEquals(listOf("play", "pause", "seek:1000", "stop"), forwarded)
    }

    @Test
    fun `a null listener must not throw on any command`() {
        val player = newPlayer()
        assertNull(player.playbackListener)
        // None of these may throw before a protocol wires the listener up.
        player.setUri(URI("http://example.com/v.mp4"), null)
        player.play()
        player.pause()
        player.seek(0)
        player.stop()
    }

    // ===== seek bookkeeping (GetPositionInfo source) =====

    @Test
    fun `seek updates the REL_TIME position`() = withPlayer { player, forwarded ->
        player.setUri(URI("http://example.com/v.mp4"), null)
        player.seek(61_000)

        assertEquals("seek:61000", forwarded.last())
        assertEquals("00:01:01", player.positionInfo.relTime)
    }

    // ===== TransportAction advertising per state =====

    @Test
    fun `fresh player advertises only Play`() = withPlayer { player, _ ->
        assertEquals(listOf(org.fourthline.cling.support.model.TransportAction.Play),
            player.currentTransportActions().toList())
    }

    @Test
    fun `playing advertises Stop Pause Seek`() = withPlayer { player, _ ->
        player.play()
        val actions = player.currentTransportActions().toList()
        assertTrue(org.fourthline.cling.support.model.TransportAction.Stop in actions)
        assertTrue(org.fourthline.cling.support.model.TransportAction.Pause in actions)
        assertTrue(org.fourthline.cling.support.model.TransportAction.Seek in actions)
    }

    @Test
    fun `paused advertises Play on top of Stop Pause Seek`() = withPlayer { player, _ ->
        player.play()
        player.pause()
        val actions = player.currentTransportActions().toList()
        assertTrue(org.fourthline.cling.support.model.TransportAction.Play in actions)
        assertTrue(org.fourthline.cling.support.model.TransportAction.Pause in actions)
        assertTrue(org.fourthline.cling.support.model.TransportAction.Seek in actions)
    }

    // ===== RendererTransport: URI validation and action mapping =====

    @Test
    fun `transport rejects an empty URI`() {
        val transport = transportWithPlayer()
        try {
            transport.setAVTransportURI(instanceId, "", null)
            throw AssertionError("empty URI must be rejected")
        } catch (_: org.fourthline.cling.support.avtransport.AVTransportException) {
            // expected
        }
    }

    @Test
    fun `transport rejects a non-http scheme`() {
        val transport = transportWithPlayer()
        try {
            transport.setAVTransportURI(instanceId, "ftp://example.com/v.mp4", null)
            throw AssertionError("ftp scheme must be rejected")
        } catch (_: org.fourthline.cling.support.avtransport.AVTransportException) {
            // expected
        }
    }

    @Test
    fun `transport accepts an http URI and binds media info`() = withPlayer { player, _ ->
        val transport = transportWithPlayer(player)
        transport.setAVTransportURI(instanceId, "http://example.com/v.mp4", null)

        assertEquals("http://example.com/v.mp4", player.mediaInfo.currentURI)
        assertEquals(player.mediaInfo, transport.getMediaInfo(instanceId))
        assertEquals(player.transportInfo, transport.getTransportInfo(instanceId))
        assertEquals(player.positionInfo, transport.getPositionInfo(instanceId))
    }

    @Test
    fun `transport maps Play Pause Stop onto the player`() = withPlayer { player, forwarded ->
        val transport = transportWithPlayer(player)
        transport.setAVTransportURI(instanceId, "http://example.com/v.mp4", null)
        transport.play(instanceId, "1")
        transport.pause(instanceId)
        transport.stop(instanceId)

        assertEquals(listOf("play", "play", "pause", "stop"), forwarded)
        assertEquals(TransportState.STOPPED, player.transportInfo.currentTransportState)
    }

    @Test
    fun `transport REL_TIME seek reaches the player in ms`() = withPlayer { player, forwarded ->
        val transport = transportWithPlayer(player)
        transport.seek(instanceId, "REL_TIME", "0:01:00")

        assertEquals("seek:60000", forwarded.single())
    }

    @Test
    fun `transport rejects non-REL_TIME seek modes`() {
        val transport = transportWithPlayer()
        try {
            transport.seek(instanceId, "ABS_TIME", "0:01:00")
            throw AssertionError("ABS_TIME must be rejected")
        } catch (_: org.fourthline.cling.support.avtransport.AVTransportException) {
            // expected
        }
    }

    @Test
    fun `transport rejects a malformed REL_TIME target`() {
        val transport = transportWithPlayer()
        try {
            transport.seek(instanceId, "REL_TIME", "not-a-time")
            throw AssertionError("malformed target must be rejected")
        } catch (_: org.fourthline.cling.support.avtransport.AVTransportException) {
            // expected
        }
    }

    @Test
    fun `transport exposes exactly the configured instances`() {
        val transport = transportWithPlayer()
        assertEquals(1, transport.getCurrentInstanceIds().size)
        assertEquals(instanceId, transport.getCurrentInstanceIds()[0])
    }

    /** A transport over one fresh player; used by the rejection tests that never reach the listener. */
    private fun transportWithPlayer(player: RendererPlayer = newPlayer()): RendererTransport =
        RendererTransport(
            LastChange(NoSchemaAVTParser()),
            mapOf(instanceId to player),
        )
}
