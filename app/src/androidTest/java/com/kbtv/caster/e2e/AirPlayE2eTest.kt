package com.kbtv.caster.e2e

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kbtv.caster.airplay.AirPlayProtocol
import com.kbtv.caster.service.CastCoordinatorService
import com.kbtv.caster.service.protocol.FeatureFlags
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AirPlay end-to-end (VERIFICATION.md criterion 4). Gated by
 * `Assume` on FLAG_AIRPLAY_PROTOCOL: these tests SKIP under default flags
 * (keeping `connectedDebugAndroidTest` green for criterion 2) and RUN under
 * `gradle connectedDebugAndroidTest -Pkbtv.flags.airplay=true`.
 *
 *  1. `_airplay._tcp` + `_raop._tcp` discoverable via NsdManager ≤10s.
 *  2. URL push (POST /play plist body) → real ExoPlayer READY + isPlaying.
 *  3. Service restart → mDNS re-discoverable ≤10s.
 */
@RunWith(AndroidJUnit4::class)
class AirPlayE2eTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var connection: ServiceConnection? = null

    @After
    fun tearDown() {
        connection?.let { context.unbindService(it) }
        connection = null
        context.startService(
            Intent(context, CastCoordinatorService::class.java)
                .setAction(CastCoordinatorService.ACTION_STOP)
        )
    }

    @Test
    fun `airplayAndRaopShouldBeDiscoverableWithin10Seconds`() {
        assumeTrue(FeatureFlags.airplayProtocol)
        val service = startAndBindService()
        awaitStatus(service, CastCoordinatorService.ServiceStatus.RUNNING)

        discoverWithin10s("_airplay._tcp.", AirPlayProtocol.SERVICE_NAME)
        discoverWithin10s("_raop._tcp.", AirPlayProtocol.SERVICE_NAME + "-raop")
    }

    @Test
    fun `airplayUrlPushShouldDriveExoPlayerToReadyAndIsPlaying`() {
        assumeTrue(FeatureFlags.airplayProtocol)
        val media = TestMediaServer(context).also { it.start() }
        try {
            val service = startAndBindService()
            awaitStatus(service, CastCoordinatorService.ServiceStatus.RUNNING)
            val port = awaitAirPlayPort()

            val player = service.getExoPlayer()
                ?: throw AssertionError("ExoPlayer should exist after service start")
            val ready = CountDownLatch(1)
            val playing = CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<String>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        android.util.Log.d("AirPlayE2e", "playbackState=$playbackState")
                        if (playbackState == Player.STATE_READY) ready.countDown()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        android.util.Log.d("AirPlayE2e", "isPlaying=$isPlaying")
                        if (isPlaying) playing.countDown()
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        failure.compareAndSet(null, "player error: ${error.errorCodeName} — ${error.message}")
                        ready.countDown()
                    }
                })
            }

            val body = "<plist><dict><key>Content-Location</key><string>${media.url()}</string>" +
                "<key>Start-Position</key><real>0.0</real></dict></plist>"
            val request = "POST /play HTTP/1.1\r\nContent-Length: ${body.length}\r\n" +
                "Content-Type: text/x-apple-plist+xml\r\n\r\n$body"
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 10_000
                socket.getOutputStream().apply {
                    write(request.toByteArray(StandardCharsets.ISO_8859_1))
                    flush()
                }
                socket.shutdownOutput()
            }

            assertTrue("STATE_READY within 60s", ready.await(60, TimeUnit.SECONDS))
            assertTrue("isPlaying within 60s", playing.await(60, TimeUnit.SECONDS))
        } finally {
            media.stop()
        }
    }

    @Test
    fun `mDNSShouldBeRediscoverableWithin10SecondsAfterServiceRestart`() {
        assumeTrue(FeatureFlags.airplayProtocol)
        val service = startAndBindService()
        awaitStatus(service, CastCoordinatorService.ServiceStatus.RUNNING)

        // Stop, wait for STOPPED, then start again — discovery must succeed after that.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            context.startService(
                Intent(context, CastCoordinatorService::class.java)
                    .setAction(CastCoordinatorService.ACTION_STOP)
            )
        }
        awaitStatus(service, CastCoordinatorService.ServiceStatus.STOPPED)
        instrumentation.runOnMainSync {
            context.startService(
                Intent(context, CastCoordinatorService::class.java)
                    .setAction(CastCoordinatorService.ACTION_START)
            )
        }
        awaitStatus(service, CastCoordinatorService.ServiceStatus.RUNNING)

        discoverWithin10s("_airplay._tcp.", AirPlayProtocol.SERVICE_NAME)
    }

    // ===== helpers =====

    private fun startAndBindService(): CastCoordinatorService {
        context.startService(
            Intent(context, CastCoordinatorService::class.java)
                .setAction(CastCoordinatorService.ACTION_START)
        )
        val bound = CountDownLatch(1)
        var service: CastCoordinatorService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as CastCoordinatorService.LocalBinder).getService()
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        connection = conn
        context.bindService(
            Intent(context, CastCoordinatorService::class.java),
            conn,
            Context.BIND_AUTO_CREATE
        )
        assertTrue("service bind timeout", bound.await(15, TimeUnit.SECONDS))
        return service!!
    }

    private fun awaitStatus(
        service: CastCoordinatorService,
        expected: CastCoordinatorService.ServiceStatus,
        timeoutSec: Long = 15
    ) {
        val reached = CountDownLatch(1)
        val observer = androidx.lifecycle.Observer<CastCoordinatorService.ServiceStatus> {
            if (it == expected) reached.countDown()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { service.serviceStatus.observeForever(observer) }
        val ok = reached.await(timeoutSec, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { service.serviceStatus.removeObserver(observer) }
        assertTrue("serviceStatus never reached $expected within ${timeoutSec}s", ok)
    }

    private fun discoverWithin10s(type: String, expectName: String) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                assertTrue("discovery start failed for $serviceType: $errorCode", false)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == expectName) found.countDown()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        val ok = found.await(10, TimeUnit.SECONDS)
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        assertTrue("mDNS service '$expectName' ($type) not found within 10s", ok)
    }

    /** The service logs the ephemeral AirPlayServer port into the JSONL trace. */
    private fun awaitAirPlayPort(timeoutSec: Long = 15): Int {
        val trace = File(File(context.filesDir, "trace"), "cast.jsonl")
        val pattern = Regex("\"event\":\"airplay_started\",\"port\":(\\d+)")
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < deadline) {
            val text = if (trace.isFile) trace.readText() else ""
            val match = pattern.findAll(text).lastOrNull()
            if (match != null) return match.groupValues[1].toInt()
            Thread.sleep(200)
        }
        throw AssertionError("airplay_started not found in trace within ${timeoutSec}s")
    }
}
