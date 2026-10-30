package com.kbtv.caster.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Headless repro for the video-SWITCHING failure (2026-10-30 stop event):
 * playing a second/third clip inside one mirror session goes black — the
 * MStar decoder's in-band resolution switch (port flush + buffer re-count)
 * dies after a couple of switches per instance, then the sender stops.
 *
 * Replays the recorded mirror dump — today's dumps contain the REAL
 * portrait<->landscape SPS switches captured live — through the REAL
 * MirrorVideoRenderer at live pace (30fps aggregated packets), and asserts
 * rendering keeps progressing after EVERY flip. Expected RED until the fix
 * lands; the fix is done when this is reliably GREEN across 20+ flips.
 *
 * No iPhone needed: this is the closed loop the stop-event post-mortem
 * called for (VERIFICATION.md §10 gap #1 materialized for switching).
 */
@RunWith(AndroidJUnit4::class)
class FlipStreamReproTest {

    @Test
    fun rendererSurvivesResolutionFlips() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dumpFile = File(ctx.filesDir, "trace/mirror-dump.h264")
        assertTrue("mirror dump missing — cast once first", dumpFile.isFile)
        val dump = dumpFile.readBytes()
        assertTrue("dump too small: ${dump.size}", dump.size > 100_000)

        // --- split dump into NALs (same algorithm family as the renderer) ---
        val nalBounds = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < dump.size) {
            if (dump[i] == 0.toByte() && dump[i + 1] == 0.toByte() && dump[i + 2] == 1.toByte()) {
                val start = if (i > 0 && dump[i - 1] == 0.toByte()) i - 1 else i
                if (nalBounds.isNotEmpty()) {
                    val (ps, _) = nalBounds.last()
                    nalBounds[nalBounds.size - 1] = ps to start
                }
                nalBounds.add(start to dump.size)
                i += 3
            } else i++
        }
        val typeOf = { from: Int ->
            var j = from
            while (j + 1 < dump.size && dump[j] == 0.toByte()) j++
            dump[j + 1].toInt() and 0x1F
        }

        // --- aggregate into live-shaped packets; flag SPS flips ---
        data class Pkt(val bytes: ByteArray, val sawNewSps: Boolean)

        val packets = ArrayList<Pkt>()
        var pktStart = -1
        var pktHasNewSps = false
        var lastSps: ByteArray? = null
        for ((from, to) in nalBounds) {
            val t = typeOf(from)
            if (pktStart < 0) pktStart = from
            if (t == 7) {
                val sps = dump.copyOfRange(from, to)
                if (lastSps != null && !sps.contentEquals(lastSps!!)) pktHasNewSps = true
                lastSps = sps
            }
            if (t == 1 || t == 5) {
                packets.add(Pkt(dump.copyOfRange(pktStart, to), pktHasNewSps))
                pktStart = -1
                pktHasNewSps = false
            }
        }
        val flipCount = packets.count { it.sawNewSps }
        val flips = packets.withIndex().filter { it.value.sawNewSps }.map { it.index }
        android.util.Log.i("FlipRepro",
            "dump=${dump.size}B pkts=${packets.size} flips=$flipCount at=$flips")
        // a dump without switches cannot repro the bug — skip, don't pass hollow
        assumeTrue("dump has no resolution flips — cast with video switches first", flipCount >= 2)

        // --- drive the renderer exactly like the live path ---
        val renderer = com.kbtv.caster.airplay.MirrorVideoRenderer()
        renderer.attachContext(ctx)
        val tex = android.graphics.SurfaceTexture(0)
        val surf = android.view.Surface(tex)
        renderer.setResolution(500, 1080)
        renderer.onSession(true)
        renderer.setSurface(surf)

        // v2 lessons: (1) judging in-line starves the decoder through the
        // switch — feed CONTINUOUSLY and judge post-hoc from per-packet
        // samples; (2) the dump is truncated at ~8MB and the fatal switch
        // may not be in it — LOOP the recording so switches accumulate far
        // past the live death point (switch #3).
        val maxLoops = 6
        val total = packets.size * maxLoops
        val renderedAt = LongArray(total)
        val boundaries = ArrayList<Int>()
        try {
            var ptsNs = System.nanoTime()
            var g = 0
            for (loop in 0 until maxLoops) {
                for (i in packets.indices) {
                    renderer.onFrame(packets[i].bytes, ptsNs, false)
                    ptsNs += 33_000_000L
                    renderedAt[g] = renderer.renderedFrameCount
                    if (packets[i].sawNewSps && g > 0) boundaries.add(g)
                    g++
                    Thread.sleep(33)
                }
            }
        } finally {
            android.util.Log.i("FlipRepro",
                "fed=${renderedAt.size} flips=${boundaries.size} lastRendered=${renderedAt.lastOrNull()}")
            renderer.release()
            surf.release()
            tex.release()
        }

        // post-hoc: within 180 packets (6s at 30fps) after each flip the
        // decoder must have produced at least 10 more frames
        val results = ArrayList<String>()
        var dead = 0
        for ((idx, b) in boundaries.withIndex()) {
            val end = minOf(b + 180, renderedAt.size - 1)
            val gain = if (end > b) renderedAt[end] - renderedAt[b] else 0
            val ok = gain >= 10
            if (!ok) dead++
            results.add("flip${idx + 1}@pkt$b=+${gain}${if (ok) "" else " DEAD"}")
        }
        val joined = results.joinToString(" ")
        android.util.Log.i("FlipRepro", "results=$joined")
        assertTrue("decoder died at $dead/${boundaries.size} flips: $joined", dead == 0)
    }
}
