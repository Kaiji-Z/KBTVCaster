package com.kbtv.caster.airplay

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.kbtv.caster.service.protocol.CastProtocol
import com.kbtv.caster.trace.TraceRecorder
import timber.log.Timber
import java.util.Collections

/**
 * AirPlay receiving protocol — URL-push subset in this round; screen
 * mirroring arrives with the native H.264/H.265 decode path (SPEC B5).
 *
 * Advertises `_airplay._tcp` + `_raop._tcp` via NsdManager and serves the
 * HTTP surface on [AirPlayServer], translating intents into
 * [CastProtocol.PlaybackCommand]s for the coordinator service.
 *
 * Flag-gated: the service only registers this protocol while
 * `FLAG_AIRPLAY_PROTOCOL` is on — off reproduces pre-change behavior
 * exactly (VERIFICATION.md §4). mDNS TXT-record fidelity (features flags
 * etc.) is tuned during on-device acceptance; the current set is the
 * honest minimal subset.
 */
class AirPlayProtocol : CastProtocol {

    override val name: String = "AirPlay"

    private var server: AirPlayServer? = null
    private var nsdManager: NsdManager? = null
    private val registered = mutableListOf<NsdManager.RegistrationListener>()
    @Volatile private var sink: CastProtocol.PlaybackSink? = null

    override fun start(context: Context, sink: CastProtocol.PlaybackSink) {
        this.sink = sink
        try {
            val s = AirPlayServer(onIntent = { intent -> deliver(intent) })
            val port = s.start()
            server = s
            TraceRecorder.log("airplay_started", "port" to port)
            Timber.d("AirPlay server started on port $port")
            registerNsd(context, port)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start AirPlay protocol")
            TraceRecorder.log("airplay_start_failed", "error" to e.message)
        }
    }

    override fun stop() {
        registered.forEach { listener ->
            try {
                nsdManager?.unregisterService(listener)
            } catch (e: Exception) {
                Timber.w(e, "Error unregistering AirPlay mDNS service")
            }
        }
        registered.clear()
        server?.stop()
        server = null
        sink = null
        TraceRecorder.log("airplay_stopped")
    }

    private fun registerNsd(context: Context, port: Int) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = nsd
        // iOS senders ignore AirPlay devices whose deviceid is not MAC-shaped
        // and whose TXT lacks a features bitmap (measured 2026-10-13: iPhone
        // listed only the TV's built-in stack, not us).
        val deviceId = macShapedDeviceId(context)

        // Both record types point at the same HTTP surface; real receivers
        // split airplay/raop ports, but one socket is sufficient for push.
        for ((type, instance) in listOf(
            "_airplay._tcp." to SERVICE_NAME,
            "_raop._tcp." to "$SERVICE_NAME-raop",
        )) {
            val info = NsdServiceInfo().apply {
                serviceName = instance
                setServiceType(type)
                setPort(port)
                setAttribute("deviceid", deviceId)
                setAttribute("srcvers", "220.68")
                setAttribute("model", MODEL)
                if (type.startsWith("_airplay")) {
                    // 0x77 = photo/video/slideshow push, no screen mirroring.
                    setAttribute("features", "0x77")
                    setAttribute("flags", "0x44")
                } else {
                    setAttribute("pw", "false")
                    setAttribute("cn", "0,1,2,3")
                    setAttribute("ch", "2")
                    setAttribute("et", "0,1")
                }
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    TraceRecorder.log("airplay_registered", "type" to type)
                    Timber.d("AirPlay mDNS registered: ${serviceInfo.serviceName} ($type)")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    TraceRecorder.log("airplay_registration_failed", "type" to type, "code" to errorCode)
                    Timber.e("AirPlay mDNS registration failed: $type ($errorCode)")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registered.add(listener)
        }
    }

    private fun deliver(intent: AirPlayIntent) {
        TraceRecorder.log("airplay_intent", "intent" to intent.javaClass.simpleName)
        val sink = sink ?: return
        when (intent) {
            is AirPlayIntent.PlayUrl ->
                sink.onCommand(CastProtocol.PlaybackCommand.Play(Uri.parse(intent.url)))
            AirPlayIntent.Pause -> sink.onCommand(CastProtocol.PlaybackCommand.Pause)
            AirPlayIntent.Resume -> sink.onCommand(CastProtocol.PlaybackCommand.Resume)
            AirPlayIntent.Stop -> sink.onCommand(CastProtocol.PlaybackCommand.Stop)
            is AirPlayIntent.SeekSec ->
                sink.onCommand(CastProtocol.PlaybackCommand.Seek((intent.positionSec * 1000).toLong()))
        }
    }

    /**
     * MAC-shaped stable device id: the wlan MAC when readable, else six MD5
     * bytes of ANDROID_ID formatted as a MAC. Shape (not authenticity) is
     * what iOS discovery checks.
     */
    private fun macShapedDeviceId(context: Context): String {
        val real = try {
            val faces = Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            faces.firstOrNull { it.name == "wlan0" || it.name == "eth0" }
                ?.hardwareAddress?.takeIf { it.size == 6 }
        } catch (e: Exception) {
            null
        }
        if (real != null) return real.joinToString(":") { String.format("%02X", it) }
        val seed = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
        ) ?: "KBTVCaster"
        val hash = java.security.MessageDigest.getInstance("MD5").digest(seed.toByteArray())
        return (0 until 6).joinToString(":") { String.format("%02X", hash[it]) }
    }

    companion object {
        /** ASCII instance name — CJK names in mDNS confuse some senders (SPEC §5 C2 rationale). */
        const val SERVICE_NAME = "KBTVCaster"
        const val MODEL = "AppleTV3,2"
    }
}
