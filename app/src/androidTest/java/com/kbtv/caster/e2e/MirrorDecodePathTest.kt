package com.kbtv.caster.e2e

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kbtv.caster.trace.TraceRecorder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Mirror decode-path capability verification — VERIFICATION.md criterion 4④:
 * a canned H.264 stream must decode ≥100 frames with per-frame decode
 * latency p95 < 150 ms. This exercises exactly the MediaCodec AVC decode
 * component the AirPlay mirroring pipeline (SPEC B5) will use.
 *
 * The canned stream is a deterministic ffmpeg-generated Annex-B elementary
 * stream (testsrc2, 320x192@30fps, baseline, zerolatency) bundled as a test
 * asset — generated with:
 *   ffmpeg -f lavfi -i testsrc2=size=320x192:rate=30:duration=10
 *     -pix_fmt yuv420p -c:v libx264 -profile:v baseline -preset ultrafast
 *     -tune zerolatency -bf 0 -x264-params keyint=30:bframes=0 -b:v 300k
 *     -f h264 test_320x192.h264
 *
 * The stream is split into access units (slice-NAL boundaries) so each input
 * buffer maps 1:1 to one output frame and per-frame latency is exact. Feeding
 * is window-gated to mirror real-time pacing — without the gate codec backlog
 * would dominate late-frame latency. Results are written to the JSONL trace
 * (event b5_decode_stats) as durable evidence.
 */
@RunWith(AndroidJUnit4::class)
class MirrorDecodePathTest {

    private val appContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `cannedH264ShouldDecodeAtLeast100FramesWithP95Under150Ms`() {
        TraceRecorder.init(appContext.filesDir, true)
        val stream = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("media/test_320x192.h264").use { it.readBytes() }
        assertTrue("canned stream too small: ${stream.size}", stream.size > 50_000)

        val latencies = decodeAndMeasure(stream)
        assertTrue(
            "decoded only ${latencies.size} frames, need >= $MIN_FRAMES",
            latencies.size >= MIN_FRAMES
        )
        val sorted = latencies.sorted()
        val p95 = sorted[sorted.size * 95 / 100]
        val p95Ms = TimeUnit.NANOSECONDS.toMicros(p95) / 1000.0
        Log.w(TAG, "decoded ${latencies.size} frames; p95=${p95Ms}ms")
        TraceRecorder.log(
            "b5_decode_stats",
            "frames" to latencies.size,
            "p95Micros" to TimeUnit.NANOSECONDS.toMicros(p95).toInt(),
        )
        assertTrue("decode latency p95 ${p95Ms}ms exceeds 150ms", p95Ms < 150.0)
    }

    /** Decode the canned stream, returning per-frame latencies in nanoseconds. */
    private fun decodeAndMeasure(stream: ByteArray): List<Long> {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
        // Explicit software decoder: byType prefers c2.goldfish.h264.decoder on
        // emulators, which stalls on real byte-streams (queue timeout →
        // UNKNOWN_ERROR). c2.android.avc.decoder is the software workhorse.
        // createSoftwareAvcDecoder configures and starts the codec.
        val decoder = createSoftwareAvcDecoder(format)

        val accessUnits = splitAnnexB(stream)
        val queuedAt = HashMap<Long, Long>() // au ptsUs -> nanoTime queued
        val latencies = ArrayList<Long>()
        val info = MediaCodec.BufferInfo()
        var auIndex = 0
        var outputs = 0
        var eosSeen = false

        fun drainOutput() {
            while (true) {
                val oIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    oIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    oIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 ->
                        decoder.releaseOutputBuffer(oIndex, false)
                    else -> {
                        queuedAt.remove(info.presentationTimeUs)?.let { queued ->
                            latencies.add(System.nanoTime() - queued)
                        }
                        decoder.releaseOutputBuffer(oIndex, false)
                        outputs++
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosSeen = true
                    }
                }
            }
        }

        while (auIndex < accessUnits.size) {
            if (queuedAt.size < MAX_INFLIGHT_AUS) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val au = accessUnits[auIndex]
                    val buf = decoder.getInputBuffer(inIndex)!!
                    buf.clear()
                    buf.put(au)
                    val pts = auIndex * PTS_STEP_USEC
                    queuedAt[pts] = System.nanoTime()
                    decoder.queueInputBuffer(inIndex, 0, au.size, pts, 0)
                    auIndex++
                }
            }
            drainOutput()
        }
        // Flush the tail: signal EOS and drain until EOS output or deadline.
        val eosIndex = decoder.dequeueInputBuffer(10_000)
        if (eosIndex >= 0) {
            decoder.queueInputBuffer(eosIndex, 0, 0, auIndex * PTS_STEP_USEC, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TAIL_DRAIN_TIMEOUT_MS)
        while (!eosSeen && System.nanoTime() < deadline) {
            drainOutput()
        }
        decoder.stop()
        decoder.release()
        Log.w(TAG, "AUs=${accessUnits.size} outputs=$outputs latencies=${latencies.size}")
        return latencies
    }

    /**
     * Split an Annex-B elementary stream into access units. A new AU starts at
     * each slice NAL (type 1 non-IDR / 5 IDR); parameter sets and SEI prefix
     * the AU that follows them. Returned slices retain their start codes.
     */
    private fun splitAnnexB(stream: ByteArray): List<ByteArray> {
        val auStarts = ArrayList<Int>()
        var prefixStart = -1
        var i = 0
        while (i + 3 < stream.size) {
            if (stream[i] == 0.toByte() && stream[i + 1] == 0.toByte() && stream[i + 2] == 1.toByte()) {
                val nalType = stream[i + 3].toInt() and 0x1F
                val codeStart = if (i > 0 && stream[i - 1] == 0.toByte()) i - 1 else i
                if (nalType == 1 || nalType == 5) {
                    auStarts.add(if (prefixStart >= 0) prefixStart else codeStart)
                    prefixStart = -1
                } else if (prefixStart < 0) {
                    prefixStart = codeStart
                }
                i += 3
            } else {
                i++
            }
        }
        val aus = ArrayList<ByteArray>(auStarts.size)
        for (k in auStarts.indices) {
            val from = auStarts[k]
            val to = if (k + 1 < auStarts.size) auStarts[k + 1] else stream.size
            aus.add(stream.copyOfRange(from, to))
        }
        return aus
    }

    private fun createSoftwareAvcDecoder(format: MediaFormat): MediaCodec {
        val candidates = listOf("c2.android.avc.decoder", "OMX.google.h264.decoder")
        var lastError: Exception? = null
        for (name in candidates) {
            try {
                val codec = MediaCodec.createByCodecName(name)
                codec.configure(format, null, null, 0)
                codec.start()
                return codec
            } catch (e: Exception) {
                Log.w(TAG, "decoder $name unavailable: ${e.message}")
                lastError = e
            }
        }
        throw AssertionError("no software AVC decoder available", lastError)
    }

    companion object {
        const val TAG = "MirrorDecodePath"
        const val WIDTH = 320
        const val HEIGHT = 192
        const val MIN_FRAMES = 100
        const val MAX_INFLIGHT_AUS = 8
        const val TAIL_DRAIN_TIMEOUT_MS = 10_000L
        const val PTS_STEP_USEC = 33_333L
    }
}
