package com.panda.pkgbrowser

import android.webkit.WebView
import org.json.JSONArray

/**
 * Soporte `blob:` estilo 1DM (v1.2, implementación propia, sin copiar código).
 *
 * Muchas páginas disparan descargas creando un Blob en memoria y navegando a
 * `blob:<uuid>`. Ese esquema NO se puede descargar directamente: el contenido
 * vive solo en el renderer del WebView. La salida (el mismo truco conceptual
 * que usan los gestores con grabber) es registrar, con JS inyectado, las URLs
 * reales que la página pide por `fetch`/`XMLHttpRequest`; cuando el
 * DownloadListener recibe un `blob:`, se cruza con la última URL origen
 * capturada y se descarga ESA con la sesión del WebView (cookies+referer+UA).
 *
 * Limitación honesta: si la página genera el Blob localmente sin pedir
 * ninguna URL de red (p. ej. lo construye por JS), no hay origen que
 * recuperar y se avisa al usuario.
 */
object BlobHook {

    /**
     * Engancha fetch/XHR una sola vez por página y guarda las últimas 50
     * URLs pedidas en `window.__pkgb_urls`. Todo envuelto en try/catch para
     * no romper el JS de la página si algo falla.
     */
    private val HOOK_JS = """
        (function(){try{
        if(window.__pkgb_hooked)return;window.__pkgb_hooked=true;
        window.__pkgb_urls=[];
        function rec(u){try{
        if(typeof u==='string'&&u&&u.indexOf('blob:')!==0&&u.indexOf('data:')!==0){
        window.__pkgb_urls.push(u);
        if(window.__pkgb_urls.length>50)window.__pkgb_urls.shift();
        }}catch(e){}}
        if(window.fetch){var f=window.fetch;window.fetch=function(u,o){
        try{rec(typeof u==='string'?u:(u&&u.url));}catch(e){}
        return f.apply(this,arguments);};}
        if(window.XMLHttpRequest){var o=XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open=function(m,u){
        try{rec(u);}catch(e){}
        return o.apply(this,arguments);};}
        }catch(e){}})();
    """.trimIndent()

    /** Inyecta el enganche; llamar en `onPageFinished`. */
    fun install(view: WebView) {
        try {
            view.evaluateJavascript(HOOK_JS, null)
        } catch (_: Exception) {
        }
    }

    /**
     * Lee las URLs registradas. El callback llega en el hilo UI
     * (así lo garantiza `evaluateJavascript`).
     */
    fun getRecordedUrls(view: WebView, callback: (List<String>) -> Unit) {
        try {
            view.evaluateJavascript("(window.__pkgb_urls||[]).slice(-50)") { json ->
                callback(parseJsonArray(json))
            }
        } catch (_: Exception) {
            callback(emptyList())
        }
    }

    /** Elige la mejor candidata: extensión de archivo primero, si no la más reciente. */
    fun pickBest(urls: List<String>): String? {
        if (urls.isEmpty()) return null
        return urls.filter { ResourceSniffer.isFileUrl(it) }.lastOrNull()
            ?: urls.lastOrNull()
    }

    private fun parseJsonArray(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
