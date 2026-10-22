package com.kbtv.caster.e2e

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device decoder A/B rig born from the 2026-10-20 field night: the real
 * iPhone mirror dump (files/trace/mirror-dump.h264, proven valid by ffmpeg
 * off-device) is fed to the TV's own decoders under the format/feed variants
 * field receivers distinguish — csd-0 blob vs split, whole-AU vs slices,
 * vendor low-latency keys — to find the combination this MStar firmware
 * actually decodes. Requires a fresh dump on the device (connect a mirror
 * session first); run via am instrument on the TV.
 */
@RunWith(AndroidJUnit4::class)
class DecoderAbTest {

    private data class Nal(val offset: Int, val type: Int, val end: Int)

    private fun parseNals(d: ByteArray): List<Nal> {
        val out = ArrayList<Nal>()
        var i = 0
        while (i + 3 < d.size) {
            if (d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 1.toByte()) {
                val start = if (i > 0 && d[i - 1] == 0.toByte()) i - 1 else i
                val type = d[i + 3].toInt() and 0x1F
                if (out.isNotEmpty()) out[out.size - 1] = Nal(out[out.size - 1].offset, out[out.size - 1].type, start)
                out.add(Nal(start, type, d.size))
                i += 3
            } else i++
        }
        return out
    }

    private fun runVariant(name: String, dump: ByteArray, nals: List<Nal>, codecName: String,
                           blobCsd: Boolean, sliceOnly: Boolean, lowLatency: Boolean,
                           surface: Surface?, hugePts: Boolean = false,
                           llMode: String = "all"): Int {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (n in nals) {
            val bytes = dump.copyOfRange(n.offset, n.end)
            if (n.type == 7 && sps == null) sps = bytes
            if (n.type == 8 && pps == null) pps = bytes
        }
        if (sps == null || pps == null) return -1
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 500, 1080).apply {
            if (blobCsd) {
                val blob = sps!! + pps!!
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(blob))
            } else {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps!!))
                setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps!!))
            }
            if (lowLatency) {
                // Field evidence: commercial receivers treat low-latency
                // keys as per-device switches — try subsets: the MStar
                // firmware may reject surface+lowlatency combos while
                // accepting surface alone.
                when (llMode) {
                    "all" -> {
                        setInteger("low-latency", 1)
                        setInteger("lowlatency", 1)
                        setInteger("min-latency", 1)
                        setInteger("vdec-lowlatency", 1)
                    }
                    "min" -> setInteger("min-latency", 1)
                    "vdec" -> setInteger("vdec-lowlatency", 1)
                    "lowlat" -> setInteger("lowlatency", 1)
                }
            }
        }
        val codec = try {
            MediaCodec.createByCodecName(codecName).also {
                it.configure(format, surface, null, 0)
                it.start()
            }
        } catch (e: Exception) {
            android.util.Log.i("DecoderAb", "$name configure/start failed: ${e.message}")
            return -2
        }
        var decoded = 0
        var fed = 0
        var fmtLogged = false
        val info = MediaCodec.BufferInfo()
        try {
            for (n in nals) {
                val isSlice = n.type == 1 || n.type == 5
                if (sliceOnly && !isSlice) continue
                if (!sliceOnly && !(isSlice || n.type == 6 || n.type == 7 || n.type == 8)) continue
                val bytes = dump.copyOfRange(n.offset, n.end)
                val idx = codec.dequeueInputBuffer(20_000)
                if (idx < 0) continue
                codec.getInputBuffer(idx)!!.put(bytes)
                codec.queueInputBuffer(idx, 0, bytes.size,
                    if (hugePts) 1_770_000_000_000_000L + fed * 33_333L else fed * 33_333L, 0)
                fed++
                var drain = 0
                while (drain < 4) {
                    val ob = codec.dequeueOutputBuffer(info, 0)
                    if (ob >= 0) {
                        if (!fmtLogged && surface == null) {
                            fmtLogged = true
                            val img = codec.getOutputImage(ob)
                            val of = codec.outputFormat
                            android.util.Log.i("DecoderAb",
                                "OUTFMT: ${of.getInteger(android.media.MediaFormat.KEY_COLOR_FORMAT)} " +
                                "${of.getInteger(android.media.MediaFormat.KEY_WIDTH)}x${of.getInteger(android.media.MediaFormat.KEY_HEIGHT)} " +
                                "img=${img?.format} crop=${img?.cropRect} planes=${img?.planes?.map { it.rowStride to it.pixelStride }}")
                            img?.close()
                        }
                        codec.releaseOutputBuffer(ob, false); decoded++
                    }
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
            android.util.Log.i("DecoderAb", "$name runtime: ${e.message}")
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
        android.util.Log.i("DecoderAb", "$name [$codecName] fed=$fed decoded=$decoded")
        return decoded
    }

    /**
     * The winning recipe (nosurf+blob+aus+ll, 302/303 frames) fed with the
     * app's real PTS scale (NTP epoch, ~1.77e15 us) vs the rig's small PTS:
     * isolates presentation-time magnitude as the last untested delta between
     * the successful rig decode and the zero-output in-app decode.
     */
    @Test
    fun ptsSensitivity() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dumpFile = java.io.File(ctx.filesDir, "trace/mirror-dump.h264")
        assertTrue("mirror dump missing — connect a mirror session first", dumpFile.isFile)
        val dump = dumpFile.readBytes()
        val nals = parseNals(dump)
        val small = runVariant("pts-small", dump, nals, "OMX.MS.AVC.Decoder",
            blobCsd = true, sliceOnly = false, lowLatency = true, surface = null, hugePts = false)
        val huge = runVariant("pts-huge", dump, nals, "OMX.MS.AVC.Decoder",
            blobCsd = true, sliceOnly = false, lowLatency = true, surface = null, hugePts = true)
        android.util.Log.i("DecoderAb", "PTS-VERDICT small=$small huge=$huge")
        assertTrue("baseline pts-small regressed (decoded=$small)", small >= 100)
    }

    /**
     * Surface-mode recipe hunt informed by field observation of commercial
     * receivers: they switch low-latency keys and parameter-set feeding
     * per device. Matrix: {imgsurf, txtsurf} x
     * {no ll keys, all, min, vdec, lowlat} on the MStar decoder — the live
     * app only ever tried surface+all, which rejects every AU.
     */
    @Test
    fun surfaceModeRecipe() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dumpFile = java.io.File(ctx.filesDir, "trace/mirror-dump.h264")
        assertTrue("mirror dump missing", dumpFile.isFile)
        val dump = dumpFile.readBytes()
        val nals = parseNals(dump)
        val imageReader = android.media.ImageReader.newInstance(500, 1080,
            android.graphics.ImageFormat.YV12, 4)
        val texSurf = android.view.Surface(android.graphics.SurfaceTexture(0))
        var anyDecoded = 0 to ""
        for ((sname, surf) in listOf("imgsurf" to imageReader.surface, "txtsurf" to texSurf)) {
            for (llMode in listOf("none", "all", "min", "vdec", "lowlat")) {
                val n = runVariant("s-$sname-$llMode", dump, nals, "OMX.MS.AVC.Decoder",
                    blobCsd = true, sliceOnly = false, lowLatency = llMode != "none",
                    surface = surf, llMode = llMode)
                if (n > anyDecoded.first) anyDecoded = n to "$sname-$llMode"
                imageReader.acquireLatestImage()?.close()
            }
        }
        imageReader.close()
        texSurf.release()
        android.util.Log.i("DecoderAb", "SURFACE-RECIPE best=$anyDecoded")
        // informational: no hard assert — a null result just confirms buffer mode
    }

    @Test
    fun findWorkingRecipe() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dumpFile = java.io.File(ctx.filesDir, "trace/mirror-dump.h264")
        assertTrue("mirror dump missing — connect a mirror session first", dumpFile.isFile)
        val dump = dumpFile.readBytes()
        val nals = parseNals(dump)
        android.util.Log.i("DecoderAb", "dump=${dump.size}B nals=${nals.size}")

        val imageReader = android.media.ImageReader.newInstance(500, 1080,
            android.graphics.ImageFormat.YV12, 4)
        var best = 0 to ""
        val texSurf = android.view.Surface(android.graphics.SurfaceTexture(0))
        val surfaces = listOf<Pair<String, Surface?>>(
            "imgsurf" to imageReader.surface,
            "txtsurf" to texSurf,
            "nosurf" to null,
        )
        for (codecName in listOf("OMX.MS.AVC.Decoder", "OMX.google.h264.decoder")) {
            for ((sname, surf) in surfaces) {
                for (blob in listOf(true, false)) {
                    for (slices in listOf(false, true)) {
                        for (ll in listOf(false, true)) {
                            val tag = "$sname-${if (blob) "blob" else "split"}-${if (slices) "slices" else "aus"}${if (ll) "-ll" else ""}"
                            val n = runVariant(tag, dump, nals, codecName, blob, slices, ll, surf)
                            if (n > best.first) best = n to "$codecName $tag"
                            imageReader.acquireLatestImage()?.close()
                        }
                    }
                }
            }
        }
        imageReader.close()
        texSurf.release()
        android.util.Log.i("DecoderAb", "BEST: ${best.second} -> ${best.first} frames")
        assertTrue("no variant decoded >=100 frames (best ${best.second}: ${best.first})", best.first >= 100)
    }
}
