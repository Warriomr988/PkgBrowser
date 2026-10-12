package com.panda.pkgbrowser

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mini servidor HTTP propio (solo stdlib) para servir el PKG a la PS4.
 *
 * Crítico para el RPI de GoldHEN: responde `206 Partial Content` con
 * `Content-Range` y `Accept-Ranges: bytes` ante `Range`, `200` completo sin
 * Range, soporta `HEAD`, devuelve `416` ante rangos imposibles y streamea el
 * archivo por chunks de 32 KB (jamás lo carga entero en memoria).
 *
 * Diseñado para vivir en el foreground service durante TODA la descarga
 * (v1.1: el bug de v1.0 lo detenía a los segundos del 200 OK del RPI y la
 * PS4 se quedaba sin servidor a mitad del PKG).
 * Solo atiende `/pkg` del archivo configurado en [fileToServe].
 */
class TinyHttpServer(private val port: Int = 8080) {

    @Volatile
    var fileToServe: File? = null

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
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
                s.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(":").trim()
                    }
                }
                val file = fileToServe
                val headOnly = method == "HEAD"
                if ((method != "GET" && !headOnly) || path != "/pkg" || file == null || !file.exists()) {
                    writeStatus(s, "404 Not Found", "Content-Type: text/plain\r\nContent-Length: 0\r\n")
                    return
                }
                val total = file.length()
                if (rangeHeader != null) {
                    val (rs, re) = parseRange(rangeHeader, total)
                    if (rs >= total || re < rs) {
                        writeStatus(
                            s, "416 Range Not Satisfiable",
                            "Content-Range: bytes */$total\r\nContent-Length: 0\r\n"
                        )
                        return
                    }
                    val start = rs.coerceAtLeast(0L)
                    val end = re.coerceAtMost(total - 1)
                    writeFile(s, file, total, start, end, partial = true, headOnly = headOnly)
                } else {
                    writeFile(s, file, total, 0L, total - 1, partial = false, headOnly = headOnly)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * "bytes=0-1023" → (0,1023); "bytes=1024-" → (1024,total-1);
     * "bytes=-500" → (total-500,total-1). Si vienen varios rangos, solo el
     * primero. Devuelve valores sin sanear: el llamador decide el 416.
     */
    private fun parseRange(header: String, total: Long): Pair<Long, Long> {
        val spec = header.substringAfter("=").substringBefore(",").trim()
        val dash = spec.indexOf('-')
        if (dash < 0 || total <= 0) return 0L to -1L
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()
        if (left.isEmpty()) {
            // Rango sufijo: los últimos N bytes.
            val n = right.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            return (total - n).coerceAtLeast(0L) to (total - 1)
        }
        val s = left.toLongOrNull() ?: 0L
        val e = if (right.isEmpty()) total - 1 else (right.toLongOrNull() ?: (total - 1))
        return s to e
    }

    private fun writeStatus(s: Socket, status: String, extraHeaders: String) {
        val head = "HTTP/1.0 $status\r\n${extraHeaders}Connection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun writeFile(
        s: Socket,
        file: File,
        total: Long,
        start: Long,
        end: Long,
        partial: Boolean,
        headOnly: Boolean
    ) {
        val out = s.getOutputStream()
        val length = if (total > 0 && end >= start) end - start + 1 else 0L
        val head = StringBuilder()
            .append("HTTP/1.0 ").append(if (partial) "206 Partial Content" else "200 OK").append("\r\n")
            .append("Content-Type: application/octet-stream\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Content-Length: ").append(length).append("\r\n")
        if (partial) {
            head.append("Content-Range: bytes ")
                .append(start).append('-').append(end).append('/').append(total)
                .append("\r\n")
        }
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        // HEAD: solo encabezados. El cuerpo se streamea por chunks:
        // jamás se carga el archivo entero en memoria.
        if (!headOnly && length > 0) {
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
