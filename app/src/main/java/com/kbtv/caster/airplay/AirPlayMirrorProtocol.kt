package com.kbtv.caster.airplay

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.kbtv.caster.service.protocol.CastProtocol
import com.kbtv.caster.trace.TraceRecorder
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import timber.log.Timber
import java.net.NetworkInterface
import java.security.MessageDigest

/**
 * AirPlay receiving protocol on the vendored UxPlay native kernel.
 *
 * The native side runs the full AirPlay stack (pairing, /stream mirroring,
 * HLS video push, RTSP audio) and publishes its own TXT records — including
 * the mirroring features bits iOS screens for the "Screen Mirroring" list.
 * Discovery is registered twice deliberately: via NsdManager (reliable on
 * modern Android) and via our own wire-level responder below API 24, where
 * the platform mDNS daemon goes silent (measured on the Android 6 field TV).
 *
 * Replaces the Kotlin-only AirPlayProtocol URL-push surface under the same
 * kbtv.flags.airplay gate; URL pushes arrive here as [RaopCallbackHandler.onVideoPlay]
 * and flow into the coordinator's PlaybackPlan path unchanged.
 */
class AirPlayMirrorProtocol : CastProtocol, RaopCallbackHandler, io.github.jqssun.airplay.bridge.LogListener {

    override val name: String = "AirPlayMirror"

    /** Set by the coordinator when the protocol starts; receives the mirror stream. */
    var videoOut: MirrorVideoRenderer? = null

    /**
     * Set by the coordinator; invoked with the mirror session state. The
     * coordinator brings its UI forward when a session starts — the mirror
     * renders into MainActivity's SurfaceView, which does not exist while
     * the activity is backgrounded (field TV: 1608 frames dropped, zero
     * rendered, until the UI returned).
     */
    var onMirrorSession: ((Boolean) -> Unit)? = null

    private var sink: CastProtocol.PlaybackSink? = null
    private var handle: Long = 0L
    private var nsd: NsdManager? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private var ownResponder: AirPlayMDns? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var mirrorFrames = 0L

    override fun start(context: Context, sink: CastProtocol.PlaybackSink) {
        this.sink = sink
        try {
            val appContext = context.applicationContext
            this.appContext = appContext
            registerNetworkWatch(appContext)
            val keyFile = appContext.filesDir.resolve("airplay.pem").absolutePath
            // oboe's OpenSL ES backend (pre-AAudio devices, like the Android 6
            // field TV) cannot discover native rate/burst itself; hand it the
            // AudioManager values so low-latency buffer sizing works there.
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            NativeBridge.nativeSetDefaultStreamValues(
                audioManager.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
                audioManager.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0,
            )
            // nohold=true: on iPhone disconnect the RTSP connection lingers in
            // the httpd (TEARDOWN type-110 stops the mirror but never destroys
            // the connection), so a reconnect used to get 409 Conflict. The
            // nohold path evicts known connections on the next request —
            // single-client takeover is the intended TV receiver semantics.
            handle = NativeBridge.nativeInit(this, hwAddress(appContext), SERVICE_NAME, keyFile, true, false)
            if (handle == 0L) {
                Timber.e("nativeInit failed")
                TraceRecorder.log("mirror_start_failed", "stage" to "init")
                return
            }
            val dm = appContext.resources.displayMetrics
            NativeBridge.nativeSetDisplaySize(handle, dm.widthPixels, dm.heightPixels, MAX_FPS)
            // (alac, aac): audio codec advertisement. Both on — mirrors the
            // emulator-verified configuration.
            NativeBridge.nativeSetCodecs(handle, true, true)
            NativeBridge.nativeSetLang(handle, "", "", java.util.Locale.getDefault().toLanguageTag())
            NativeBridge.nativeSetAudioEnabled(handle, true)
            NativeBridge.nativeSetPlist(handle, "maxFPS", MAX_FPS)
            // Gate raop.c's CSeq-less video path: without this the kernel drops
            // URL-push /play requests ("ignoring AirPlay video streaming
            // request"). Must run BEFORE nativeStart: the shim snapshots the
            // TXT records (incl. features bits 0/4) when registering inside
            // start, and later feature updates never reach the cached TXT.
            val traceDir = java.io.File(appContext.filesDir, "trace").apply { mkdirs() }
            NativeBridge.nativeSetHlsEnabled(handle, true)
            val port = NativeBridge.nativeStart(handle, PORT)
            if (port < 0) {
                Timber.e("nativeStart failed on port $PORT")
                TraceRecorder.log("mirror_start_failed", "stage" to "start")
                NativeBridge.nativeDestroy(handle)
                handle = 0L
                return
            }

            val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(handle).orEmpty()
            val raopTxt = NativeBridge.nativeGetRaopTxtRecords(handle).orEmpty()
            val serverName = NativeBridge.nativeGetServerName(handle) ?: SERVICE_NAME
            val raopName = NativeBridge.nativeGetRaopServiceName(handle) ?: "$SERVICE_NAME-raop"
            Timber.i("native stack up: name=$serverName port=$port airplayTxt=${airplayTxt.keys}")
            TraceRecorder.log(
                "airplay_mirror_started",
                "port" to port,
                "name" to serverName,
                "features" to airplayTxt["features"],
            )

            registerNsd(appContext, serverName, raopName, port, airplayTxt, raopTxt)
            if (android.os.Build.VERSION.SDK_INT < 24) {
                // Platform mDNS daemon is unreliable here (see AirPlayMDns).
                multicastLock = (appContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                    ?.createMulticastLock("KBTVCaster-mDNS")?.apply { setReferenceCounted(false); acquire() }
                val responder = AirPlayMDns(
                    serverName,
                    raopName,
                    port,
                    airplayTxt.map { it.key to it.value },
                    raopTxt.map { it.key to it.value },
                )
                responder.start()
                ownResponder = responder
            }

            // MStar cold-boot decoder quirk: first create-decode is refused
            // until a full cycle completes (多屏互动 warms it for itself —
            // field 2026-10-26). Warm it here so the user's first cast works.
            warmup = MirrorDecoderWarmup(
                {
                    val f = appContext.filesDir.resolve("trace/mirror-dump.h264")
                    f.takeIf { it.exists() }?.readBytes() ?: runCatching {
                        // fresh installs: bundled synthetic test stream
                        appContext.assets.open("mirror-warmup.h264").use { it.readBytes() }
                    }.getOrNull()
                },
                { mirrorUiActive },
            ).apply { start() }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start AirPlay mirror protocol")
            TraceRecorder.log("mirror_start_failed", "error" to e.message)
        }
    }

    private fun registerNsd(
        context: Context,
        serverName: String,
        raopName: String,
        port: Int,
        airplayTxt: Map<String, String>,
        raopTxt: Map<String, String>,
    ) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = nsdManager
        for ((type, instance, txt) in listOf(
            Triple("_airplay._tcp", serverName, airplayTxt),
            Triple("_raop._tcp", raopName, raopTxt),
        )) {
            val info = NsdServiceInfo().apply {
                serviceName = instance
                serviceType = type
                setPort(port)
                txt.forEach { (k, v) -> setAttribute(k, v) }
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    TraceRecorder.log("mirror_mdns_registered", "type" to type, "name" to serviceInfo.serviceName)
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Timber.e("mDNS registration failed: $type ($errorCode)")
                    TraceRecorder.log("mirror_mdns_failed", "type" to type, "code" to errorCode)
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registrations.add(listener)
        }
    }

    override fun stop() {
        networkCallback?.let { cb ->
            appContext?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                ?.let { it as? android.net.ConnectivityManager }?.unregisterNetworkCallback(cb)
        }
        networkCallback = null
        synchronized(restartLock) { restartPending?.let { restartHandler.removeCallbacks(it) } }
        appContext = null
        warmup?.cancel()
        warmup = null
        registrations.forEach {
            try {
                nsd?.unregisterService(it)
            } catch (e: Exception) {
                Timber.w(e, "mDNS unregister")
            }
        }
        registrations.clear()
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Timber.w(e, "MulticastLock release")
        }
        multicastLock = null
        ownResponder?.stop()
        ownResponder = null
        if (handle != 0L) {
            try {
                NativeBridge.nativeStop(handle)
                NativeBridge.nativeDestroy(handle)
            } catch (e: Exception) {
                Timber.w(e, "native stop")
            }
            handle = 0L
        }
        sink = null
        TraceRecorder.log("airplay_mirror_stopped")
    }

    // ===== RaopCallbackHandler: native events -> trace + coordinator =====

    @Volatile
    private var mirrorUiActive = false

    private var warmup: MirrorDecoderWarmup? = null

    // Long-uptime killer (field 2026-10-28): every native socket binds to the
    // network interface present at start; one wifi/router bounce renumbers it
    // and mDNS answers ENODEV + NTP goes unreachable — the receiver turns
    // invisible until reboot. Rebind the whole stack when a network appears.
    private var appContext: android.content.Context? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val restartLock = Any()
    private var restartPending: Runnable? = null
    private var lastRestartElapsed = 0L

    private fun registerNetworkWatch(context: android.content.Context) {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                TraceRecorder.log("mirror_network_change")
                scheduleRestart(context)
            }
        }
        networkCallback = cb
        try {
            cm.registerNetworkCallback(android.net.NetworkRequest.Builder().build(), cb)
        } catch (e: Exception) {
            Timber.w(e, "network watch register failed")
        }
    }

    private fun scheduleRestart(context: android.content.Context) {
        synchronized(restartLock) {
            restartPending?.let { restartHandler.removeCallbacks(it) }
            val r = Runnable { restartNative(context) }
            restartPending = r
            restartHandler.postDelayed(r, 5000)
        }
    }

    private fun restartNative(context: android.content.Context) {
        synchronized(restartLock) {
            restartPending = null
            val elapsed = android.os.SystemClock.elapsedRealtime()
            if (elapsed - lastRestartElapsed < 30_000) return
            lastRestartElapsed = elapsed
        }
        Timber.w("network changed — restarting AirPlay stack")
        val sink = this.sink
        try { stop() } catch (e: Exception) { Timber.w(e, "restart stop") }
        try { start(context, sink ?: return) } catch (e: Exception) {
            Timber.e(e, "restart start failed")
        }
    }

    private val restartHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Session-level callbacks arrive on NATIVE connection threads; heavy work
    // here (codec teardown, audio stop) blocked the httpd loop and wedged the
    // receiver after every session (field 2026-10-29: port listening, RTSP
    // never answered). Everything except the per-frame path runs here.
    private val callbackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mirror-callbacks").apply { isDaemon = true }
    }

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        lastVideoNs = System.nanoTime()
        pauseGrace?.cancel(); pauseGrace = null
        // Self-heal on lock-screen resume: the idle watchdog may have closed
        // the session while the sender was paused; when its stream comes back
        // without a new SETUP, reopen instead of rendering into a dead session.
        if (!mirrorUiActive && mirrorFrames > 0) {
            Timber.w("frames resumed after session close — reopening")
            onMirrorRunning(true)
        }
        videoOut?.onFrame(data, ntpTimeNs, isH265)
        mirrorFrames++
        if (mirrorFrames == 1L || mirrorFrames % RENDER_TRACE_EVERY == 0L) {
            TraceRecorder.log("mirror_frame", "n" to mirrorFrames, "h265" to isH265)
        }
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        Timber.d("onAudioFormat ct=$ct spf=$spf screen=$usingScreen")
        // Open playout first, then declare the codec (upstream order; the
        // native start is idempotent, teardown closes the stream).
        try {
            // configure MUST precede start: without it the engine never
            // builds its internals and decode() silently drops everything
            // (pkts stayed 0 through the whole 2026-10-25 field session
            // while 1793 packets were dequeued and handed over).
            NativeBridge.nativeServerAudioConfigure(handle, 0, 90, 0, false, false, true, false)
            val started = NativeBridge.nativeServerAudioStart(handle)
            NativeBridge.nativeServerAudioFormat(handle, ct, spf)
            TraceRecorder.log("mirror_audio_format", "ct" to ct, "spf" to spf,
                "screen" to usingScreen, "started" to started)
            if (!started) Timber.w("audio output failed to start")
            startAudioStatsTimer()
        } catch (e: Exception) {
            Timber.e(e, "audio start failed")
        }
    }

    // Senders sometimes vanish without TEARDOWN (abrupt Wi-Fi loss, app
    // kill): the session then stays "active" with a visible mirror surface
    // and zero frames — a permanent black screen. Auto-close after 10s idle.
    private var sessionWatchdog: java.util.Timer? = null

    @Volatile
    private var lastVideoNs = 0L  // written on the native callback thread, read by the watchdog timer

    private fun startSessionWatchdog() {
        stopSessionWatchdog()
        lastVideoNs = System.nanoTime()
        sessionWatchdog = java.util.Timer("session-watchdog", true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    // 10s, not 30s: the 2026-10-25 field session showed iOS
                    // stop-mirroring sends no TEARDOWN and no conn close —
                    // idle was the ONLY end signal, so a long timeout left a
                    // zombie "connected" screen. Lock-screen resume is covered
                    // by the self-heal reopen in onVideoData, not by waiting.
                    if (System.nanoTime() - lastVideoNs > 10_000_000_000L) {
                        Timber.w("mirror idle >10s, closing session")
                        closeMirrorSession("mirror_idle_timeout")
                    }
                }
            }, 2000, 2000)
        }
    }

    private fun stopSessionWatchdog() {
        sessionWatchdog?.cancel()
        sessionWatchdog = null
    }

    // Periodic engine counters into the trace: discriminates "no packets
    // arrive" (pkts=0 — delivery gate / NTP sync) from "decoder fails"
    // (decodeErr climbs) from "output paces" (backlog/underruns move).
    private var audioStatsTimer: java.util.Timer? = null

    private fun startAudioStatsTimer() {
        audioStatsTimer?.cancel()
        audioStatsTimer = java.util.Timer("audio-stats", true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    try {
                        val stats = NativeBridge.nativeServerAudioStats(handle)
                        TraceRecorder.log("mirror_audio_stats", "raw" to stats)
                        Timber.d("audio stats: %s", stats)
                    } catch (t: Throwable) {
                        // Throwable: an UnsatisfiedLinkError here previously
                        // killed the timer silently (stats showed 0 entries
                        // in the 2026-10-22 field session).
                        Timber.w(t, "audio stats failed")
                        cancel()
                    }
                }
            }, 5000, 5000)
        }
    }

    // The sender's telemetry alternates content and display dimensions every
    // few seconds (500x1080 <-> 1920x1080 — field 2026-10-28); each distinct
    // report used to tear the decoder down and restart it. Act only on a
    // size that repeats consecutively: real changes persist, flapping dies.
    private var lastSizeW = 0f
    private var lastSizeH = 0f

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        TraceRecorder.log("mirror_video_size", "w" to w, "h" to h)
        val act = lastSizeW == 0f || (w == lastSizeW && h == lastSizeH)
        lastSizeW = w
        lastSizeH = h
        if (act) videoOut?.setResolution(w.toInt(), h.toInt())
    }

    override fun onVolumeChange(volume: Float) {
        TraceRecorder.log("mirror_volume", "v" to volume)
    }

    override fun onClientVolume(): Float = 0f

    override fun onAudioTeardown() {
        callbackExecutor.execute { handleAudioTeardown() }
    }

    private fun handleAudioTeardown() {
        audioStatsTimer?.cancel()
        audioStatsTimer = null
        try {
            NativeBridge.nativeServerAudioStop(handle)
        } catch (e: Exception) {
            Timber.w(e, "audio stop failed")
        }
        TraceRecorder.log("mirror_audio_teardown")
    }

    override fun onConnectionInit() {
        TraceRecorder.log("mirror_conn_init")
    }

    override fun onConnectionDestroy() {
        TraceRecorder.log("mirror_conn_destroy")
        callbackExecutor.execute {
            // A closed RTSP connection ends mirroring even without TEARDOWN.
            // Safe under nohold takeover: the evicted old connection always
            // destroys before the new session's first mirror frame arrives.
            if (mirrorUiActive) closeMirrorSession("mirror_conn_close")
        }
    }

    override fun onConnectionReset(reason: Int) {
        TraceRecorder.log("mirror_conn_reset", "reason" to reason)
        // 1 = RESET_TYPE_RTP_SHUTDOWN: TEARDOWN 110, sender ended mirroring.
        if (reason == 1) callbackExecutor.execute {
            if (mirrorUiActive) closeMirrorSession("mirror_conn_reset_close")
        }
    }

    // iOS stop-mirroring sends video_pause and NOTHING else (no TEARDOWN,
    // no connection close — 2026-10-25 field session): the only fast stop
    // signal is pause followed by silence.
    private var pauseGrace: java.util.Timer? = null

    // UxPlay's native threads die one by one across reconnect cycles (field
    // 2026-10-29: httpd select loop gone after the 2nd session — RTSP
    // accepts but never answers). Commercial receivers re-init their whole
    // stack after every session; do the same: 5s after a close, rebuild.
    private fun closeMirrorSession(traceEvent: String) {
        TraceRecorder.log(traceEvent)
        onAudioTeardown()
        if (mirrorUiActive) onMirrorRunning(false)
        appContext?.let { scheduleRestart(it) }
    }

    override fun onVideoPause() {
        TraceRecorder.log("mirror_video_pause")
        callbackExecutor.execute { handleVideoPause() }
    }

    private fun handleVideoPause() {
        pauseGrace?.cancel()
        pauseGrace = java.util.Timer("mirror-pause-grace", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    if (mirrorUiActive && System.nanoTime() - lastVideoNs > 3_000_000_000L) {
                        Timber.w("video paused >3s with no frames — closing session")
                        closeMirrorSession("mirror_pause_close")
                    }
                }
            }, 3000)
        }
    }

    override fun onVideoResume() {
        TraceRecorder.log("mirror_video_resume")
        callbackExecutor.execute {
            pauseGrace?.cancel()
            pauseGrace = null
        }
    }

    override fun onDisplayPin(pin: String) {
        TraceRecorder.log("mirror_pin", "pin" to pin)
    }

    override fun onMetadata(data: ByteArray) {}

    override fun onCoverArt(data: ByteArray) {}

    override fun onProgress(start: Long, curr: Long, end: Long) {}

    override fun onDacpId(dacpId: String, activeRemote: String) {}

    override fun onMirrorRunning(running: Boolean) {
        mirrorFrames = 0
        mirrorUiActive = running
        if (running) startSessionWatchdog() else stopSessionWatchdog()
        videoOut?.onSession(running)
        try {
            onMirrorSession?.invoke(running)
        } catch (e: Exception) {
            Timber.w(e, "mirror session listener failed")
        }
        TraceRecorder.log("mirror_session", "running" to running)
        Timber.i("mirror running=$running")
    }

    override fun onVideoPlay(location: String, startPositionSeconds: Float) {
        // AirPlay URL push (formerly the Kotlin AirPlayServer surface):
        // hand the location to the coordinator's PlaybackPlan path.
        TraceRecorder.log("mirror_video_play", "uri" to location, "start" to startPositionSeconds)
        sink?.onCommand(CastProtocol.PlaybackCommand.Play(Uri.parse(location)))
    }

    override fun onVideoScrub(positionSeconds: Float) {
        sink?.onCommand(CastProtocol.PlaybackCommand.Seek((positionSeconds * 1000).toLong()))
    }

    override fun onVideoRate(rate: Float) {
        if (rate == 0f) sink?.onCommand(CastProtocol.PlaybackCommand.Pause)
    }

    override fun onVideoStop() {
        sink?.onCommand(CastProtocol.PlaybackCommand.Stop)
    }

    override fun onVideoSessionPoll() {}

    // ===== native log channel (bound by nativeInit alongside the raop callbacks) =====

    override fun onLog(msg: String) {
        Timber.d("native: $msg")
    }

    private fun hwAddress(context: Context): ByteArray {
        try {
            val faces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            faces.firstOrNull { it.name.startsWith("wlan") || it.name.startsWith("eth") }
                ?.hardwareAddress?.takeIf { it.size == 6 }?.let { return it }
        } catch (e: Exception) {
            Timber.w(e, "hwAddress read failed")
        }
        val seed = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
        ) ?: "KBTVCaster"
        return MessageDigest.getInstance("MD5").digest(seed.toByteArray()).copyOf(6)
    }

    private companion object {
        const val SERVICE_NAME = "KBTVCaster"
        const val PORT = 7000
        const val MAX_FPS = 60
        const val RENDER_TRACE_EVERY = 100L
    }
}
