package com.panda.pkgbrowser

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Orden de instalación al Remote Package Installer
 * (patrón validado en PS4 Stream Bridge):
 * POST con socket crudo estilo `curl --http0.9` — HTTP/1.0 simple,
 * sin `Expect: 100-continue` ni chunked, porque el RPI es quisquilloso.
 *
 * Vale para el RPI de flatz (puerto 12800) y para NovapsRPI (fork que
 * escucha en el puerto 12801): el protocolo es el mismo; el caller elige
 * [port]. La PS4 descarga el PKG directamente de [fileUrl] vía BGFT,
 * sin puente en el teléfono.
 */
object Ps4Sender {

    fun sendInstall(ps4Ip: String, port: Int, fileUrl: String): Ps4SendResult {
        return try {
            val body = """{"type":"direct","packages":["$fileUrl"]}"""
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ps4Ip, port), 10_000)
                sock.soTimeout = 20_000
                val out = sock.getOutputStream()
                val req = StringBuilder()
                    .append("POST /api/install HTTP/1.0\r\n")
                    .append("Host: ").append(ps4Ip).append(':').append(port).append("\r\n")
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
