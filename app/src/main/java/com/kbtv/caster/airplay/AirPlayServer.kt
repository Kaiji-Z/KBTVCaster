package com.kbtv.caster.airplay

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Embedded AirPlay receiver HTTP surface (URL-push subset): accepts one
 * request per connection, parses with [AirPlayRequest], maps via
 * [AirPlayCommandMapper], and delivers the intent to [onIntent].
 *
 * Pure java.net — JVM-testable without Android. mDNS advertisement and
 * service wiring live in [AirPlayProtocol]. Response policy: handled → 200,
 * known-but-incomplete → 404, unparseable → 400; nothing throws outward.
 */
internal class AirPlayServer(
    private val onIntent: (AirPlayIntent) -> Unit,
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "kbtv-airplay").apply { isDaemon = true }
    },
) {
    private var serverSocket: ServerSocket? = null

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** Start listening on an ephemeral port; returns the bound port. */
    fun start(): Int {
        val socket = ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        serverSocket = socket
        executor.execute { acceptLoop(socket) }
        return socket.localPort
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try { server.accept() } catch (_: Exception) { break }
            executor.execute {
                try {
                    handle(client)
                } catch (_: Exception) {
                    try { client.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
            val head = StringBuilder()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                head.append(line).append("\r\n")
                val idx = line.indexOf(':')
                if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                    contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                }
            }
            if (head.isEmpty()) return

            val bodyChars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(bodyChars, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            val raw = head.toString() + "\r\n" + String(bodyChars, 0, read)

            val request = AirPlayRequest.parse(raw)
            val intent = request?.let { AirPlayCommandMapper.toIntent(it) }
            val status = when {
                request == null -> "400 Bad Request"
                intent != null -> "200 OK"
                else -> "404 Not Found"
            }
            val out = socket.getOutputStream()
            out.write(
                "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1)
            )
            out.flush()

            intent?.let(onIntent)
        }
    }
}
