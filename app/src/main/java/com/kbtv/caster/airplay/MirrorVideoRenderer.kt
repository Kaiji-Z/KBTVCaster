package com.kbtv.caster.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.kbtv.caster.trace.TraceRecorder
import timber.log.Timber
import java.util.ArrayDeque

/**
 * Decodes the AirPlay mirror stream to a Surface and renders each frame as
 * soon as it is decoded. The native side delivers one Annex-B NAL per
 * onFrame() call (SPS/PPS first, then slices), plus the coded resolution via
 * onVideoSize().
 *
 * Carries over the field-hardened recipe from the B5 verification
 * (MirrorDecodePathTest): parameter sets go into the MediaFormat as csd-0/1
 * because OMX-era decoders (the Android 6 field TV) stall on in-band-only
 * streams; decoder selection is byType on real devices with a software
 * fallback list on emulators; a bounded pending queue drops stale NALs so a
 * stalled decoder cannot grow mirror latency without bound.
 *
 * The codec lives per mirror session; the Surface survives across sessions
 * and is re-attached by the UI whenever its SurfaceView is (re)created.
 */
class MirrorVideoRenderer {

    private val _rendering = MutableLiveData(false)

    /** True while a mirror session renders frames; the UI shows the mirror surface. */
    val rendering: LiveData<Boolean> = _rendering

    private val lock = Object()

    // configuration, guarded by lock
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var sessionRunning = false
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null
    private val pending = ArrayDeque<PendingNal>()
    private var codec: MediaCodec? = null
    private var feeder: Thread? = null
    private var restartsAllowed = 0

    // render-side only (feeder thread)
    private var renderedFrames = 0L
    private var droppedFrames = 0L
    private var h265Dropped = 0L
    private val latencyMicros = ArrayList<Long>()
    private val queuedAtNs = HashMap<Long, Long>()
    private var lastPtsUs = 0L

    private class PendingNal(val data: ByteArray, val ptsUs: Long, val slice: Boolean)

    // ===== configuration (UI thread / service) =====

    fun setSurface(newSurface: Surface?) {
        val (oldCodec, oldFeeder) = synchronized(lock) {
            if (newSurface == surface) null to null
            else {
                surface = newSurface
                // The codec renders into the Surface it was configured with.
                stopCodecLocked() to feeder.also { feeder = null }
            }
        }
        oldFeeder?.join(500)
        quietStop(oldCodec)
        synchronized(lock) { maybeStartCodecLocked() }
    }

    fun setResolution(w: Int, h: Int) {
        val (oldCodec, oldFeeder) = synchronized(lock) {
            if (w <= 0 || h <= 0 || (w == width && h == height)) null to null
            else {
                width = w
                height = h
                stopCodecLocked() to feeder.also { feeder = null }
            }
        }
        oldFeeder?.join(500)
        quietStop(oldCodec)
        synchronized(lock) { maybeStartCodecLocked() }
    }

    fun onSession(running: Boolean) {
        val (oldCodec, oldFeeder) = synchronized(lock) {
            sessionRunning = running
            stopCodecLocked() to feeder.also { feeder = null }
        }
        if (oldFeeder != null && oldFeeder !== Thread.currentThread()) oldFeeder.join(500)
        quietStop(oldCodec)
        if (!running) {
            _rendering.postValue(false)
            // stats only after the feeder thread is dead (it owns latencyMicros)
            synchronized(lock) {
                pending.clear()
                logStatsLocked("mirror_session_end")
            }
        } else {
            synchronized(lock) {
                csd0 = null
                csd1 = null
                restartsAllowed = MAX_RESTARTS
                maybeStartCodecLocked()
            }
        }
    }

    fun release() {
        onSession(false)
        synchronized(lock) { surface = null }
    }

    // ===== frame ingestion (native callback threads) =====

    fun onFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        if (isH265) {
            // Not advertised (TXT feature bit 42 stays off), so clients send
            // H.264; count defensively in case one shows up anyway.
            h265Dropped++
            if (h265Dropped == 1L) Timber.w("H.265 mirror frame dropped (not enabled)")
            return
        }
        val nalType = nalTypeOf(data)
        if (nalType == NAL_SPS || nalType == NAL_PPS) {
            synchronized(lock) {
                if (nalType == NAL_SPS && csd0 == null) csd0 = data.copyOf()
                if (nalType == NAL_PPS && csd1 == null) csd1 = data.copyOf()
            }
        }
        var ptsUs = if (ntpTimeNs > 0) ntpTimeNs / 1000 else 0L
        if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1
        lastPtsUs = ptsUs
        val slice = nalType == NAL_SLICE || nalType == NAL_IDR
        if (slice) queuedAtNs[ptsUs] = System.nanoTime()

        synchronized(lock) {
            if (!sessionRunning || surface == null) {
                droppedFrames++
                queuedAtNs.remove(ptsUs)
                return
            }
            pending.addLast(PendingNal(data.copyOf(), ptsUs, slice))
            while (pending.size > MAX_PENDING_NALS) {
                val stale = pending.pollFirst() ?: break
                droppedFrames++
                queuedAtNs.remove(stale.ptsUs)
            }
            maybeStartCodecLocked()
            if (codec != null) {
                lock.notifyAll()
            }
        }
    }

    // ===== feeder =====

    private fun feederLoop() {
        val info = MediaCodec.BufferInfo()
        var starveSinceNs = 0L
        while (true) {
            var codecRef: MediaCodec
            val nal: PendingNal?
            synchronized(lock) {
                if (feeder !== Thread.currentThread()) return
                codecRef = codec ?: return
                if (pending.isEmpty()) {
                    // Short idle poll: outputs must be drained promptly even
                    // while no input is pending, or decoded frames pile up
                    // behind the poll interval (measured ~7 frames at 50ms).
                    lock.wait(POLL_IDLE_MS)
                }
                nal = pending.pollFirst()
            }
            try {
                if (nal != null) {
                    val inIdx = codecRef.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codecRef.getInputBuffer(inIdx)!!
                        buf.clear()
                        buf.put(nal.data)
                        codecRef.queueInputBuffer(inIdx, 0, nal.data.size, nal.ptsUs, 0)
                        starveSinceNs = 0
                    } else {
                        if (starveSinceNs == 0L) starveSinceNs = System.nanoTime()
                        else if (System.nanoTime() - starveSinceNs > STARVE_ABORT_NS) {
                            throw IllegalStateException(
                                "decoder offered no input buffer for ${STARVE_ABORT_NS / 1_000_000}ms",
                            )
                        }
                    }
                }
                drainOutputs(codecRef, info)
            } catch (e: Exception) {
                Timber.e(e, "mirror decoder error after $renderedFrames frames")
                handleCodecError()
                return
            }
        }
    }

    private fun drainOutputs(codecRef: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val oIdx = codecRef.dequeueOutputBuffer(info, 0)
            when {
                oIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                oIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                oIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 ->
                    codecRef.releaseOutputBuffer(oIdx, false)
                else -> {
                    queuedAtNs.remove(info.presentationTimeUs)?.let { queuedNs ->
                        latencyMicros.add((System.nanoTime() - queuedNs) / 1000)
                    }
                    codecRef.releaseOutputBuffer(oIdx, true)
                    renderedFrames++
                    if (renderedFrames == 1L) {
                        Timber.i("first mirror frame rendered (${width}x${height})")
                        _rendering.postValue(true)
                        TraceRecorder.log("mirror_first_frame", "w" to width, "h" to height)
                    }
                    if (renderedFrames % STATS_EVERY == 0L) {
                        TraceRecorder.log(
                            "mirror_render_tick",
                            "frames" to renderedFrames,
                            "dropped" to droppedFrames,
                        )
                    }
                }
            }
        }
    }

    private fun handleCodecError() {
        val (oldCodec, oldFeeder) = synchronized(lock) {
            stopCodecLocked() to feeder.also { feeder = null }
        }
        oldFeeder?.let { if (it !== Thread.currentThread()) it.join(300) }
        quietStop(oldCodec)
        if (restartsAllowed > 0) {
            restartsAllowed--
            Timber.w("restarting mirror decoder ($restartsAllowed restarts left)")
            synchronized(lock) {
                queuedAtNs.clear()
                maybeStartCodecLocked()
            }
        }
    }

    /** Must hold [lock]. Stops accepting frames; caller joins feeder and releases the codec. */
    private fun stopCodecLocked(): MediaCodec? {
        feeder = null
        lock.notifyAll()
        return codec.also { codec = null }
    }

    /** Must hold [lock]. Starts the codec once surface, resolution and csd are known. */
    private fun maybeStartCodecLocked() {
        if (codec != null || !sessionRunning) return
        val s = surface ?: return
        if (width <= 0 || height <= 0) return
        val sps = csd0 ?: return
        val pps = csd1 ?: return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
            }
            val c = createDecoder(format, s)
            c.start()
            codec = c
            val t = Thread({ feederLoop() }, "mirror-feeder").apply { start() }
            feeder = t
            TraceRecorder.log("mirror_codec_started", "w" to width, "h" to height, "codec" to c.name)
        } catch (e: Exception) {
            Timber.e(e, "failed to start mirror decoder")
            TraceRecorder.log("mirror_codec_failed", "error" to e.message)
        }
    }

    private fun createDecoder(format: MediaFormat, target: Surface): MediaCodec {
        val emulator = android.os.Build.HARDWARE.contains("goldfish") ||
            android.os.Build.HARDWARE.contains("ranchu")
        if (!emulator) {
            try {
                return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .also { it.configure(format, target, null, 0) }
            } catch (e: Exception) {
                Timber.w(e, "byType decoder unavailable, trying software")
            }
        }
        var lastError: Exception? = null
        for (name in SOFTWARE_DECODERS) {
            try {
                return MediaCodec.createByCodecName(name)
                    .also { it.configure(format, target, null, 0) }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("no AVC decoder available", lastError)
    }

    private fun quietStop(codec: MediaCodec?) {
        codec ?: return
        try {
            codec.stop()
        } catch (e: Exception) {
            Timber.w(e, "mirror decoder stop")
        }
        try {
            codec.release()
        } catch (e: Exception) {
            Timber.w(e, "mirror decoder release")
        }
    }

    /** Must hold [lock] or be render-side; logs session decode statistics. */
    private fun logStatsLocked(event: String) {
        val frames = renderedFrames
        val p95 = if (latencyMicros.isEmpty()) 0L else {
            val sorted = latencyMicros.sorted()
            sorted[sorted.size * 95 / 100]
        }
        TraceRecorder.log(
            event,
            "frames" to frames,
            "dropped" to droppedFrames,
            "h265Dropped" to h265Dropped,
            "p95Micros" to p95,
        )
        Timber.i("mirror stats: frames=$frames dropped=$droppedFrames p95=${p95 / 1000.0}ms")
    }

    /** NAL header type of an Annex-B unit, or -1 if it does not start with a start code. */
    private fun nalTypeOf(data: ByteArray): Int {
        var i = 0
        while (i + 1 < data.size && data[i] == 0.toByte()) i++
        if (i + 2 >= data.size || data[i] != 1.toByte()) return -1
        return data[i + 1].toInt() and 0x1F
    }

    companion object {
        const val NAL_SLICE = 1
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8

        /** ~2s of mirror NALs at 60fps plus parameter sets. */
        const val MAX_PENDING_NALS = 128
        const val MAX_RESTARTS = 1
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val POLL_IDLE_MS = 5L
        const val STARVE_ABORT_NS = 20_000_000_000L
        const val STATS_EVERY = 100L
        val SOFTWARE_DECODERS = listOf("c2.android.avc.decoder", "OMX.google.h264.decoder")
    }
}
