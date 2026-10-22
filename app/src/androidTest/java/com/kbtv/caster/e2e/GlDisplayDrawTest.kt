package com.kbtv.caster.e2e

import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pixel-level acceptance for the GL display tier: the renderer feeds the
 * recorded mirror dump into the real pipeline while an ImageReader stands in
 * for the screen; the acquired frame must contain non-black pixels. This
 * closes the gap that let the missing eglMakeCurrent ship: prior headless
 * tests asserted decoder output counts, never the drawn image (live session
 * 2026-10-22: 364 frames "rendered", p95 29ms, black screen).
 */
@RunWith(AndroidJUnit4::class)
class GlDisplayDrawTest {

    @Test
    fun glPathDrawsNonBlackPixels() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dump = File(ctx.filesDir, "trace/mirror-dump.h264").readBytes()
        assertTrue("dump too small", dump.size > 100_000)

        // split into packets the same way the live path delivers them
        val nalBounds = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < dump.size) {
            if (dump[i] == 0.toByte() && dump[i + 1] == 0.toByte() && dump[i + 2] == 1.toByte()) {
                val start = if (i > 0 && dump[i - 1] == 0.toByte()) i - 1 else i
                if (nalBounds.isNotEmpty()) {
                    nalBounds[nalBounds.size - 1] = nalBounds.last().first to start
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

        val reader = ImageReader.newInstance(960, 540, android.graphics.PixelFormat.RGBA_8888, 4)
        val renderer = com.kbtv.caster.airplay.MirrorVideoRenderer()
        renderer.attachContext(ctx)
        renderer.setResolution(500, 1080)
        renderer.onSession(true)
        renderer.setSurface(reader.surface)

        var ptsNs = System.nanoTime()
        for (pkt in packets.subList(0, minOf(120, packets.size))) {
            renderer.onFrame(pkt, ptsNs, false)
            ptsNs += 33_000_000L
            Thread.sleep(33)
        }
        Thread.sleep(2500)  // let the GL thread draw and the reader receive

        var sawNonBlack = false
        var img = reader.acquireLatestImage()
        var tries = 0
        while (img == null && tries < 10) {
            Thread.sleep(300)
            img = reader.acquireLatestImage()
            tries++
        }
        if (img != null) {
            val plane = img.planes[0]
            val buf = plane.buffer.duplicate()
            val rowBytes = plane.rowStride
            var maxVal = 0
            val px = ByteArray(4)
            // sample a coarse grid across the frame
            var y = 8
            while (y < img.height - 8 && !sawNonBlack) {
                var x = 8
                while (x < img.width - 8 && !sawNonBlack) {
                    buf.position(y * rowBytes + x * 4)
                    buf.get(px, 0, 4)
                    val lum = px[0].toInt() and 0xFF or (px[1].toInt() and 0xFF) or (px[2].toInt() and 0xFF)
                    if (lum > maxVal) maxVal = lum
                    if (lum > 24) sawNonBlack = true
                    x += 16
                }
                y += 16
            }
            android.util.Log.i("GlDraw", "acquired ${img.width}x${img.height} maxLum=$maxVal nonBlack=$sawNonBlack")
            img.close()
        } else {
            android.util.Log.i("GlDraw", "NO FRAME ACQUIRED from ImageReader")
        }

        renderer.release()
        reader.close()
        assertTrue("GL path drew no image or all-black (acquired=${img != null})", sawNonBlack)
    }
}
