package com.panda.pkgbrowser

import java.net.NetworkInterface
import java.net.URL
import java.net.URLDecoder

object NetUtils {

    /** Primera IPv4 site-local no-loopback: la IP LAN del teléfono. */
    fun getLanIp(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            // Pasada 1: site-local (192.168.x.x, 10.x.x.x, 172.16.x.x)
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (host.contains(":")) continue // IPv6
                    if (addr.isSiteLocalAddress) return host
                }
            }
            // Pasada 2: cualquier IPv4 no-loopback
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (!host.contains(":")) return host
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Nombre real del archivo: primero Content-Disposition (con soporte
     * RFC 5987 `filename*=UTF-8''...`), luego el último segmento de la URL.
     */
    fun guessFileName(url: String, contentDisposition: String?, mimetype: String?): String {
        if (!contentDisposition.isNullOrBlank()) {
            // filename*=UTF-8''nombre%20con%20espacios.pkg
            val star = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(contentDisposition)
            if (star != null) {
                var v = star.groupValues[1].trim().trim('"')
                val idx = v.indexOf("''")
                if (idx >= 0) v = v.substring(idx + 2)
                v = decodeQuietly(v)
                if (v.isNotBlank()) return sanitizeFileName(v)
            }
            // filename="nombre.pkg" o filename=nombre.pkg
            val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(contentDisposition)
            if (plain != null) {
                val v = decodeQuietly(plain.groupValues[1].trim())
                if (v.isNotBlank()) return sanitizeFileName(v)
            }
        }
        try {
            val path = URL(url).path ?: ""
            val last = path.substringAfterLast('/').substringBefore('?')
            val decoded = decodeQuietly(last)
            if (decoded.isNotBlank()) return sanitizeFileName(decoded)
        } catch (_: Exception) {
        }
        // NOTA v1.1: usar mimetype para detectar la extensión cuando la URL no trae nombre.
        return "descarga.pkg"
    }

    fun sanitizeFileName(name: String): String {
        var n = name.trim().replace(Regex("""[/\\?%*:|"<>]"""), "_").trim()
        if (n.isEmpty()) n = "descarga.pkg"
        if (n.length > 120) n = n.take(120)
        return n
    }

    private fun decodeQuietly(v: String): String {
        return try {
            URLDecoder.decode(v, "UTF-8")
        } catch (_: Exception) {
            v
        }
    }
}
