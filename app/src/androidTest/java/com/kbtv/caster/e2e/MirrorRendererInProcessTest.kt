package com.kbtv.caster.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the REAL MirrorVideoRenderer (not a re-implementation) with the
 * recorded mirror dump, replaying live callback shape: one aggregated AU per
 * onFrame (SPS+PPS+SEI+IDR together, then lone slices), resolution set once,
 * a SurfaceTexture-backed display surface. DecoderAbTest already proved the
 * bytes+config decode 4961 frames via a hand-rolled MediaCodec loop; this
 * test isolates whether the renderer's own threading/queue/lifecycle logic
 * (pending queue, feeder, maybeStartCodecLocked gating, bufferMode blit)
 * reproduces that — the exact code that produced frames=0 in the live
 * 2026-10-20 field session.
 */
@RunWith(AndroidJUnit4::class)
class MirrorRendererInProcessTest {

    @Test
    fun rendererDecodesDump() {
        Timber.plant(object : Timber.DebugTree() {})
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dumpFile = File(ctx.filesDir, "trace/mirror-dump.h264")
        assertTrue("mirror dump missing — connect a mirror session first", dumpFile.isFile)
        val dump = dumpFile.readBytes()
        assertTrue("dump too small: ${dump.size}", dump.size > 100_000)

        // --- split dump into NALs (same algorithm family as the renderer) ---
        val nalBounds = ArrayList<Pair<Int, Int>>() // (start, end)
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
        android.util.Log.i("MirrorInProc", "dump=${dump.size}B nals=${nalBounds.size}")

        // --- group NALs into live-shaped callbacks: a buffer ends after an IDR
        // slice or a non-IDR slice; parameter sets before a slice ride along ---
        val packets = ArrayList<ByteArray>()
        var pktStart = -1
        for ((from, to) in nalBounds) {
            val t = typeOf(from)
            if (pktStart < 0) pktStart = from
            if (t == 1 || t == 5) {
                packets.add(dump.copyOfRange(pktStart, to))
                pktStart = -1
            }
        }
        android.util.Log.i("MirrorInProc", "replayed packets=${packets.size}")

        // --- drive the renderer exactly like the live path does ---
        // Phase A: rig-style single NALs (splitAnnexBNals passthrough path);
        // Phase B: live-style aggregated packets (splitAnnexBNals split path).
        // The raw rig (DecoderAbTest) decodes this dump feeding single NALs;
        // if A renders and B does not, the split/queue aggregation is the bug.
        var resultA = false
        var resultB = false

        // Phase A
        run {
            val renderer = com.kbtv.caster.airplay.MirrorVideoRenderer()
            renderer.attachContext(ctx)
            val tex = android.graphics.SurfaceTexture(0)
            val surf = android.view.Surface(tex)
            renderer.setResolution(500, 1080)
            renderer.onSession(true)
            renderer.setSurface(surf)
            val firstFrame = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                renderer.rendering.observeForever { rendering -> if (rendering) firstFrame.countDown() }
            }
            var ptsNs = System.nanoTime()
            for ((from, to) in nalBounds) {
                renderer.onFrame(dump.copyOfRange(from, to), ptsNs, false)
                ptsNs += 8_000_000L
                Thread.sleep(2)
            }
            resultA = firstFrame.await(45, TimeUnit.SECONDS)
            android.util.Log.i("MirrorInProc", "PHASE-A single-NAL feed: firstFrame=$resultA")
            renderer.release()
            surf.release()
            tex.release()
            Thread.sleep(500)
        }

        // Phase B
        run {
            val renderer = com.kbtv.caster.airplay.MirrorVideoRenderer()
            renderer.attachContext(ctx)
            val tex = android.graphics.SurfaceTexture(0)
            val surf = android.view.Surface(tex)
            renderer.setResolution(500, 1080)
            renderer.onSession(true)
            renderer.setSurface(surf)
            val firstFrame = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                renderer.rendering.observeForever { rendering -> if (rendering) firstFrame.countDown() }
            }
            // Live-equivalent pacing: 30fps aggregated packets, real time.
            // Reproduces the field standing-queue latency (830ms p95) so the
            // feeder stats explain it without an iPhone session.
            var ptsNs = System.nanoTime()
            val limit = minOf(packets.size, 900)
            for (pkt in packets.subList(0, limit)) {
                renderer.onFrame(pkt, ptsNs, false)
                ptsNs += 33_000_000L
                Thread.sleep(33)
            }
            Thread.sleep(10_000)
            resultB = firstFrame.await(45, TimeUnit.SECONDS)
            android.util.Log.i("MirrorInProc", "PHASE-B aggregated-packet feed: firstFrame=$resultB")
            renderer.release()
            surf.release()
            tex.release()
        }
    }

    /**
     * The rig (DecoderAbTest, inline loop) decodes 4961 frames while the
     * real renderer fed the same NALs gets every buffer rejected
     * (MS_OMX_H264DEC "Error Code" per NAL). Bisects the two code deltas:
     * codec creation (byName vs byType) and buf.clear() before put.
     */
    @Test
    fun bisectCreationPath() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dumpFile = File(ctx.filesDir, "trace/mirror-dump.h264")
        assertTrue("mirror dump missing", dumpFile.isFile)
        val dump = dumpFile.readBytes()
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
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for ((from, to) in nalBounds) {
            val t = typeOf(from)
            if (t == 7 && sps == null) sps = dump.copyOfRange(from, to)
            if (t == 8 && pps == null) pps = dump.copyOfRange(from, to)
        }
        assertTrue("no sps/pps in dump", sps != null && pps != null)
        val hist = HashMap<Int, Int>()
        for ((from, _) in nalBounds) {
            val t = typeOf(from)
            hist[t] = (hist[t] ?: 0) + 1
        }
        android.util.Log.i("BisectAb", "NAL-TYPES: ${hist.entries.sortedBy { it.key }}")
        // First six NAL types in stream order — poison-NAL position check.
        android.util.Log.i("BisectAb", "FIRST-NALS: ${nalBounds.take(6).map { typeOf(it.first) }}")

        fun inlineDecode(tag: String, byType: Boolean, clearFirst: Boolean): Int {
            val format = android.media.MediaFormat.createVideoFormat(
                android.media.MediaFormat.MIMETYPE_VIDEO_AVC, 500, 1080).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps!! + pps!!))
                setInteger("low-latency", 1)
                setInteger("lowlatency", 1)
                setInteger("min-latency", 1)
                setInteger("vdec-lowlatency", 1)
            }
            val codec = try {
                (if (byType) android.media.MediaCodec.createDecoderByType(android.media.MediaFormat.MIMETYPE_VIDEO_AVC)
                else android.media.MediaCodec.createByCodecName("OMX.MS.AVC.Decoder"))
                    .also { it.configure(format, null, null, 0); it.start() }
            } catch (e: Exception) {
                android.util.Log.i("BisectAb", "$tag configure/start failed: ${e.message}")
                return -2
            }
            var decoded = 0
            var fed = 0
            val codecName = try { codec.name } catch (_: Exception) { "?" }
            val info = android.media.MediaCodec.BufferInfo()
            try {
                for ((from, to) in nalBounds) {
                    val t = typeOf(from)
                    if (!(t == 1 || t == 5 || t == 6 || t == 7 || t == 8)) continue
                    val bytes = dump.copyOfRange(from, to)
                    val idx = codec.dequeueInputBuffer(20_000)
                    if (idx < 0) continue
                    val buf = codec.getInputBuffer(idx)!!
                    if (clearFirst) buf.clear()
                    buf.put(bytes)
                    codec.queueInputBuffer(idx, 0, bytes.size, fed * 33_333L, 0)
                    fed++
                    var drain = 0
                    while (drain < 4) {
                        val ob = codec.dequeueOutputBuffer(info, 0)
                        if (ob >= 0) { codec.releaseOutputBuffer(ob, false); decoded++ }
                        else break
                        drain++
                    }
                }
                var tries = 0
                while (tries < 600) {
                    val ob = codec.dequeueOutputBuffer(info, 5_000)
                    if (ob >= 0) { codec.releaseOutputBuffer(ob, false); decoded++ } else break
                    tries++
                }
            } catch (e: Exception) {
                android.util.Log.i("BisectAb", "$tag runtime: ${e.message}")
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                try { codec.release() } catch (_: Exception) {}
            }
            android.util.Log.i("BisectAb", "$tag name=$codecName byType=$byType clear=$clearFirst fed=$fed decoded=$decoded")
            return decoded
        }

        val c = inlineDecode("C-byName-noClear", byType = false, clearFirst = false)
        val d = inlineDecode("D-byType-noClear", byType = true, clearFirst = false)
        val e = inlineDecode("E-byType-clear", byType = true, clearFirst = true)
        val f = inlineDecode("F-byName-clear", byType = false, clearFirst = true)
        android.util.Log.i("BisectAb", "BISECT-VERDICT C=$c D=$d E=$e F=$f")
        assertTrue("control variant C regressed: $c", c >= 100)
    }

    /**
     * Diagnostic: feed the renderer the first 40 NALs single-NAL style while
     * logging (test-side) what goes into onFrame and (renderer-side probes)
     * what actually reaches the codec and what it was configured with.
     */
    @Test
    fun probeFirstNals() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dump = File(ctx.filesDir, "trace/mirror-dump.h264").readBytes()
        val bounds = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < dump.size) {
            if (dump[i] == 0.toByte() && dump[i + 1] == 0.toByte() && dump[i + 2] == 1.toByte()) {
                val start = if (i > 0 && dump[i - 1] == 0.toByte()) i - 1 else i
                if (bounds.isNotEmpty()) bounds[bounds.size - 1] = bounds.last().first to start
                bounds.add(start to dump.size)
                i += 3
            } else i++
        }
        val renderer = com.kbtv.caster.airplay.MirrorVideoRenderer()
        renderer.attachContext(ctx)
        val tex = android.graphics.SurfaceTexture(0)
        val surf = android.view.Surface(tex)
        renderer.setResolution(500, 1080)
        renderer.onSession(true)
        renderer.setSurface(surf)
        for (nb in bounds.take(40).withIndex()) {
            val bytes = dump.copyOfRange(nb.value.first, nb.value.second)
            android.util.Log.i("MirrorProbe",
                "sent#${nb.index} size=${bytes.size} hex=${bytes.take(16).joinToString("") { "%02x".format(it) }}")
            renderer.onFrame(bytes, System.nanoTime() + nb.index * 8_000_000L, false)
            Thread.sleep(4)
        }
        Thread.sleep(4000)
        renderer.release()
        surf.release()
        tex.release()
    }
}
