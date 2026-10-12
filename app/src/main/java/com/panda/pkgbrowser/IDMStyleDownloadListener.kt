package com.panda.pkgbrowser

import android.webkit.DownloadListener
import android.webkit.WebView
import android.widget.Toast

/**
 * Patrón estilo 1DM+ (implementación propia, clean-room): el WebView avisa
 * de la descarga y el flujo sonda → confirmación → encolado vive en
 * [DownloadStarter] (compartido con el resolvedor de enlaces de v1.2).
 *
 * v1.2: si la descarga llega como `blob:`, no se puede descargar tal cual
 * (el contenido vive en el renderer); se cruza con las URLs origen
 * registradas por [BlobHook] y se descarga la mejor candidata con la
 * sesión del WebView.
 */
class IDMStyleDownloadListener(
    private val activity: BrowserActivity,
    private val webViewProvider: () -> WebView?
) : DownloadListener {

    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        if (url.isNullOrBlank()) return
        if (url.startsWith("blob:", ignoreCase = true)) {
            resolveBlob(userAgent)
            return
        }
        DownloadStarter.start(
            activity, url, userAgent, activity.currentPageUrl,
            contentDisposition, mimetype, contentLength
        )
    }

    private fun resolveBlob(userAgent: String?) {
        val wv = webViewProvider()
        if (wv == null) {
            Toast.makeText(activity, "No se pudo resolver el enlace (blob:)", Toast.LENGTH_LONG).show()
            return
        }
        // evaluateJavascript entrega el resultado en el hilo UI.
        BlobHook.getRecordedUrls(wv) { urls ->
            if (activity.isFinishing) return@getRecordedUrls
            val best = BlobHook.pickBest(urls)
            if (best != null) {
                Toast.makeText(activity, "Origen del blob detectado, descargando…", Toast.LENGTH_SHORT).show()
                DownloadStarter.start(
                    activity, best,
                    userAgent?.takeIf { it.isNotBlank() } ?: wv.settings.userAgentString,
                    activity.currentPageUrl
                )
            } else {
                Toast.makeText(
                    activity,
                    "La página generó la descarga en memoria (blob:) sin una URL de origen. " +
                        "Prueba descargar desde el enlace directo de la página.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
