package com.panda.pkgbrowser

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mini servidor HTTP propio (solo stdlib) para servir el PKG a la PS4.
 * Soporta `Range` porque el RPI de GoldHEN descarga por rangos.
 * Solo atiende `GET /pkg` del archivo configurado en [fileToServe].
 */
class TinyHttpServer(private val port: Int = 8080) {

    @Volatile
    var fileToServe: File? = null

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        val ss = ServerSocket(port)
        serverSocket = ss
        running.set(true)
        Thread {
            while (running.get()) {
                try {
                    val sock = ss.accept()
                    Thread { handle(sock) }.start()
                } catch (_: Exception) {
                    // Socket cerrado al detener: salir del loop.
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(":").trim()
                    }
                }
                val file = fileToServe
                if (!requestLine.startsWith("GET /pkg") || file == null || !file.exists()) {
                    writeResponse(s, "404 Not Found", null, 0L, 0L, -1L, false)
                    return
                }
                val total = file.length()
                val (start, end) = parseRange(rangeHeader, total)
                writeResponse(
                    s,
                    if (rangeHeader != null) "206 Partial Content" else "200 OK",
                    file, total, start, end, rangeHeader != null
                )
            }
        } catch (_: Exception) {
        }
    }

    /** "bytes=0-1023" / "bytes=1024-" → par (inicio, fin) saneado. */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long> {
        if (header == null || total <= 0) return 0L to maxOf(0L, total - 1)
        val spec = header.substringAfter("=").trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return 0L to total - 1
        val start = spec.substring(0, dash).toLongOrNull() ?: 0L
        val end = spec.substring(dash + 1).toLongOrNull() ?: (total - 1)
        val s = start.coerceIn(0L, total - 1)
        return s to end.coerceIn(s, total - 1)
    }

    private fun writeResponse(
        s: Socket,
        status: String,
        file: File?,
        total: Long,
        start: Long,
        end: Long,
        partial: Boolean
    ) {
        val out = s.getOutputStream()
        val length = if (file != null && total > 0 && end >= start) end - start + 1 else 0L
        val head = StringBuilder()
            .append("HTTP/1.0 ").append(status).append("\r\n")
            .append("Content-Type: application/octet-stream\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Content-Length: ").append(length).append("\r\n")
        if (partial && file != null) {
            head.append("Content-Range: bytes ")
                .append(start).append('-').append(end).append('/').append(total)
                .append("\r\n")
        }
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        if (file != null && length > 0) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(32768)
                var remaining = length
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
        out.flush()
    }
}
