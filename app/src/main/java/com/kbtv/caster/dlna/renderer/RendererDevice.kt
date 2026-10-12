package com.kbtv.caster.dlna.renderer

import android.content.Context
import android.os.Build
import com.kbtv.caster.BuildConfig
import org.fourthline.cling.binding.LocalServiceBinder
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder
import org.fourthline.cling.model.DefaultServiceManager
import org.fourthline.cling.model.ValidationException
import org.fourthline.cling.model.meta.DeviceDetails
import org.fourthline.cling.model.meta.DeviceIdentity
import org.fourthline.cling.model.meta.LocalDevice
import org.fourthline.cling.model.meta.LocalService
import org.fourthline.cling.model.meta.ManufacturerDetails
import org.fourthline.cling.model.meta.ModelDetails
import org.fourthline.cling.model.types.DLNACaps
import org.fourthline.cling.model.types.DLNADoc
import org.fourthline.cling.model.types.UDADeviceType
import org.fourthline.cling.model.types.UDN
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser
import org.fourthline.cling.support.lastchange.LastChange
import org.fourthline.cling.support.lastchange.LastChangeAwareServiceManager
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlLastChangeParser
import timber.log.Timber
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Assembles the UPnP MediaRenderer [LocalDevice]: AVTransport +
 * RenderingControl + ConnectionManager services over a single logical player
 * instance, and runs the daemon thread that flushes LastChange event state
 * to GENA subscribers every [LAST_CHANGE_INTERVAL_MS].
 *
 * Device identity fields describe this project honestly — no borrowed
 * manufacturer/model strings — because picky control points filter on them.
 */
class RendererDevice(
    friendlyName: String,
    context: Context,
    instanceCount: Int = 1,
) {
    private val avTransportLastChange = LastChange(AVTransportLastChangeParser())
    private val renderingControlLastChange = LastChange(RenderingControlLastChangeParser())

    val players = HashMap<UnsignedIntegerFourBytes, RendererPlayer>()

    private val binder: LocalServiceBinder = AnnotationLocalServiceBinder()

    private lateinit var avTransportManager: LastChangeAwareServiceManager<RendererTransport>
    private lateinit var renderingControlManager: LastChangeAwareServiceManager<RendererControl>

    /** Set false by [shutdown] to stop the LastChange push thread. */
    @Volatile
    private var lastChangeThreadRunning = false

    val device: LocalDevice

    init {
        repeat(instanceCount) { i ->
            val instanceId = UnsignedIntegerFourBytes(i.toLong())
            players[instanceId] = RendererPlayer(
                instanceId,
                context,
                avTransportLastChange,
                renderingControlLastChange,
            )
        }

        val connectionManagerService = bind(RendererConnectionManager::class.java)
        connectionManagerService.setManager(
            object : DefaultServiceManager<RendererConnectionManager>(
                connectionManagerService,
                RendererConnectionManager::class.java,
            ) {
                override fun createServiceInstance(): RendererConnectionManager = RendererConnectionManager()
            },
        )

        val avTransportService = bind(RendererTransport::class.java)
        avTransportManager = object : LastChangeAwareServiceManager<RendererTransport>(
            avTransportService,
            AVTransportLastChangeParser(),
        ) {
            override fun createServiceInstance(): RendererTransport =
                RendererTransport(avTransportLastChange, players)
        }
        avTransportService.setManager(avTransportManager)

        val renderingControlService = bind(RendererControl::class.java)
        renderingControlManager = object : LastChangeAwareServiceManager<RendererControl>(
            renderingControlService,
            RenderingControlLastChangeParser(),
        ) {
            override fun createServiceInstance(): RendererControl =
                RendererControl(renderingControlLastChange, players)
        }
        renderingControlService.setManager(renderingControlManager)

        device = try {
            LocalDevice(
                DeviceIdentity(stableUdn(UDN_SALT)),
                UDADeviceType("MediaRenderer", 1),
                DeviceDetails(
                    friendlyName,
                    ManufacturerDetails(MANUFACTURER),
                    ModelDetails(MODEL_NAME, MODEL_DESCRIPTION, BuildConfig.VERSION_NAME),
                    arrayOf(DLNADoc("DMR", DLNADoc.Version.V1_5)),
                    DLNACaps(arrayOf("av-upload")),
                ),
                arrayOf<LocalService<*>>(
                    avTransportService,
                    renderingControlService,
                    connectionManagerService,
                ),
            )
        } catch (e: ValidationException) {
            throw IllegalStateException("MediaRenderer device failed UPnP validation", e)
        }

        Timber.i("renderer device type: ${device.type}")
        startLastChangeThread()
    }

    /**
     * Deterministic per-device UDN: MD5 of manufacturer/model/salt. Stable
     * across reboots so control points re-discover the same device.
     */
    private fun stableUdn(salt: String): UDN {
        val seed = "${Build.MANUFACTURER}|${Build.MODEL}|$salt"
        val hash = MessageDigest.getInstance("MD5").digest(seed.toByteArray())
        val mostSignificant = BigInteger(1, hash.copyOfRange(0, 8)).toLong()
        val leastSignificant = BigInteger(1, hash.copyOfRange(8, 16)).toLong()
        return UDN(UUID(mostSignificant, leastSignificant))
    }

    /** [LocalServiceBinder.read] returns a raw LocalService; restore the typed view. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> bind(clazz: Class<T>): LocalService<T> =
        binder.read(clazz) as LocalService<T>

    private fun startLastChangeThread() {
        lastChangeThreadRunning = true
        thread(name = "DmrRenderer-LastChange", isDaemon = true) {
            try {
                while (lastChangeThreadRunning) {
                    // Non-blocking: only flushes what has accumulated.
                    avTransportManager.fireLastChange()
                    renderingControlManager.fireLastChange()
                    Thread.sleep(LAST_CHANGE_INTERVAL_MS)
                }
            } catch (e: Exception) {
                if (lastChangeThreadRunning) {
                    Timber.e(e, "LastChange push thread failed")
                }
            }
        }
    }

    /** Stop the LastChange push thread; call before dropping the device. */
    fun shutdown() {
        lastChangeThreadRunning = false
    }

    private companion object {
        const val UDN_SALT = "KBTVCaster-DMR"
        const val MANUFACTURER = "KBTVCaster Project"
        const val MODEL_NAME = "KBTVCaster DMR"
        const val MODEL_DESCRIPTION = "KBTVCaster DLNA Digital Media Renderer"
        const val LAST_CHANGE_INTERVAL_MS = 500L
    }
}
