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
            val keyFile = appContext.filesDir.resolve("airplay.pem").absolutePath
            // oboe's OpenSL ES backend (pre-AAudio devices, like the Android 6
            // field TV) cannot discover native rate/burst itself; hand it the
            // AudioManager values so low-latency buffer sizing works there.
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            NativeBridge.nativeSetDefaultStreamValues(
                audioManager.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
                audioManager.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0,
            )
            handle = NativeBridge.nativeInit(this, hwAddress(appContext), SERVICE_NAME, keyFile, false, false)
            if (handle == 0L) {
                Timber.e("nativeInit failed")
                TraceRecorder.log("mirror_start_failed", "stage" to "init")
                return
            }
            val dm = appContext.resources.displayMetrics
            NativeBridge.nativeSetDisplaySize(handle, dm.widthPixels, dm.heightPixels, MAX_FPS)
            NativeBridge.nativeSetCodecs(handle, true, true)
            NativeBridge.nativeSetLang(handle, "", "", java.util.Locale.getDefault().toLanguageTag())
            NativeBridge.nativeSetAudioEnabled(handle, true)
            NativeBridge.nativeSetPlist(handle, "maxFPS", MAX_FPS)
            // Gate raop.c's CSeq-less video path: without this the kernel drops
            // URL-push /play requests ("ignoring AirPlay video streaming
            // request"). Must run BEFORE nativeStart: the shim snapshots the
            // TXT records (incl. features bits 0/4) when registering inside
            // start, and later feature updates never reach the cached TXT.
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

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
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
            NativeBridge.nativeServerAudioStart(handle)
            NativeBridge.nativeServerAudioFormat(handle, ct, spf)
            TraceRecorder.log("mirror_audio_format", "ct" to ct, "spf" to spf, "screen" to usingScreen)
        } catch (e: Exception) {
            Timber.e(e, "audio start failed")
        }
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        TraceRecorder.log("mirror_video_size", "w" to w, "h" to h)
        videoOut?.setResolution(w.toInt(), h.toInt())
    }

    override fun onVolumeChange(volume: Float) {
        TraceRecorder.log("mirror_volume", "v" to volume)
    }

    override fun onClientVolume(): Float = 0f

    override fun onAudioTeardown() {
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
    }

    override fun onConnectionReset(reason: Int) {
        TraceRecorder.log("mirror_conn_reset", "reason" to reason)
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
        videoOut?.onSession(running)
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
