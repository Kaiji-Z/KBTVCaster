package com.kbtv.caster.dlna

import org.fourthline.cling.UpnpServiceConfiguration
import org.fourthline.cling.android.AndroidNetworkAddressFactory
import org.fourthline.cling.android.AndroidUpnpServiceConfiguration
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.transport.spi.NetworkAddressFactory
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.logging.Logger

/**
 * UpnpServiceConfiguration with an emulator-tolerant interface filter.
 *
 * The vendored NetworkAddressFactoryImpl requires multicast-capable
 * interfaces; goldfish (emulator) NICs report supportsMulticast() == false,
 * so Cling refuses to start its stream server on ANY AVD ("No usable
 * network interface") and SOAP-driven e2e verification would be impossible
 * without a physical TV.
 *
 * Fix via the vendor's own extension points — no vendored code modified:
 * [KbtvNetworkAddressFactory] first runs the stock strict filter; only if
 * that yields ZERO interfaces does it re-run discovery accepting any up,
 * non-loopback interface with an IPv4 address. Real devices behave exactly
 * as before (their multicast NIC passes the strict filter); emulators gain
 * a TCP-only mode. SSDP multicast still only works where the OS supports
 * it — that part is genuinely impossible on an emulator.
 */
class KbtvUpnpServiceConfiguration : AndroidUpnpServiceConfiguration() {

    override fun createNetworkAddressFactory(streamListenPort: Int): NetworkAddressFactory =
        KbtvNetworkAddressFactory(streamListenPort)
}

/** See [KbtvUpnpServiceConfiguration]. */
class KbtvNetworkAddressFactory(streamListenPort: Int) :
    AndroidNetworkAddressFactory(streamListenPort) {

    private val log = Logger.getLogger(KbtvNetworkAddressFactory::class.java.name)
    private var relaxed = false

    init {
        if (!hasUsableNetwork()) {
            log.warning("No usable network interface (strict filter); retrying with relaxed TCP-only filter")
            relaxed = true
            networkInterfaces.clear()
            bindAddresses.clear()
            useInterfaces.clear()
            useAddresses.clear()
            discoverNetworkInterfaces()
            discoverBindAddresses()
        }
        // On emulator hardware, also bind loopback: `adb forward` (the e2e
        // tooling's only channel into the guest) reaches 127.0.0.1 alone.
        // Real devices are untouched by this branch.
        val emulator = android.os.Build.HARDWARE.let { it.contains("goldfish") || it.contains("ranchu") }
        if (emulator && bindAddresses.none { it.isLoopbackAddress }) {
            var loopback: NetworkInterface? = null
            val all = java.net.NetworkInterface.getNetworkInterfaces()
            while (all != null && all.hasMoreElements()) {
                val nif = all.nextElement()
                if (nif.isLoopback) {
                    loopback = nif
                    break
                }
            }
            loopback?.let { lb ->
                networkInterfaces.add(lb)
                getInetAddresses(lb).filterIsInstance<Inet4Address>().forEach { bindAddresses.add(it) }
            }
        }
        if (com.kbtv.caster.BuildConfig.DEBUG) {
            android.util.Log.w(
                "KbtvNet",
                "factory ready: relaxed=$relaxed ifaces=${networkInterfaces.joinToString { it.name }} binds=${bindAddresses.joinToString { it.hostAddress }}"
            )
        }
    }

    override fun isUsableNetworkInterface(iface: NetworkInterface): Boolean =
        if (relaxed) isRelaxedUsable(iface) else super.isUsableNetworkInterface(iface)

    /**
     * Stock IPv4/useAddresses filter without the vendored Android subclass's
     * ICS-era reflection hack (`InetAddress.hostName` injection), which ART
     * blocks on API 33+ — turning EVERY address unusable there. Loopback is
     * additionally accepted in relaxed (emulator) mode so the stream server
     * also binds 127.0.0.1, reachable through `adb forward` (which connects
     * to the device's loopback only).
     */
    override fun isUsableAddress(networkInterface: NetworkInterface, address: java.net.InetAddress): Boolean =
        address is Inet4Address &&
            (!address.isLoopbackAddress || relaxed) &&
            (useAddresses.isEmpty() || useAddresses.contains(address.hostAddress))

    private fun isRelaxedUsable(iface: NetworkInterface): Boolean = try {
        // Loopback included: relaxed mode exists for emulator verification,
        // where `adb forward` can only reach the device's 127.0.0.1.
        iface.isUp && getInetAddresses(iface).any { it is Inet4Address }
    } catch (e: Exception) {
        false
    }
}

/**
 * Drop-in replacement for the manifest's AndroidUpnpServiceImpl entry: same
 * behavior on real devices, emulator-tolerant via [KbtvUpnpServiceConfiguration].
 */
class KbtvUpnpServiceImpl : AndroidUpnpServiceImpl() {

    override fun createConfiguration(): UpnpServiceConfiguration =
        KbtvUpnpServiceConfiguration()
}
