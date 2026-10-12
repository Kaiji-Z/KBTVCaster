package com.kbtv.caster.e2e

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.Observer
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kbtv.caster.receiver.BootReceiver
import com.kbtv.caster.service.CastCoordinatorService
import com.kbtv.caster.service.protocol.CastProtocol
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DLNA end-to-end (VERIFICATION.md §8.5 e2e items), driven the way the real
 * chain delivers commands:
 *
 *  1. sink Play → CastCoordinatorService.onCommand → handlePlay → the real
 *     ExoPlayer reaches STATE_READY then isPlaying.
 *  2. BOOT_COMPLETED delivered to BootReceiver (invoked directly — the
 *     broadcast itself is protected) → service reaches RUNNING.
 *
 * Test media is a 3s 440 Hz WAV generated at runtime and served by a
 * single-file localhost HTTP server, so playback goes through the same
 * progressive-HTTP path a phone-pushed URL takes (DefaultHttpDataSource on
 * 127.0.0.1; cleartext allowed by the manifest). Requires a TV emulator or
 * device; run via `gradle connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class DlnaE2eTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var connection: ServiceConnection? = null
    private var httpServer: SingleFileHttpServer? = null

    @After
    fun tearDown() {
        httpServer?.stop()
        httpServer = null
        connection?.let { context.unbindService(it) }
        connection = null
        context.startService(
            Intent(context, CastCoordinatorService::class.java)
                .setAction(CastCoordinatorService.ACTION_STOP)
        )
    }

    // NOTE: androidTest method names avoid spaces — D8 rejects spacey
    // SimpleNames for this minSdk (JVM unit tests keep the backtick-word style).
    @Test
    fun `sinkPlayShouldDriveExoPlayerToReadyAndIsPlaying`() {
        val wav = File(context.cacheDir, "e2e-test.wav").also { writeTestWav(it) }
        val server = SingleFileHttpServer(wav).also { httpServer = it }.start()

        val service = startAndBindService()
        awaitStatus(service, CastCoordinatorService.ServiceStatus.RUNNING)

        // Self-probe: distinguish a dead test server from a player-side failure.
        try {
            java.net.Socket("127.0.0.1", server.mediaServerPort()).use { s ->
                s.soTimeout = 5_000
                s.getOutputStream().apply {
                    write("GET /test.wav HTTP/1.0\r\n\r\n".toByteArray())
                    flush()
                }
                val line = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream())).readLine()
                android.util.Log.d("DlnaE2e", "self-probe: $line")
                assertTrue("self-probe failed: $line", line?.startsWith("HTTP/1.1 200") == true)
            }
        } catch (e: Exception) {
            throw AssertionError("self-probe threw — server not reachable: ${e.javaClass.simpleName}: ${e.message}")
        }

        val player = service.getExoPlayer()
            ?: throw AssertionError("ExoPlayer should exist after service start")

        val ready = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    android.util.Log.d("DlnaE2e", "playbackState=$playbackState")
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    android.util.Log.d("DlnaE2e", "isPlaying=$isPlaying")
                    if (isPlaying) playing.countDown()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val chain = StringBuilder(error.errorCodeName)
                    var cause: Throwable? = error
                    while (cause != null) {
                        chain.append(" | ").append(cause.javaClass.simpleName).append(": ").append(cause.message)
                        cause = cause.cause
                    }
                    failure.compareAndSet(null, chain.toString())
                    ready.countDown()
                }
            })
        }

        // Exactly what DlnaProtocol does when a phone pushes SetAVTransportURI + Play.
        (service as CastProtocol.PlaybackSink).onCommand(
            CastProtocol.PlaybackCommand.Play(Uri.parse(server.url()))
        )

        assertTrue("STATE_READY within 60s", ready.await(60, TimeUnit.SECONDS))
        if (failure.get() != null) {
            // Discriminate: did the server die between the probe and playback?
            val stillUp = try {
                java.net.Socket("127.0.0.1", server.mediaServerPort()).use { true }
            } catch (e: Exception) {
                false
            }
            throw AssertionError("${failure.get()} | isRunning=${server.isRunning} secondProbe=$stillUp")
        }
        assertTrue("isPlaying within 60s", playing.await(60, TimeUnit.SECONDS))
    }

    @Test
    fun `bootCompletedShouldBringServiceToRunningWithoutCrashing`() {
        // Normalize to STOPPED so the assertion can't ride on a previous test's state.
        val service = startAndBindService()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.startService(
                Intent(context, CastCoordinatorService::class.java)
                    .setAction(CastCoordinatorService.ACTION_STOP)
            )
        }
        awaitStatus(service, CastCoordinatorService.ServiceStatus.STOPPED)

        // ACTION_BOOT_COMPLETED is a protected broadcast — invoke the receiver directly.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        }

        // BootReceiver defers the start by 5s (Handler.postDelayed).
        awaitStatus(service, CastCoordinatorService.ServiceStatus.RUNNING, timeoutSec = 25)
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
        val observer = Observer<CastCoordinatorService.ServiceStatus> { status ->
            if (status == expected) reached.countDown()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { service.serviceStatus.observeForever(observer) }
        val ok = reached.await(timeoutSec, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { service.serviceStatus.removeObserver(observer) }
        assertTrue("serviceStatus never reached $expected within ${timeoutSec}s", ok)
    }

    /** 3s of 440 Hz mono 16-bit PCM at 8 kHz — trivially valid, deterministic. */
    private fun writeTestWav(file: File, seconds: Int = 3, sampleRate: Int = 8000) {
        val samples = seconds * sampleRate
        val dataLen = samples * 2
        BufferedOutputStream(file.outputStream()).use { out ->
            fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
            fun le32(v: Int) = byteArrayOf(
                (v and 0xff).toByte(), (v shr 8 and 0xff).toByte(),
                (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte()
            )
            out.write("RIFF".toByteArray()); out.write(le32(36 + dataLen)); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(1))
            out.write(le32(sampleRate)); out.write(le32(sampleRate * 2)); out.write(le16(2)); out.write(le16(16))
            out.write("data".toByteArray()); out.write(le32(dataLen))
            val frame = ByteArray(2)
            for (i in 0 until samples) {
                val v = (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 12000).toInt()
                frame[0] = (v and 0xff).toByte()
                frame[1] = (v shr 8 and 0xff).toByte()
                out.write(frame)
            }
        }
    }

    /** Serves one file on 127.0.0.1 across sequential connections — enough HTTP for a progressive test stream.
     *  Bind explicitly to IPv4: InetAddress.getLoopbackAddress() resolves to ::1 on some
     *  emulator images, and clients connecting to 127.0.0.1 then get ECONNREFUSED. */
    private class SingleFileHttpServer(private val file: File) {
        private val server = ServerSocket(
            0, 4,
            InetAddress.getByName("127.0.0.1")
        )
        private var thread: Thread? = null

        fun start(): SingleFileHttpServer {
            thread = Thread({
                while (!server.isClosed) {
                    try {
                        server.accept().use { socket -> serve(socket) }
                    } catch (_: Exception) {
                        // server closed or client hiccup — loop or exit quietly
                    }
                }
            }, "e2e-http").apply {
                isDaemon = true
            }.also { it.start() }
            return this
        }

        private fun serve(socket: java.net.Socket) {
            val reader: BufferedReader = socket.getInputStream().bufferedReader()
            reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val bytes = file.readBytes()
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/wav\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().use { os ->
                os.write(head.toByteArray(Charsets.US_ASCII))
                os.write(bytes)
                os.flush()
            }
        }

        fun url(): String = "http://127.0.0.1:${server.localPort}/test.wav"

        fun mediaServerPort(): Int = server.localPort

        val isRunning: Boolean get() = !server.isClosed

        fun stop() {
            try { server.close() } catch (_: Exception) {}
        }
    }
}
