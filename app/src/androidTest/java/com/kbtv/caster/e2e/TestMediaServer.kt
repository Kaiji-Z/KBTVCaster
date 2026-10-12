package com.kbtv.caster.e2e

import android.content.Context
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Shared e2e helper: generates a deterministic 440 Hz WAV at runtime and
 * serves it over HTTP on 127.0.0.1, so playback goes through the same
 * progressive-HTTP path a pushed URL takes. No binary fixtures needed.
 */
class TestMediaServer(context: Context) {

    private val wavFile: File = File(context.cacheDir, "e2e-media.wav").also { writeTestWav(it) }
    // IPv4-explicit: getLoopbackAddress() may resolve to ::1 on some emulator
    // images, breaking 127.0.0.1 clients with ECONNREFUSED.
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private var thread: Thread? = null

    fun start(): TestMediaServer {
        thread = Thread({
            while (!server.isClosed) {
                try {
                    server.accept().use { socket -> serve(socket) }
                } catch (_: Exception) {
                    // closed or client hiccup — loop or exit quietly
                }
            }
        }, "e2e-media-http").apply {
            isDaemon = true
        }.also { it.start() }
        return this
    }

    fun url(): String = "http://127.0.0.1:${server.localPort}/cast-test.wav"

    fun stop() {
        try { server.close() } catch (_: Exception) {}
    }

    private fun serve(socket: Socket) {
        val reader: BufferedReader = socket.getInputStream().bufferedReader()
        reader.readLine() ?: return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val bytes = wavFile.readBytes()
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
}
