package com.panda.pkgbrowser

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolvedor automático de enlaces directos (v1.2).
 *
 * Carga la URL de la PÁGINA en un WebView oculto (1×1, invisible) que
 * comparte la sesión del navegador (mismo CookieManager) y olfatea el
 * enlace directo del archivo por tres vías:
 * 1. `shouldInterceptRequest`: si alguna petición apunta a un archivo
 *    (.pkg, .zip, …), listo.
 * 2. DownloadListener del WebView oculto: si la página dispara una
 *    descarga (redirect directo al archivo), listo.
 * 3. JS inyectado al terminar la página (y reintentos a los 6 y 12 s, por
 *    si el enlace se genera tarde): cosecha `<a href>`, fuentes de
 *    video/audio y `action` de formularios; filtra por extensión y, si no
 *    hay coincidencia, sondea (Range 0-0) buscando Content-Type de descarga
 *    u `attachment`. Si hay varios candidatos, el usuario elige.
 *
 * v1.2: el WebView oculto también acepta cookies de terceros (verificación
 * tipo Cloudflare) e instala [BlobHook]: si la página pide el archivo por
 * fetch/XHR y luego lo ofrece como `blob:`, se recupera la URL origen.
 *
 * Sin código por sitio: todo es heurística genérica.
 */
object LinkResolver {

    private val FILE_EXTENSIONS = listOf(".pkg", ".zip", ".rar", ".7z", ".iso", ".bin")
    private const val TIMEOUT_MS = 45_000L
    private const val MAX_PROBE_CANDIDATES = 10

    interface Callback {
        fun onResolved(directUrl: String, userAgent: String)
        fun onFailed(reason: String)
    }

    interface CancelHandle {
        fun cancel()
    }

    /** Inicia la resolución; devuelve un handle para cancelarla. */
    fun resolve(activity: Activity, pageUrl: String, callback: Callback): CancelHandle {
        val session = ResolveSession(activity, pageUrl, callback)
        session.start()
        return session
    }

    private class ResolveSession(
        private val activity: Activity,
        private val pageUrl: String,
        private val callback: Callback
    ) : CancelHandle {

        private val ui = Handler(Looper.getMainLooper())
        private var webView: WebView? = null
        private var container: FrameLayout? = null
        private var userAgent: String = System.getProperty("http.agent").orEmpty()

        @Volatile
        private var finished = false

        private val timeoutRunnable = Runnable {
            fail("Tiempo agotado: no se encontró enlace directo en la página")
        }

        fun start() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                ui.post { start() }
                return
            }
            if (activity.isFinishing) {
                callback.onFailed("Actividad cerrada")
                return
            }
            try {
                val wv = WebView(activity)
                userAgent = wv.settings.userAgentString ?: userAgent
                val holder = FrameLayout(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(1, 1)
                    addView(wv, FrameLayout.LayoutParams(1, 1))
                    visibility = View.INVISIBLE
                }
                activity.findViewById<ViewGroup>(android.R.id.content).addView(holder)
                webView = wv
                container = holder

                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                // v1.2: cookies de terceros también en el resolvedor (la
                // verificación de hosters como Cloudflare usa iframes ajenos).
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.webViewClient = SnifferClient()
                wv.setDownloadListener(DownloadListener { url, _, _, _, _ ->
                    if (!url.isNullOrBlank()) finish(url)
                })
                ui.postDelayed(timeoutRunnable, TIMEOUT_MS)
                wv.loadUrl(pageUrl)
            } catch (e: Exception) {
                fail("No se pudo abrir el resolvedor: ${e.message}")
            }
        }

        override fun cancel() {
            fail("Resolución cancelada")
        }

        @Synchronized
        private fun finish(url: String) {
            if (finished) return
            finished = true
            cleanup()
            // Contrato: el callback siempre llega en el hilo UI (finish puede
            // venir del hilo de red de shouldInterceptRequest).
            ui.post { callback.onResolved(url, userAgent) }
        }

        @Synchronized
        private fun fail(reason: String) {
            if (finished) return
            finished = true
            cleanup()
            ui.post { callback.onFailed(reason) }
        }

        private fun failOnUi(reason: String) {
            ui.post { fail(reason) }
        }

        private fun cleanup() {
            val run = {
                ui.removeCallbacks(timeoutRunnable)
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Exception) {
                }
                webView = null
                try {
                    container?.let { c -> (c.parent as? ViewGroup)?.removeView(c) }
                } catch (_: Exception) {
                }
                container = null
            }
            if (Looper.myLooper() == Looper.getMainLooper()) run() else ui.post(run)
        }

        private inner class SnifferClient : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url?.toString() ?: return null
                if (isFileUrl(url)) {
                    // Corre en un hilo de red del WebView: finish es sincronizado.
                    finish(url)
                }
                return null // no interceptar de verdad, solo olfatear
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString().orEmpty()
                if (isFileUrl(url)) {
                    finish(url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                // v1.2: enganchar fetch/XHR antes de cosechar, por si la
                // página pide el archivo por red y lo ofrece como blob:.
                BlobHook.install(view)
                // El enlace puede generarse tarde con JS: cosechar ahora y
                // reintentar a los 6 y 12 s (el timeout global es 45 s).
                for (delay in longArrayOf(0L, 6_000L, 12_000L)) {
                    ui.postDelayed({ if (!finished) harvestLinks(view) }, delay)
                }
            }
        }

        private fun harvestLinks(view: WebView) {
            val js = "(function(){var out=[];function p(u){if(u&&out.indexOf(u)<0)out.push(u);}" +
                "var as=document.querySelectorAll('a[href]');for(var i=0;i<as.length;i++)p(as[i].href);" +
                "var ms=document.querySelectorAll('video,audio');for(var j=0;j<ms.length;j++){p(ms[j].src);p(ms[j].currentSrc);}" +
                "var ss=document.querySelectorAll('video source,audio source');for(var k=0;k<ss.length;k++)p(ss[k].src);" +
                "var fs=document.querySelectorAll('form[action]');for(var f=0;f<fs.length;f++)p(fs[f].action);" +
                // v1.2: URLs pedidas por fetch/XHR (enganche de BlobHook): si la
                // página luego ofrece un blob:, esta es la URL origen real.
                "if(window.__pkgb_urls){for(var h=0;h<window.__pkgb_urls.length;h++)p(window.__pkgb_urls[h]);}" +
                "return JSON.stringify(out);})()"
            // evaluateJavascript corre en el hilo UI: aquí se pueden leer
            // las cookies con seguridad para la sonda posterior.
            val cookies = try {
                CookieManager.getInstance().getCookie(pageUrl)
            } catch (_: Exception) {
                null
            }
            try {
                view.evaluateJavascript(js) { json ->
                    Thread { processHarvest(json, cookies) }.start()
                }
            } catch (_: Exception) {
                // WebView destruido entre el chequeo y la llamada: ignorar.
            }
        }

        private fun processHarvest(json: String?, cookies: String?) {
            if (finished) return
            val urls = try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { it.isNotBlank() }
                }
            } catch (_: Exception) {
                emptyList()
            }
            // 1) Coincidencia por extensión (.pkg primero).
            val byExt = urls.filter { isFileUrl(it) }.sortedBy { rankExtension(it) }
            if (byExt.isNotEmpty()) {
                if (byExt.size == 1) {
                    finishOnUi(byExt[0])
                } else {
                    ui.post { if (!finished) showChooser(byExt) }
                }
                return
            }
            // 2) Sin extensión clara: sondear por mimetype (límite de candidatos).
            val candidates = urls.filterNot { isBlobOrData(it) }.take(MAX_PROBE_CANDIDATES)
            for (raw in candidates) {
                if (finished) return
                if (isDownloadByMime(raw, cookies)) {
                    finishOnUi(raw)
                    return
                }
            }
            if (!finished) failOnUi("No se encontró ningún enlace directo en la página")
        }

        private fun finishOnUi(url: String) {
            ui.post { finish(url) }
        }

        private fun showChooser(candidates: List<String>) {
            if (finished || activity.isFinishing) return
            val display = candidates.map {
                if (it.length > 90) "…" + it.takeLast(89) else it
            }.toTypedArray()
            var dialog: AlertDialog? = null
            dialog = AlertDialog.Builder(activity)
                .setTitle("Varios enlaces encontrados")
                .setMessage("Elige cuál descargar:")
                .setItems(display) { _, which ->
                    dialog?.dismiss()
                    finish(candidates[which])
                }
                .setNegativeButton("Cancelar") { _, _ -> fail("Resolución cancelada") }
                .setOnCancelListener { fail("Resolución cancelada") }
                .show()
        }

        /** Sonda Range 0-0: ¿el servidor lo sirve como descarga? */
        private fun isDownloadByMime(url: String, cookies: String?): Boolean {
            var conn: HttpURLConnection? = null
            return try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                    if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
                    setRequestProperty("Referer", pageUrl)
                    setRequestProperty("Range", "bytes=0-0")
                }
                conn.connect()
                val ct = conn.contentType?.lowercase().orEmpty()
                val cd = conn.getHeaderField("Content-Disposition").orEmpty()
                ct.contains("octet-stream") || ct.contains("x-pkg") ||
                    ct.contains("force-download") ||
                    cd.contains("attachment", ignoreCase = true)
            } catch (_: Exception) {
                false
            } finally {
                conn?.disconnect()
            }
        }

        private fun isBlobOrData(url: String): Boolean {
            return url.startsWith("blob:", ignoreCase = true) ||
                url.startsWith("data:", ignoreCase = true)
        }

        private fun isFileUrl(url: String): Boolean {
            if (isBlobOrData(url)) return false
            val path = url.lowercase().substringBefore('?').substringBefore('#')
            return FILE_EXTENSIONS.any { path.endsWith(it) }
        }

        private fun rankExtension(url: String): Int {
            val path = url.lowercase().substringBefore('?').substringBefore('#')
            return if (path.endsWith(".pkg")) 0 else 1
        }
    }
}
