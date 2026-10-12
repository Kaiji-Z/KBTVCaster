package com.kbtv.caster.dlna

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.kbtv.caster.dlna.renderer.DmrPlaybackListener
import com.kbtv.caster.dlna.renderer.RendererDevice
import com.kbtv.caster.trace.TraceRecorder
import org.fourthline.cling.android.AndroidUpnpService
import timber.log.Timber

/**
 * Binds the Cling UPnP service ([KbtvUpnpServiceImpl]) and registers the
 * MediaRenderer device on it. The descriptor advertises an ASCII
 * friendlyName — picky control points choke on non-ASCII values here; the
 * UI/notification display name is unaffected.
 */
object DlnaBootstrap {

    /** ASCII-only device name for the UPnP description XML. */
    private const val FRIENDLY_NAME = "KBTVCaster"

    private var renderer: RendererDevice? = null
    private var playbackListener: DmrPlaybackListener? = null
    private var serviceConnection: ServiceConnection? = null
    private var appContext: Context? = null

    fun setPlaybackListener(listener: DmrPlaybackListener) {
        playbackListener = listener
        renderer?.players?.values?.forEach { it.playbackListener = listener }
    }

    fun start(context: Context) {
        appContext = context.applicationContext

        // Unbind any previous connection; a fresh one is created below.
        stop()

        val connection: ServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val upnpService = service as AndroidUpnpService
                Timber.i("Cling service connected")
                val device = RendererDevice(FRIENDLY_NAME, context)
                renderer = device
                upnpService.registry.addDevice(device.device)
                playbackListener?.let { setPlaybackListener(it) }
                Timber.i("DLNA服务已启动: $FRIENDLY_NAME")
                logRegistration(upnpService, device)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Timber.i("Cling service disconnected")
            }
        }
        serviceConnection = connection
        context.bindService(
            Intent(context, KbtvUpnpServiceImpl::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun stop() {
        renderer?.shutdown()
        renderer = null
        val connection = serviceConnection
        val context = appContext
        if (connection != null && context != null) {
            try {
                context.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Service not registered")
            }
            serviceConnection = null
        }
    }

    /**
     * Ground truth for e2e tooling (androidTest asset
     * dlna_control_point.py): it reads these trace events to find the UDN
     * and descriptor path, then drives SOAP through adb forward.
     */
    private fun logRegistration(upnpService: AndroidUpnpService, device: RendererDevice) {
        try {
            val udn = device.device.identity.udn.toString()
            Timber.i("UPnP device registered, UDN=$udn")
            TraceRecorder.log("upnp_registered", "udn" to udn)
            try {
                val descPath = upnpService.configuration.namespace
                    .getDescriptorPath(device.device)
                Timber.i("UPnP descriptor path: $descPath")
                TraceRecorder.log("upnp_descriptor_path", "path" to descPath.toString())
            } catch (t: Throwable) {
                Timber.w(t, "descriptor path unavailable")
            }
        } catch (t: Throwable) {
            Timber.w(t, "Failed to log UDN")
        }
    }
}
