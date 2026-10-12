package com.panda.pkgbrowser

import android.app.Activity
import android.app.AlertDialog
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
 * Flujo común "sonda → diálogo de confirmación → encolar" (patrón 1DM):
 * 1. Hereda la sesión: copia las cookies, el referer y el user-agent a la
 *    petición de descarga. Así baja contenido que exige login.
 * 2. Sonda previa ligera (`Range: bytes=0-0`) para leer el nombre real
 *    (`Content-Disposition`) y el tamaño (`Content-Range`/`Content-Length`).
 * 3. Diálogo de confirmación con nombre editable y encolado en el motor.
 *
 * Extraído de IDMStyleDownloadListener (v1.0) sin cambios de lógica para
 * reutilizarlo desde el resolvedor de enlaces (v1.2).
 */
object DownloadStarter {

    /**
     * @param activity actividad para diálogos/toasts (hilo UI).
     * @param url enlace directo del archivo.
     * @param userAgent UA con el que se obtuvo el enlace (o null → default).
     * @param referer página de origen (o null).
     * @param contentDisposition/contentLength/mimetype pistas previas si se
     *        conocen (las del DownloadListener); la sonda las revalida.
     */
    fun start(
        activity: Activity,
        url: String,
        userAgent: String?,
        referer: String?,
        contentDisposition: String? = null,
        mimetype: String? = null,
        contentLength: Long = -1L
    ) {
        if (url.isBlank()) return
        val ua = userAgent?.takeIf { it.isNotBlank() }
            ?: System.getProperty("http.agent").orEmpty()
        // CookieManager se lee en el hilo UI.
        val cookies = try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Exception) {
            null
        }

        Thread {
            val probe = probeUrl(url, ua, contentDisposition, mimetype, contentLength, cookies, referer)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                when (probe) {
                    is ProbeResult.Ok -> showConfirmDialog(activity, probe, url)
                    is ProbeResult.Failed ->
                        Toast.makeText(
                            activity,
                            "No se pudo iniciar la descarga: ${probe.reason}",
                            Toast.LENGTH_LONG
                        ).show()
                }
            }
        }.start()
    }

    private fun probeUrl(
        url: String,
        ua: String,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long,
        cookies: String?,
        referer: String?
    ): ProbeResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", ua)
                if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
                if (!referer.isNullOrEmpty()) setRequestProperty("Referer", referer)
                // Sonda ligera: pide solo el primer byte.
                setRequestProperty("Range", "bytes=0-0")
            }
            conn.connect()
            val code = conn.responseCode
            // v1.2: fallo rápido en errores HTTP (un 403/404 no se arregla
            // reintentando; antes la sonda lo trataba como "Ok" de 14 B).
            if (code >= 400) {
                return ProbeResult.Failed(friendlyHttpError(code))
            }
            val total: Long = when (code) {
                HttpURLConnection.HTTP_PARTIAL ->
                    parseContentRange(conn.getHeaderField("Content-Range"))
                        ?: conn.getHeaderField("Content-Length")?.toLongOrNull()
                        ?: contentLength
                else ->
                    conn.getHeaderField("Content-Length")?.toLongOrNull() ?: contentLength
            }
            val acceptRanges = code == HttpURLConnection.HTTP_PARTIAL ||
                conn.getHeaderField("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
            // conn.url trae la URL final tras redirects: mejor para el nombre.
            val finalUrl = try {
                conn.url.toString()
            } catch (_: Exception) {
                url
            }
            // v1.2: la cadena cdn → direct puede cambiar de host; refrescar
            // las cookies para el host final (hereda la sesión completa).
            val effectiveCookies = if (!finalUrl.equals(url, ignoreCase = true)) {
                try {
                    CookieManager.getInstance().getCookie(finalUrl)
                        ?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                } ?: cookies
            } else {
                cookies
            }
            val contentType = conn.contentType?.lowercase().orEmpty()
            val disposition = conn.getHeaderField("Content-Disposition") ?: contentDisposition
            // v1.2: si el servidor devolvió una página web en vez del archivo
            // (típico de token firmado muerto), no encolarlo como descarga.
            if (!disposition.orEmpty().contains("attachment", ignoreCase = true) &&
                (contentType.contains("text/html") || contentType.contains("text/plain")) &&
                total in 1..4096
            ) {
                return ProbeResult.Failed(
                    "El servidor devolvió una página web en vez del archivo " +
                        "(HTTP $code). Si el enlace es firmado, ábrelo en el " +
                        "navegador de la app para heredar la sesión."
                )
            }
            val name = NetUtils.guessFileName(
                finalUrl,
                disposition,
                mimetype
            )
            ProbeResult.Ok(name, total, acceptRanges, effectiveCookies, referer, ua)
        } catch (e: Exception) {
            ProbeResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** "bytes 0-0/12345678" → 12345678 */
    private fun parseContentRange(header: String?): Long? {
        if (header == null) return null
        val slash = header.lastIndexOf('/')
        if (slash < 0) return null
        return header.substring(slash + 1).trim().toLongOrNull()
    }

    /** v1.2: mensajes claros por código HTTP (fallo rápido, sin reintentos inútiles). */
    private fun friendlyHttpError(code: Int): String = when (code) {
        401 -> "Acceso denegado (401): el enlace requiere autenticación"
        403 -> "Acceso denegado (403): el servidor rechazó la petición. " +
            "Si el enlace es firmado, ábrelo en el navegador de la app para heredar la sesión"
        404 -> "No encontrado (404): el enlace ya no existe o expiró"
        429 -> "Demasiadas peticiones (429): espera un poco y reintenta"
        in 500..599 -> "Error del servidor ($code): reintenta más tarde"
        else -> "HTTP $code"
    }

    private fun showConfirmDialog(activity: Activity, probe: ProbeResult.Ok, url: String) {
        val input = EditText(activity).apply {
            setText(probe.fileName)
            setSelection(0, probe.fileName.length)
            hint = "Nombre del archivo"
        }
        val sizeText = if (probe.totalBytes > 0) formatBytes(probe.totalBytes) else "tamaño desconocido"
        AlertDialog.Builder(activity)
            .setTitle("Descargar archivo")
            .setMessage("$sizeText\n$url")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = NetUtils.sanitizeFileName(input.text.toString())
                enqueueDownload(activity, url, name, probe)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun enqueueDownload(activity: Activity, url: String, fileName: String, probe: ProbeResult.Ok) {
        val db = DownloadDb(activity)
        val id = try {
            db.insertDownload(
                url, fileName, probe.totalBytes,
                probe.cookies, probe.referer, probe.userAgent, probe.acceptRanges
            )
        } finally {
            db.close()
        }
        if (id < 0) {
            Toast.makeText(activity, "No se pudo encolar la descarga", Toast.LENGTH_SHORT).show()
            return
        }
        DownloadService.startDownload(activity, id)
        Toast.makeText(activity, "Descarga encolada", Toast.LENGTH_SHORT).show()
    }
}
