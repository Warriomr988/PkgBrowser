package com.panda.pkgbrowser

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Orden de instalación al Remote Package Installer de GoldHEN
 * (patrón validado en PS4 Stream Bridge):
 * POST con socket crudo estilo `curl --http0.9` — HTTP/1.0 simple,
 * sin `Expect: 100-continue` ni chunked, porque el RPI es quisquilloso.
 */
object Ps4Sender {

    private const val RPI_PORT = 12800

    fun sendInstall(ps4Ip: String, fileUrl: String): Ps4SendResult {
        return try {
            val body = """{"type":"direct","packages":["$fileUrl"]}"""
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ps4Ip, RPI_PORT), 10_000)
                sock.soTimeout = 20_000
                val out = sock.getOutputStream()
                val req = StringBuilder()
                    .append("POST /api/install HTTP/1.0\r\n")
                    .append("Host: ").append(ps4Ip).append(':').append(RPI_PORT).append("\r\n")
                    .append("Content-Type: application/json\r\n")
                    .append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                    .append("Connection: close\r\n")
                    .append("\r\n")
                    .toString()
                out.write(req.toByteArray(Charsets.UTF_8))
                out.write(bodyBytes)
                out.flush()
                val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                val statusLine = reader.readLine()
                if (statusLine == null) {
                    Ps4SendResult.Err("La PS4 no respondió")
                } else if (statusLine.contains("200")) {
                    Ps4SendResult.Ok(statusLine.trim())
                } else {
                    Ps4SendResult.Err("El RPI respondió: $statusLine")
                }
            }
        } catch (e: Exception) {
            Ps4SendResult.Err(e.message ?: e.javaClass.simpleName)
        }
    }
}
