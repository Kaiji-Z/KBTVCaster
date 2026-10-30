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

    /** True while a mirror session renders frames. */
    val rendering: LiveData<Boolean> = _rendering

    private val _sessionActive = MutableLiveData(false)

    /**
     * True while a mirror session is up. The UI must key surface visibility
     * off this, not [rendering]: the first frame can only render after the
     * SurfaceView is visible and its surface reaches us, so gating visibility
     * on the first frame deadlocks the pipeline.
     */
    val sessionActive: LiveData<Boolean> = _sessionActive

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
    private var surfaceModeStarves = 0
    private var lastDecoderError: Exception? = null

    // render-side only (feeder thread)
    // volatile: written by the feeder thread, read cross-thread by tests
    // (FlipStreamReproTest polls it; without volatile ARM caches it stale)
    @Volatile private var renderedFrames = 0L

    /** Test/trace read-only view of the decode-output counter. */
    val renderedFrameCount: Long get() = renderedFrames
    private var droppedFrames = 0L
    private var h265Dropped = 0L
    private val latencyMicros = ArrayList<Long>()
    private val queuedAtNs = HashMap<Long, Long>()
    private var lastPtsUs = 0L

    private class PendingNal(val data: ByteArray, val ptsUs: Long, val slice: Boolean)

    // Debug-only raw stream capture (set by the service when debuggable):
    // the first AUs of a session land in <dir>/mirror-dump.h264 so a failed
    // field round still yields the exact bytes for offline ffprobe/decode.
    var debugDumpDir: java.io.File? = null
    private var dumpStream: java.io.FileOutputStream? = null
    private var dumpBytes = 0L

    // MStar tier (2026-10-20 field night): on the Android 6 field TV's
    // OMX.MS.AVC.Decoder, a directly attached surface starts but rejects
    // every access unit; with NO surface the same bytes decode perfectly
    // (DecoderAbTest: 302/303 frames). Decode to buffers there and blit
    // the planar YUV through RenderScript to the display surface.
    private var bufferMode = false
    private var glDisplay: MirrorGlDisplay? = null
    private var rs: android.renderscript.RenderScript? = null
    private var yuvScript: android.renderscript.ScriptIntrinsicYuvToRGB? = null
    private var yuvIn: android.renderscript.Allocation? = null
    private var rgbOut: android.renderscript.Allocation? = null
    private var frameBitmap: android.graphics.Bitmap? = null
    private var nv21: ByteArray? = null
    private var blitMatrix: android.graphics.Matrix? = null
    private var blitPaint: android.graphics.Paint? = null
    private var appContext: android.content.Context? = null

    /** Must be called once from the service before any session (RenderScript needs a context). */
    fun attachContext(context: android.content.Context) {
        appContext = context.applicationContext
    }

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
        // Full join: the feeder can be inside a ~1.4s blit; releasing the
        // codec under it segfaulted the process (tombstone 2026-10-20).
        // 5s is a sanity bound past any bounded feeder operation.
        oldFeeder?.let { joinFeeder(it) }
        teardownCodec(oldCodec)
        synchronized(lock) { maybeStartCodecLocked() }
    }

    fun setResolution(w: Int, h: Int) {
        val (oldCodec, oldFeeder) = synchronized(lock) {
            if (w <= 0 || h <= 0 || (w == width && h == height)) null to null
            else {
                width = w
                height = h
                // new configuration: fresh restart budget — resolution flips
                // (portrait <-> landscape video) must not exhaust the budget
                // set by earlier unrelated stalls
                restartsAllowed = MAX_RESTARTS
                glDisplay?.setDecodeBufferSize(w, h)
                // The next stream carries new parameter sets: drop the stale
                // ones or the restarted codec configures with the previous
                // resolution's SPS/PPS (landscape-video switch 500x1080 ->
                // 1920x1080 and back).
                csd0 = null
                csd1 = null
                stopCodecLocked() to feeder.also { feeder = null }
            }
        }
        oldFeeder?.let { joinFeeder(it) }
        teardownCodec(oldCodec)
        synchronized(lock) { maybeStartCodecLocked() }
    }

    fun onSession(running: Boolean) {
        _sessionActive.postValue(running)
        if (!running) closeDump()
        val (oldCodec, oldFeeder) = synchronized(lock) {
            sessionRunning = running
            stopCodecLocked() to feeder.also { feeder = null }
        }
        if (oldFeeder != null && oldFeeder !== Thread.currentThread()) joinFeeder(oldFeeder)
        teardownCodec(oldCodec)
        if (!running) {
            _rendering.postValue(false)
            cooldownTimer?.cancel(); cooldownTimer = null
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
                surfaceModeStarves = 0
                lastDecoderError = null
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
        // Real senders aggregate parameter sets and slices into one callback
        // buffer (e.g. SPS+PPS+SEI+IDR per keyframe). DecoderAbTest on the
        // field TV (2026-10-20): the MStar firmware decodes 302/303 frames
        // with one input buffer PER NAL (all types incl. SPS/PPS/SEI), but
        // zero when slices arrive concatenated or alone — so split and queue
        // each sub-NAL; csd is harvested from the first SPS/PPS for the
        // MediaFormat blob.
        val nals = splitAnnexBNals(data) ?: listOf(data)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in nals) {
            when (nalTypeOf(nal)) {
                NAL_SPS -> sps = nal
                NAL_PPS -> pps = nal
            }
        }
        if (sps != null || pps != null) {
            synchronized(lock) {
                if (sps != null && csd0 == null) csd0 = sps
                if (pps != null && csd1 == null) csd1 = pps
            }
        }
        dumpAu(data)

        synchronized(lock) {
            if (!sessionRunning || surface == null) {
                droppedFrames++
                return
            }
            for (nal in nals) {
                // Feed only the NAL types the field MStar accepts. The
                // iPhone mirror stream opens with an AUD delimiter (type 9)
                // and other housekeeping units; DecoderAbTest proved one such
                // unit in an input buffer puts the decoder into a reject-all
                // state (per-NAL "Error Code" spam, zero output), while the
                // same dump filtered to {1,5,6,7,8} decodes 4961/4964.
                when (nalTypeOf(nal)) {
                    NAL_SLICE, NAL_IDR, 6, NAL_SPS, NAL_PPS -> {}
                    else -> continue
                }
                var ptsUs = if (ntpTimeNs > 0) ntpTimeNs / 1000 else 0L
                if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1
                lastPtsUs = ptsUs
                if (nalTypeOf(nal) == NAL_SLICE || nalTypeOf(nal) == NAL_IDR) {
                    queuedAtNs[ptsUs] = System.nanoTime()
                }
                pending.addLast(PendingNal(nal, ptsUs, true))
            }
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
        // output-stall watchdog: the MStar firmware occasionally stops
        // producing output while still accepting input (2026-10-25 field
        // session: input flowed 24s at 0.5fps output, then the sender gave
        // up). Restarting the codec on stall recovers within the window.
        var lastOutputNs = System.nanoTime()
        var fedSinceOutput = 0L
        while (true) {
            var codecRef: MediaCodec
            val nal: PendingNal?
            synchronized(lock) {
                if (feeder !== Thread.currentThread()) return
                codecRef = codec ?: return
                if (statWinStart == 0L) statWinStart = System.nanoTime()
                val depth = pending.size
                statDepthSum += depth
                if (depth > statDepthMax) statDepthMax = depth
                statDepthN++
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
                    val waitT0 = System.nanoTime()
                    val inIdx = codecRef.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val waited = System.nanoTime() - waitT0
                        statInWaitSum += waited
                        if (waited > statInWaitMax) statInWaitMax = waited
                        statInWaitN++
                        statFed++
                        val buf = codecRef.getInputBuffer(inIdx)!!
                        buf.clear()
                        buf.put(nal.data)
                        codecRef.queueInputBuffer(inIdx, 0, nal.data.size, nal.ptsUs, 0)
                        if (fedProbe < 5) {
                            fedProbe++
                            android.util.Log.i("MirrorProbe",
                                "feed#$fedProbe size=${nal.data.size} pts=${nal.ptsUs} " +
                                    "hex=${nal.data.take(16).joinToString("") { "%02x".format(it) }}")
                        }
                        starveSinceNs = 0
                        fedSinceOutput++
                    } else {
                        // The MStar codec hands out input buffers only ~40ms
                        // after start(); a dequeue timeout here must NOT
                        // discard the NAL — losing the IDR that way left every
                        // later P-slice unreferenced (per-NAL MS_OMX rejects,
                        // black screen). Put it back at the queue head.
                        synchronized(lock) { pending.addFirst(nal) }
                        if (starveSinceNs == 0L) starveSinceNs = System.nanoTime()
                        else if (System.nanoTime() - starveSinceNs > STARVE_ABORT_NS) {
                            throw IllegalStateException(
                                "decoder offered no input buffer for ${STARVE_ABORT_NS / 1_000_000}ms",
                            )
                        }
                    }
                }
                val framesBefore = renderedFrames
                drainOutputs(codecRef, info)
                if (renderedFrames > framesBefore) {
                    lastOutputNs = System.nanoTime()
                    fedSinceOutput = 0
                    // a healthy decode refreshes the restart budget: the cap
                    // exists to stop endless jammed cycles, not to kill
                    // sessions that recovered
                    restartsAllowed = MAX_RESTARTS
                } else if (fedSinceOutput > STALL_FED_THRESHOLD &&
                    System.nanoTime() - lastOutputNs > STALL_RESTART_NS
                ) {
                    throw IllegalStateException(
                        "decoder output stalled: $fedSinceOutput NALs fed, none decoded in " +
                            "${(System.nanoTime() - lastOutputNs) / 1_000_000}ms",
                    )
                }
            } catch (e: Exception) {
                lastDecoderError = e
                Timber.e(e, "mirror decoder error after $renderedFrames frames")
                handleCodecError()
                return
            }
        }
    }

    private var drainProbe = 0
    private var fedProbe = 0
    private var skippedBlits = 0L
    // feeder pacing diagnostics (feeder thread only): explains standing-queue
    // latency. deep pending with fast input-wait means output pacing; slow
    // input-wait means the codec throttles feeds
    private var statDepthSum = 0L
    private var statDepthMax = 0
    private var statDepthN = 0L
    private var statInWaitSum = 0L
    private var statInWaitMax = 0L
    private var statInWaitN = 0L
    private var statFed = 0L
    private var statRendered = 0L
    private var statWinStart = 0L
    private fun drainOutputs(codecRef: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val oIdx = codecRef.dequeueOutputBuffer(info, 0)
            if (drainProbe < 12) {
                drainProbe++
                Timber.i("mirror drain probe #$drainProbe: oIdx=$oIdx flags=${info.flags} size=${info.size} bufferMode=$bufferMode")
            }
            when {
                oIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                oIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // In-band resolution switches (portrait home -> landscape
                    // video) surface here with the stream's true dimensions
                    // from SPS: drive the letterbox from these, not from the
                    // sender's flapping telemetry.
                    try {
                        val fmt = codecRef.outputFormat
                        var fw = fmt.getInteger(MediaFormat.KEY_WIDTH)
                        var fh = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                        if (fmt.containsKey(MediaFormat.KEY_CROP_LEFT)) {
                            fw = fmt.getInteger(MediaFormat.KEY_CROP_RIGHT) - fmt.getInteger(MediaFormat.KEY_CROP_LEFT) + 1
                            fh = fmt.getInteger(MediaFormat.KEY_CROP_BOTTOM) - fmt.getInteger(MediaFormat.KEY_CROP_TOP) + 1
                        }
                        // mid-flip the MStar firmware reports degenerate crops
                        // (e.g. 1x9): a tiny letterbox viewport renders pure
                        // black at 60fps. Ignore implausible dims — the next
                        // format change with real SPS dims will land.
                        if (fw >= 64 && fh >= 64) glDisplay?.setDecodeBufferSize(fw, fh)
                    } catch (e: Exception) {
                        Timber.w(e, "mirror output format dims")
                    }
                }
                oIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 ->
                    codecRef.releaseOutputBuffer(oIdx, false)
                else -> {
                    queuedAtNs.remove(info.presentationTimeUs)?.let { queuedNs ->
                        latencyMicros.add((System.nanoTime() - queuedNs) / 1000)
                    }
                    if (bufferMode) {
                        val s = synchronized(lock) { surface }
                        // Catch-up: while the pending backlog is deep, decode
                        // without displaying. The MStar decoder runs several
                        // times faster than the RenderScript blit, so this
                        // drains the queue in a few frames WITHOUT dropping
                        // NALs — dropped P-slices broke reference chains and
                        // showed as mosaic blocks on the live screen.
                        val backlog = synchronized(lock) { pending.size }
                        if (backlog > CATCHUP_PENDING_NALS) {
                            skippedBlits++
                        }
                        val img = if (backlog > CATCHUP_PENDING_NALS) null
                                  else codecRef.getOutputImage(oIdx)
                        if (img != null && s != null) {
                            try {
                                blitImage(img, s)
                            } catch (e: Exception) {
                                Timber.w(e, "mirror blit failed")
                            }
                            // No img.close(): on this OMX stack closing the
                            // codec Image returns the buffer, and the
                            // releaseOutputBuffer below would return it a
                            // second time — the server then frees the CMA
                            // page out from under the next getOutputImage
                            // (feeder SIGSEGV, tombstone 2026-10-20).
                        }
                        codecRef.releaseOutputBuffer(oIdx, false)
                    } else {
                        codecRef.releaseOutputBuffer(oIdx, true)
                    }
                    renderedFrames++
                    statRendered++
                    if (renderedFrames == 1L) {
                        Timber.i("first mirror frame rendered (${width}x${height}, bufferMode=$bufferMode)")
                        _rendering.postValue(true)
                        TraceRecorder.log("mirror_first_frame", "w" to width, "h" to height, "bufferMode" to bufferMode)
                    }
                    if (renderedFrames % STATS_EVERY == 0L) {
                        val winS = (System.nanoTime() - statWinStart) / 1e9
                        val fedPerS = if (winS > 0) statFed / winS else 0.0
                        val outPerS = if (winS > 0) statRendered / winS else 0.0
                        Timber.i(
                            "mirror feeder: depth avg=%.1f max=%d | inWait avg=%.1f max=%.1f ms | fed=%.1f/s out=%.1f/s skip=%d",
                            if (statDepthN > 0) statDepthSum.toDouble() / statDepthN else 0.0,
                            statDepthMax,
                            if (statInWaitN > 0) statInWaitSum / 1e6 / statInWaitN else 0.0,
                            statInWaitMax / 1e6,
                            fedPerS,
                            outPerS,
                            skippedBlits,
                        )
                        statDepthSum = 0; statDepthMax = 0; statDepthN = 0
                        statInWaitSum = 0; statInWaitMax = 0; statInWaitN = 0
                        statFed = 0; statRendered = 0; statWinStart = System.nanoTime()
                        val p95Now = if (latencyMicros.isEmpty()) 0L else {
                            val sortedNow = latencyMicros.sorted()
                            sortedNow[sortedNow.size * 95 / 100]
                        }
                        TraceRecorder.log(
                            "mirror_render_tick",
                            "frames" to renderedFrames,
                            "dropped" to droppedFrames,
                            "skippedBlits" to skippedBlits,
                            "p95Micros" to p95Now,
                        )
                    }
                }
            }
        }
    }

    private fun handleCodecError() {
        // Degrade: once the firmware's surface-mode jammed this session,
        // restarts into the same mode burn seconds of black each time. The
        // buffer+blit tier is the proven-working path on this TV.
        var starved = false
        // GL first: this firmware's CPU blit tier never produces output
        // (field 2026-10-26: healthy upstream, black screen, skippedBlits
        // climbing), so surface failures retry GL twice before the blit
        // fallback — a start or resolution-flip stall must recover in GL.
        if (!bufferMode && renderedFrames == 0L) {
            surfaceModeStarves++
            starved = surfaceModeStarves >= 2
        }
        val (oldCodec, oldFeeder) = synchronized(lock) {
            if (starved) {
                bufferMode = true
                Timber.w("surface mode jammed; falling back to buffer+blit for this session")
            }
            stopCodecLocked() to feeder.also { feeder = null }
        }
        oldFeeder?.let { if (it !== Thread.currentThread()) it.join(300) }
        teardownCodec(oldCodec)
        if (restartsAllowed > 0) {
            restartsAllowed--
            Timber.w("restarting mirror decoder ($restartsAllowed restarts left)")
            synchronized(lock) {
                queuedAtNs.clear()
                maybeStartCodecLocked()
            }
        } else {
            // Budget exhausted. The restart churn itself deepens the MStar
            // VDEC wedge (its buffer pool drains only minutes after the
            // churn stops — field 2026-10-26: cold boot + first session
            // still refused). Back off: probe with ONE restart per minute.
            cooldownTimer?.cancel()
            cooldownTimer = java.util.Timer("mirror-decoder-cooldown", true).apply {
                schedule(object : java.util.TimerTask() {
                    override fun run() {
                        synchronized(lock) {
                            if (sessionRunning && codec == null && csd0 != null) {
                                Timber.w("decoder cooldown elapsed — probing restart")
                                restartsAllowed = 1
                                queuedAtNs.clear()
                                maybeStartCodecLocked()
                            }
                        }
                    }
                }, 60_000)
            }
        }
    }

    private var cooldownTimer: java.util.Timer? = null

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
                // Single concatenated csd-0 blob (SPS+PPS), no csd-1: the
                // field TV's MStar OMX only parses the first parameter-set
                // buffer, so a PPS in csd-1 leaves every slice NAL rejected
                // (MS_OMX_H264DEC per-frame errors, black screen). Field
                // receivers decode this exact stream on this box with csd-0
                // only — verified against a commercial receiver's logs.
                val blob = java.nio.ByteBuffer.allocate(sps.size + pps.size)
                blob.put(sps)
                blob.put(pps)
                blob.flip()
                setByteBuffer("csd-0", blob)
            }
            val hx = { b: ByteArray -> b.take(16).joinToString("") { "%02x".format(it) } }
            android.util.Log.i("MirrorProbe",
                "configure w=$width h=$height sps=${sps.size}B[${hx(sps)}] pps=${pps.size}B[${hx(pps)}]")
            val c = createDecoder(format, s)
            c.start()
            Timber.i("mirror decoder started: name=${c.name} bufferMode=$bufferMode w=$width h=$height")
            TraceRecorder.log("mirror_decoder_started", "codec" to c.name, "bufferMode" to bufferMode)
            glDisplay?.setDecodeBufferSize(width, height)
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
                val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                if (c.name.contains("OMX.MS.")) {
                    format.setInteger("low-latency", 1)
                    format.setInteger("lowlatency", 1)
                    format.setInteger("min-latency", 1)
                    format.setInteger("vdec-lowlatency", 1)
                    // GL tier (2026-10-22): the firmware rejects AUs when the
                    // DISPLAY surface is attached and the CPU blit tier died
                    // on 1920x1080; DecoderAbTest proved SurfaceTexture+these
                    // keys decode 648/649 frames. GL-forward, blit as fallback.
                    // Once surface mode starved this session, honor the
                    // degrade flag — unconditionally resetting it here made
                    // every restart re-enter the jammed mode (2026-10-25).
                    if (surfaceModeStarves == 0) {
                        bufferMode = false
                        val gd = MirrorGlDisplay(target, width, height)
                        val decodeSurface = try { gd.createDecoderSurface(2000) } catch (e: Exception) { null }
                        if (decodeSurface != null) {
                            glDisplay = gd
                            c.configure(format, decodeSurface, null, 0)
                        } else {
                            gd.release()
                            bufferMode = true
                            c.configure(format, null, null, 0)
                        }
                    } else {
                        bufferMode = true
                        c.configure(format, null, null, 0)
                    }
                } else {
                    c.configure(format, target, null, 0)
                }
                return c
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

    /**
     * MStar tier display path: planar YUV_420_888 → NV21 → RenderScript →
     * Bitmap → Canvas blit onto the SurfaceView surface, fit-scaled.
     */
    private fun blitImage(img: android.media.Image, surface: Surface) {
        val crop = img.cropRect
        val w = crop.width()
        val h = crop.height()
        if (w <= 0 || h <= 0) return
        val y = img.planes[0]
        val u = img.planes[1]
        val v = img.planes[2]
        val buf = nv21
        if (buf == null || buf.size < w * h * 3 / 2) {
            nv21 = ByteArray(w * h * 3 / 2)
        }
        val out = nv21 ?: return
        // Plane buffers on this OMX stack are read-only direct ByteBuffers
        // (.array() throws ReadOnlyBufferException) — bulk-get via duplicates.
        val yb = y.buffer.duplicate()
        // Y plane (row stride aware, cropped)
        var dst = 0
        for (row in 0 until h) {
            yb.position((crop.top + row) * y.rowStride + crop.left)
            yb.get(out, dst, w)
            dst += w
        }
        // Interleaved VU for NV21 from the Cb/Cr planes. Fast path when both
        // chroma planes are contiguous (pixelStride 1, the MStar YV12 case):
        // row-wise bulk copies instead of 270k single-byte gets.
        val uBuf = u.buffer.duplicate()
        val vBuf = v.buffer.duplicate()
        val cw = w / 2
        if (u.pixelStride == 1 && v.pixelStride == 1) {
            val vRow = ByteArray(cw)
            val uRow = ByteArray(cw)
            for (row in 0 until h / 2) {
                uBuf.position((crop.top / 2 + row) * u.rowStride + crop.left / 2)
                uBuf.get(uRow, 0, cw)
                vBuf.position((crop.top / 2 + row) * v.rowStride + crop.left / 2)
                vBuf.get(vRow, 0, cw)
                for (col in 0 until cw) {
                    out[dst++] = vRow[col]
                    out[dst++] = uRow[col]
                }
            }
        } else {
            for (row in 0 until h / 2) {
                var cu = (crop.top / 2 + row) * u.rowStride + crop.left / 2
                var cv = (crop.top / 2 + row) * v.rowStride + crop.left / 2
                for (col in 0 until w / 2) {
                    vBuf.position(cv); out[dst++] = vBuf.get(); cv += v.pixelStride
                    uBuf.position(cu); out[dst++] = uBuf.get(); cu += u.pixelStride
                }
            }
        }
        // Lazy RS pipeline
        var bitmap = frameBitmap
        if (bitmap == null || bitmap.width != w || bitmap.height != h) {
            val ctx = appContext ?: return
            bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            frameBitmap = bitmap
            rs = android.renderscript.RenderScript.create(ctx)
            yuvScript = android.renderscript.ScriptIntrinsicYuvToRGB.create(
                rs, android.renderscript.Element.YUV(rs))
            yuvIn = android.renderscript.Allocation.createSized(
                rs, android.renderscript.Element.U8(rs), out.size)
            rgbOut = android.renderscript.Allocation.createFromBitmap(rs, bitmap)
        }
        yuvIn!!.copyFrom(out)
        yuvScript!!.setInput(yuvIn)
        yuvScript!!.forEach(rgbOut)
        rgbOut!!.copyTo(bitmap)

        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            val scale = minOf(
                canvas.width.toFloat() / w,
                canvas.height.toFloat() / h,
            )
            val dx = (canvas.width - w * scale) / 2f
            val dy = (canvas.height - h * scale) / 2f
            if (blitMatrix == null) blitMatrix = android.graphics.Matrix()
            val m = blitMatrix!!
            m.setScale(scale, scale)
            m.postTranslate(dx, dy)
            if (blitPaint == null) {
                blitPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            }
            canvas.drawBitmap(bitmap, m, blitPaint!!)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun joinFeeder(t: Thread) {
        t.join(5_000)
        if (t.isAlive) Timber.w("mirror feeder failed to exit within 5s")
    }

    /** Release the codec first, then the GL display that consumes its output. */
    private fun teardownCodec(codec: MediaCodec?) {
        quietStop(codec)
        synchronized(lock) {
            glDisplay?.let { it.release() }
            glDisplay = null
        }
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

    /** Debug capture of the raw incoming AU stream, capped at 32MB per session. */
    private fun dumpAu(data: ByteArray) {
        val dir = debugDumpDir ?: return
        // 32MB ≈ 2min live: the 8MB cap truncated every recording exactly
        // before the fatal resolution switch (2026-10-30) — the incident
        // bytes are what the flip repro needs
        if (dumpBytes > 32L * 1024 * 1024) return
        try {
            if (dumpStream == null) {
                dumpBytes = 0
                dumpStream = java.io.FileOutputStream(java.io.File(dir, "mirror-dump.h264"))
            }
            dumpStream!!.write(data)
            dumpBytes += data.size
        } catch (e: Exception) {
            Timber.w(e, "mirror dump write failed")
            closeDump()
        }
    }

    private fun closeDump() {
        try { dumpStream?.close() } catch (_: Exception) {}
        dumpStream = null
    }

    /** NAL header type of an Annex-B unit, or -1 if it does not start with a start code. */
    private fun nalTypeOf(data: ByteArray): Int {
        var i = 0
        while (i + 1 < data.size && data[i] == 0.toByte()) i++
        if (i + 2 >= data.size || data[i] != 1.toByte()) return -1
        return data[i + 1].toInt() and 0x1F
    }

    /**
     * Splits an Annex-B buffer into its NAL units (each keeping its start
     * code). Returns null when no start code is found — a bare single NAL —
     * so the caller can pass the original buffer through unchanged.
     */
    private fun splitAnnexBNals(data: ByteArray): List<ByteArray>? {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                // Prefer the 4-byte form when a leading zero belongs to it.
                val at = if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
                if (starts.isEmpty() || at > starts.last()) starts.add(at)
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return null
        val nals = ArrayList<ByteArray>(starts.size)
        for (idx in starts.indices) {
            val from = starts[idx]
            val to = if (idx + 1 < starts.size) starts[idx + 1] else data.size
            // Sub-6-byte tails are trailing-zero artifacts, not NALs.
            if (to - from >= 6) nals.add(data.copyOfRange(from, to))
        }
        return nals
    }

    companion object {
        const val NAL_SLICE = 1
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8

        /** Hard backlog bound; catch-up drains far below this. */
        const val MAX_PENDING_NALS = 48
        /** Pending NALs beyond which the blit is skipped to drain the queue.
         * At mirror frame rates 8 held ~250ms of standing latency (live p95
         * 327ms); 2 bounds the queue wait to ~2 frame intervals. */
        const val CATCHUP_PENDING_NALS = 2
        const val MAX_RESTARTS = 6
        /** NALs fed with zero decoder output before treating it as stalled. */
        const val STALL_FED_THRESHOLD = 30L
        /** Hard ceiling on the stall window; must exceed the codec start time. */
        const val STALL_RESTART_NS = 2_000_000_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val POLL_IDLE_MS = 5L
        // 5s: the field firmware sometimes stops granting input buffers
        // outright; 20s of black before recovery was unacceptable live.
        const val STARVE_ABORT_NS = 5_000_000_000L
        const val STATS_EVERY = 100L
        val SOFTWARE_DECODERS = listOf("c2.android.avc.decoder", "OMX.google.h264.decoder")
    }
}
