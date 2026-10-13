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
    private var ownResponder: AirPlayMDns? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    @Volatile private var sink: CastProtocol.PlaybackSink? = null

    override fun start(context: Context, sink: CastProtocol.PlaybackSink) {
        this.sink = sink
        try {
            val s = AirPlayServer(onIntent = { intent -> deliver(intent) })
            val port = s.start()
            server = s
            TraceRecorder.log("airplay_started", "port" to port)
            Timber.d("AirPlay server started on port $port")
            val deviceId = macShapedDeviceId(context)
            registerNsd(context, port, deviceId)
            // Below API 24 the platform mDNS daemon answered browse queries
            // only right after registration, then went silent (measured on
            // the Android 6 field TV), so answer on the wire ourselves. On
            // newer systems NsdManager is reliable AND our duplicate
            // announcements would trigger an mDNS name conflict that makes
            // mdnsd rename the instance (caught by AirPlayE2eTest).
            if (android.os.Build.VERSION.SDK_INT < 24) {
                multicastLock = try {
                    val wifi = context.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                    wifi?.createMulticastLock("KBTVCaster-mDNS")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "MulticastLock acquire failed")
                    null
                }
                val responder = AirPlayMDns(
                    SERVICE_NAME,
                    "$SERVICE_NAME-raop",
                    port,
                    txtAirplay(deviceId),
                    txtRaop(deviceId),
                )
                responder.start()
                ownResponder = responder
            }
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
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Timber.w(e, "MulticastLock release")
        }
        multicastLock = null
        ownResponder?.stop()
        ownResponder = null
        server?.stop()
        server = null
        sink = null
        TraceRecorder.log("airplay_stopped")
    }

    /** Keys measured from receivers iOS actually lists (see AirPlayMDns docs). */
    private fun txtAirplay(deviceId: String): List<Pair<String, String>> = listOf(
        "deviceid" to deviceId,
        "srcvers" to "220.68",
        "model" to MODEL,
        // Push (photo/video/slideshow) only — no mirroring claims. Upper
        // 0x1E marks AirPlay-2-era presence without audio-stack support.
        "features" to "0x77,0x1E",
        "flags" to "0x4",
        "vv" to "2",
    )

    private fun txtRaop(deviceId: String): List<Pair<String, String>> = listOf(
        "deviceid" to deviceId,
        "srcvers" to "220.68",
        "model" to MODEL,
        "pw" to "false",
        "cn" to "0,1,2,3",
        "ch" to "2",
        "et" to "0,1",
    )

    private fun registerNsd(context: Context, port: Int, deviceId: String) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = nsd
        // Both record types point at the same HTTP surface; real receivers
        // split airplay/raop ports, but one socket is sufficient for push.
        for ((type, instance, txt) in listOf(
            Triple("_airplay._tcp.", SERVICE_NAME, txtAirplay(deviceId)),
            Triple("_raop._tcp.", "$SERVICE_NAME-raop", txtRaop(deviceId)),
        )) {
            val info = NsdServiceInfo().apply {
                serviceName = instance
                setServiceType(type)
                setPort(port)
                txt.forEach { (k, v) -> setAttribute(k, v) }
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
