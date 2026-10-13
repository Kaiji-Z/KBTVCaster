package com.kbtv.caster.e2e

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kbtv.caster.airplay.MirrorVideoRenderer
import com.kbtv.caster.trace.TraceRecorder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Headless verification of the production mirror render path: the same
 * MirrorVideoRenderer the service wires to AirPlayMirrorProtocol must decode
 * an H.264 byte-stream to a Surface and deliver frames to the surface
 * consumer. An ImageReader plays the surface consumer (acquiring each frame
 * keeps the decoder's buffer queue flowing — the same job SurfaceView's
 * compositor does on screen).
 *
 * The stream is fed NAL-by-NAL at real-time pacing, mirroring how the native
 * side delivers onVideoData (one Annex-B NAL per call, SPS/PPS first). The
 * 300-frame/150ms criterion is measured on the real TV with a real iPhone;
 * this test pins the functional path: frames reach a real Surface.
 */
@RunWith(AndroidJUnit4::class)
class MirrorRenderPathTest {

    @Test
    fun `cannedH264ShouldRenderToSurfaceThroughMirrorVideoRenderer`() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        TraceRecorder.init(appContext.filesDir, true)
        val stream = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("media/test_320x192.h264").use { it.readBytes() }
        assertTrue("canned stream too small: ${stream.size}", stream.size > 50_000)

        val readerThread = HandlerThread("mirror-reader").apply { start() }
        val framesRendered = CountDownLatch(MIN_FRAMES)
        // YV12 matches what the emulator's software AVC decoder produces into
        // the surface (0x32315659); RGBA_8888 is rejected by nativeImageSetup.
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, android.graphics.ImageFormat.YV12, 8)
        reader.setOnImageAvailableListener({
            it.acquireLatestImage()?.close()
            framesRendered.countDown()
        }, Handler(readerThread.looper))

        val renderer = MirrorVideoRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setResolution(WIDTH, HEIGHT)
            renderer.onSession(true)

            val nals = splitNals(stream)
            var ntpNs = System.nanoTime()
            for (nal in nals) {
                renderer.onFrame(nal, ntpNs, false)
                ntpNs += FRAME_INTERVAL_NS
                // real-time pacing keeps the bounded pending queue from shedding
                SystemClock.sleep(FRAME_INTERVAL_MS)
            }

            assertTrue(
                "only ${MIN_FRAMES - framesRendered.count} frames reached the surface",
                framesRendered.await(60, TimeUnit.SECONDS),
            )
        } finally {
            renderer.onSession(false)
            renderer.release()
            reader.close()
            readerThread.quitSafely()
        }
    }

    /** Split an Annex-B stream into individual NALs (start code included). */
    private fun splitNals(stream: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < stream.size) {
            if (stream[i] == 0.toByte() && stream[i + 1] == 0.toByte() && stream[i + 2] == 1.toByte()) {
                val codeStart = if (i > 0 && stream[i - 1] == 0.toByte()) i - 1 else i
                if (starts.isEmpty() || codeStart != starts.last()) starts.add(codeStart)
                i += 3
            } else {
                i++
            }
        }
        return starts.indices.map { k ->
            val from = starts[k]
            val to = if (k + 1 < starts.size) starts[k + 1] else stream.size
            stream.copyOfRange(from, to)
        }
    }

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 192
        const val MIN_FRAMES = 100
        const val FRAME_INTERVAL_NS = 33_333_333L
        const val FRAME_INTERVAL_MS = 33L
    }
}
