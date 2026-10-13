package com.kbtv.caster.airplay

import timber.log.Timber
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal mDNS responder for the AirPlay service records.
 *
 * Why not NsdManager alone: on the field TV (Android 6) the platform mDNS
 * daemon answered browse queries only right after registration, then went
 * silent — iOS senders never saw the service (measured 2026-10-13; the TV's
 * own [vendor] stack ships a private responder, presumably for this reason).
 * This class announces and answers on the wire directly; the NsdManager
 * registration is kept alongside for platforms where it works.
 *
 * Answers PTR (service type), SRV/TXT (instance) and A (hostname) queries,
 * and re-announces periodically. Pure java.net, no compression pointers.
 */
class AirPlayMDns(
    private val serviceInstance: String,
    private val raopInstance: String,
    private val port: Int,
    private val txtAirplay: List<Pair<String, String>>,
    private val txtRaop: List<Pair<String, String>>,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: MulticastSocket? = null
    @Volatile private var primaryInterface: NetworkInterface? = null

    /** Last label(s) of the host name, e.g. "kbtvcaster-a1b2c3". */
    private val hostLabel = "kbtvcaster-" + (
        txtAirplay.firstOrNull { it.first == "deviceid" }?.second
            ?.replace(":", "")?.takeLast(6)?.lowercase() ?: "device"
        )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val candidates = usableInterfaces()
            // One primary interface for both join and send — the socket's
            // networkInterface decides where responses leave, so it must be
            // the LAN one (wlan0 > eth0 > first), not whichever enumerated
            // last (TVs expose p2p0/uap0 too).
            val primary = candidates.firstOrNull { it.name == "wlan0" }
                ?: candidates.firstOrNull { it.name == "eth0" }
                ?: candidates.firstOrNull()
                ?: throw IllegalStateException("no usable multicast interface")
            val sock = MulticastSocket(MDNS_PORT)
            sock.reuseAddress = true
            sock.timeToLive = 255
            sock.networkInterface = primary
            sock.joinGroup(InetSocketAddress(MDNS_GROUP, MDNS_PORT), primary)
            socket = sock
            primaryInterface = primary
            Timber.i("mDNS responder on ${primary.name} (${localAddress()?.hostAddress}) : $serviceInstance/$raopInstance port $port")
        } catch (e: Exception) {
            Timber.e(e, "mDNS responder failed to bind $MDNS_PORT")
            running.set(false)
            return
        }
        thread = Thread({ loop() }, "AirPlayMDns").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        try {
            socket?.close()
        } catch (e: Exception) {
            Timber.w(e, "mDNS socket close")
        }
        socket = null
        thread = null
    }

    private fun loop() {
        val buf = ByteArray(1500)
        var lastAnnounce = 0L
        while (running.get()) {
            try {
                val sock = socket ?: return
                if (lastAnnounce == 0L || System.currentTimeMillis() - lastAnnounce > ANNOUNCE_INTERVAL_MS) {
                    send(buildAnswerRecords(null))
                    lastAnnounce = System.currentTimeMillis()
                }
                sock.soTimeout = ANNOUNCE_INTERVAL_MS.coerceAtMost(1000L).toInt()
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                handleQuery(packet.data, packet.length)
            } catch (e: java.net.SocketTimeoutException) {
                // Expected heartbeat between announcements.
            } catch (e: Exception) {
                if (running.get()) Timber.w(e, "mDNS loop iteration failed")
            }
        }
    }

    /** qtypes we care about; null qname means unsolicited announcement. */
    private fun handleQuery(data: ByteArray, length: Int) {
        try {
            var off = 12
            val qdcount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            val labels = ArrayList<Pair<List<String>, Int>>() // qname labels + qtype
            repeat(qdcount) {
                val name = ArrayList<String>()
                while (off < length) {
                    val l = data[off].toInt() and 0xFF
                    if (l == 0) { off++; break }
                    if (l and 0xC0 != 0) { off += 2; break }
                    name.add(String(data, off + 1, l, Charsets.US_ASCII))
                    off += 1 + l
                }
                if (off + 4 > length) return
                val qtype = ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
                labels.add(Pair(name, qtype))
                off += 4
            }
            val matched = labels.any { (name, _) ->
                val joined = name.joinToString(".").lowercase()
                joined.contains("airplay") || joined.contains("raop") ||
                    joined.contains(serviceInstance.lowercase()) ||
                    joined.contains(raopInstance.lowercase()) ||
                    joined.contains(hostLabel)
            }
            if (matched) send(buildAnswerRecords(labels))
        } catch (e: Exception) {
            Timber.w(e, "mDNS query parse failed")
        }
    }

    private fun buildAnswerRecords(questions: List<Pair<List<String>, Int>>?): List<ByteArray> {
        // Full record set every time (answer + implicit additional): simple,
        // and complete enough for iOS browse + resolve in one round trip.
        val records = ArrayList<ByteArray>()
        for ((type, instance, txt) in listOf(
            Triple("_airplay._tcp.local", serviceInstance, txtAirplay),
            Triple("_raop._tcp.local", raopInstance, txtRaop),
        )) {
            val instanceName = listOf(instance) + type.split(".")
            records.add(record(instanceName, TYPE_PTR, TTL, encodeName(type.split("."))))
            records.add(record(instanceName, TYPE_SRV, TTL, encodeSrv(port)))
            records.add(record(instanceName, TYPE_TXT, TTL, encodeTxt(txt)))
        }
        records.add(record(listOf(hostLabel, "local"), TYPE_A, TTL, localAddress()?.address ?: byteArrayOf(0, 0, 0, 0)))
        return records
    }

    private fun send(records: List<ByteArray>) {
        if (records.isEmpty()) return
        val size = 12 + records.sumOf { it.size }
        val out = ByteArray(size)
        // Response header: QR=1 AA=1
        out[2] = 0x84.toByte()
        val an = records.size
        out[6] = ((an shr 8) and 0xFF).toByte()
        out[7] = (an and 0xFF).toByte()
        var off = 12
        for (r in records) {
            System.arraycopy(r, 0, out, off, r.size)
            off += r.size
        }
        try {
            socket?.send(DatagramPacket(out, out.size, InetAddress.getByName(MDNS_GROUP), MDNS_PORT))
        } catch (e: Exception) {
            Timber.w(e, "mDNS send failed")
        }
    }

    private fun record(name: List<String>, type: Int, ttl: Int, rdata: ByteArray): ByteArray {
        val nameBytes = encodeName(name)
        val out = ByteArray(nameBytes.size + 10 + rdata.size)
        System.arraycopy(nameBytes, 0, out, 0, nameBytes.size)
        var off = nameBytes.size
        out[off] = ((type shr 8) and 0xFF).toByte(); out[off + 1] = (type and 0xFF).toByte()
        out[off + 2] = 0; out[off + 3] = 1 // class IN
        out[off + 4] = ((ttl shr 24) and 0xFF).toByte(); out[off + 5] = ((ttl shr 16) and 0xFF).toByte()
        out[off + 6] = ((ttl shr 8) and 0xFF).toByte(); out[off + 7] = (ttl and 0xFF).toByte()
        out[off + 8] = ((rdata.size shr 8) and 0xFF).toByte(); out[off + 9] = (rdata.size and 0xFF).toByte()
        System.arraycopy(rdata, 0, out, off + 10, rdata.size)
        return out
    }

    private fun encodeName(labels: List<String>): ByteArray {
        var size = 1
        labels.forEach { size += 1 + it.length }
        val out = ByteArray(size)
        var off = 0
        for (label in labels) {
            out[off] = label.length.toByte()
            val bytes = label.toByteArray(Charsets.US_ASCII)
            System.arraycopy(bytes, 0, out, off + 1, bytes.size)
            off += 1 + bytes.size
        }
        out[off] = 0
        return out
    }

    private fun encodeSrv(port: Int): ByteArray {
        val target = encodeName(listOf(hostLabel, "local"))
        val out = ByteArray(6 + target.size)
        out[0] = 0; out[1] = 0 // priority
        out[2] = 0; out[3] = 0 // weight
        out[4] = ((port shr 8) and 0xFF).toByte(); out[5] = (port and 0xFF).toByte()
        System.arraycopy(target, 0, out, 6, target.size)
        return out
    }

    private fun encodeTxt(txt: List<Pair<String, String>>): ByteArray {
        var size = 0
        txt.forEach { size += 1 + it.first.length + 1 + it.second.length }
        val out = ByteArray(size)
        var off = 0
        for ((k, v) in txt) {
            val entry = "$k=$v".toByteArray(Charsets.US_ASCII)
            out[off] = entry.size.toByte()
            System.arraycopy(entry, 0, out, off + 1, entry.size)
            off += 1 + entry.size
        }
        return out
    }

    private fun usableInterfaces(): List<NetworkInterface> {
        val out = ArrayList<NetworkInterface>()
        val en = NetworkInterface.getNetworkInterfaces() ?: return out
        while (en.hasMoreElements()) {
            val nif = en.nextElement()
            try {
                if (nif.isUp && !nif.isLoopback && nif.inetAddresses.asSequence().any { it is java.net.Inet4Address }) {
                    out.add(nif)
                }
            } catch (e: Exception) {
                // skip
            }
        }
        return out
    }

    private fun localAddress(): InetAddress? = try {
        primaryInterface?.inetAddresses?.asSequence()
            ?.filterIsInstance<java.net.Inet4Address>()?.firstOrNull()
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val TYPE_PTR = 12
        const val TYPE_A = 1
        const val TYPE_SRV = 33
        const val TYPE_TXT = 16
        const val TTL = 120
        const val ANNOUNCE_INTERVAL_MS = 30_000L
    }
}
