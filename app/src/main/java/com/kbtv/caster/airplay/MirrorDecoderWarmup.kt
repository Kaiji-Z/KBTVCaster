package com.kbtv.caster.airplay

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.kbtv.caster.trace.TraceRecorder
import timber.log.Timber

/**
 * The MStar field firmware (SHARP Android 6) refuses a decoder's first use
 * after a cold boot until some client completes a full create-decode-release
 * cycle — the TV's bundled 乐播-based service warms it for itself, which is
 * why KBTVCaster's first mirror session after a reboot rendered nothing
 * (frames=0, every instance refused) while 多屏互动 worked minutes later,
 * and our own probe then worked too. This thread replays a bundled H264 dump
 * through OMX.MS.AVC.Decoder at protocol start until one probe decodes a
 * frame, backing off exponentially, so the cold window is eaten here instead
 * of by the user's first cast. Backoff keeps the churn shallow — restart
 * storms are what deepen the firmware wedge in the first place.
 */
class MirrorDecoderWarmup(
    private val dumpFile: java.io.File,
    private val sessionActive: () -> Boolean,
) : Thread("mirror-decoder-warmup") {

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    override fun run() {
        if (!dumpFile.exists()) {
            Timber.w("mirror decoder warmup skipped: no dump")
            return
        }
        val data = dumpFile.readBytes()
        val nals = annexBNals(data)
        if (nals.size < 4) return
        var backoffMs = 30_000L
        var attempt = 0
        while (!cancelled && attempt < 24 && !sessionActive()) {
            attempt++
            val ok = probeOnce(data, nals)
            TraceRecorder.log("mirror_warmup", "result" to (if (ok) "ok" else "cold"), "attempt" to attempt)
            if (ok) {
                Timber.i("mirror decoder warmup ok (attempt %d)", attempt)
                return
            }
            Timber.w("mirror decoder warmup cold (attempt %d)", attempt)
            sleep(backoffMs)
            backoffMs = minOf(backoffMs * 2, 300_000L)
        }
    }

    /** One create-feed-release cycle; true when at least one frame decoded. */
    private fun probeOnce(data: ByteArray, nals: List<Pair<Int, Int>>): Boolean {
        var st: SurfaceTexture? = null
        var surface: Surface? = null
        var codec: MediaCodec? = null
        try {
            st = SurfaceTexture(0).apply { setDefaultBufferSize(64, 64) }
            surface = Surface(st)
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(fmt, surface, null, 0)
            codec.start()

            // csd chunk: leading parameter sets (SPS 7 / PPS 8) up to the first VCL NAL
            val firstVcl = nals.indexOfFirst { val t = (data[it.first].toInt() and 0x1F); t == 5 || t == 1 }
            if (firstVcl <= 1) return false
            val csdEnd = nals[firstVcl - 1].second
            val chunks = ArrayList<Pair<Int, Int>>()
            chunks.add(nals.first().first to csdEnd)
            for (i in firstVcl until minOf(firstVcl + 12, nals.size)) {
                chunks.add(nals[i])
            }

            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 3000
            var outputFrames = 0
            var nextPtsUs = 0L
            var chunkIdx = 0
            while (System.currentTimeMillis() < deadline && outputFrames == 0) {
                if (chunkIdx < chunks.size) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx) ?: continue
                        val (from, to) = chunks[chunkIdx++]
                        buf.clear()
                        buf.put(data, from, to - from)
                        val flags = if (chunkIdx == 1) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                        codec.queueInputBuffer(inIdx, 0, to - from, nextPtsUs, flags)
                        nextPtsUs += 33_000
                    }
                }
                val oIdx = codec.dequeueOutputBuffer(info, 50_000)
                if (oIdx >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) outputFrames++
                    codec.releaseOutputBuffer(oIdx, false)
                }
            }
            return outputFrames > 0
        } catch (e: Exception) {
            Timber.w(e, "mirror decoder warmup probe failed")
            return false
        } finally {
            try { codec?.stop() } catch (e: Exception) { Timber.v(e, "warmup stop") }
            try { codec?.release() } catch (e: Exception) { Timber.v(e, "warmup release") }
            surface?.release()
            st?.release()
        }
    }

    /** Offsets of every AnnexB NAL (start-code exclusive) in [data]. */
    private fun annexBNals(data: ByteArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        var i = 0
        while (i < data.size) {
            val sc = when {
                i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> i + 4
                i + 2 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte() -> i + 3
                else -> -1
            }
            if (sc >= 0) {
                if (start >= 0) out.add(start to i)
                start = sc
                i = sc
            } else {
                i++
            }
        }
        if (start >= 0) out.add(start to data.size)
        return out
    }
}
