package com.panda.pkgbrowser

import android.app.AlertDialog
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.widget.EditText
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
 * Patrón estilo 1DM+ (implementación propia, clean-room):
 * 1. Hereda la sesión del navegador: copia las cookies del WebView, el referer
 *    (página actual) y el user-agent a la petición de descarga. Así baja
 *    contenido que exige login.
 * 2. Sonda previa ligera (`Range: bytes=0-0`) para leer el nombre real
 *    (`Content-Disposition`) y el tamaño (`Content-Range`/`Content-Length`).
 * 3. Diálogo de confirmación con nombre editable y encolado en el motor.
 */
class IDMStyleDownloadListener(
    private val activity: BrowserActivity
) : DownloadListener {

    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        if (url.isNullOrBlank()) return
        val ua = userAgent?.takeIf { it.isNotBlank() }
            ?: System.getProperty("http.agent").orEmpty()
        // CookieManager se lee en el hilo UI.
        val cookies = try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Exception) {
            null
        }
        val referer = activity.currentPageUrl

        Thread {
            val probe = probeUrl(url, ua, contentDisposition, mimetype, contentLength, cookies, referer)
            activity.runOnUiThread {
                when (probe) {
                    is ProbeResult.Ok -> showConfirmDialog(probe, url)
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
            val name = NetUtils.guessFileName(
                finalUrl,
                conn.getHeaderField("Content-Disposition") ?: contentDisposition,
                mimetype
            )
            ProbeResult.Ok(name, total, acceptRanges, cookies, referer, ua)
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

    private fun showConfirmDialog(probe: ProbeResult.Ok, url: String) {
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
                enqueueDownload(url, name, probe)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun enqueueDownload(url: String, fileName: String, probe: ProbeResult.Ok) {
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
