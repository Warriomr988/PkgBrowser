package com.panda.pkgbrowser

/**
 * Sniffer de recursos descargables (v1.2, patrón estilo 1DM implementado de
 * cero, sin copiar su código).
 *
 * Se engancha al `WebViewClient` del navegador visible por dos vías:
 * - `shouldInterceptRequest`: olfatea (sin interceptar de verdad) cada
 *   petición GET; si la URL apunta a un archivo descargable
 *   (.pkg/.zip/.rar/.7z/.iso/.bin/.apk) se notifica al candidato.
 * - `shouldOverrideUrlLoading`: si la navegación principal va a un archivo,
 *   se toma el control para lanzarla por [DownloadStarter] con la sesión
 *   completa del WebView (el mismo camino que el DownloadListener).
 *
 * Nunca descarga sin confirmación: solo avisa mediante `onCandidate` y la
 * actividad muestra un banner no intrusivo ("tocar para descargar").
 * Anti-ruido: cada URL se notifica como máximo una vez cada 30 s.
 */
class ResourceSniffer(
    private val onCandidate: (url: String) -> Unit
) {
    private val lock = Any()
    private val seen = LinkedHashMap<String, Long>()

    /**
     * Llamar desde `shouldInterceptRequest` (corre en un hilo de red del
     * WebView; el callback se dispara igual, la actividad lo lleva al hilo UI).
     */
    fun sniffRequest(url: String?, method: String?) {
        if (url.isNullOrBlank()) return
        if (method != null && !method.equals("GET", ignoreCase = true)) return
        if (!isFileUrl(url)) return
        if (!markSeen(url)) return
        try {
            onCandidate(url)
        } catch (_: Exception) {
        }
    }

    /**
     * Llamar desde `shouldOverrideUrlLoading`. Devuelve true si la
     * navegación era un archivo y ya quedó notificada (no navegar).
     * Los `blob:` se dejan pasar: los resuelve el DownloadListener vía [BlobHook].
     */
    fun handleNavigation(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (isBlobOrData(url)) return false
        if (!isFileUrl(url)) return false
        if (!markSeen(url)) return true // ya avisado: tampoco navegar dos veces
        try {
            onCandidate(url)
        } catch (_: Exception) {
        }
        return true
    }

    /** Llamar al empezar una página nueva para olvidar el debounce anterior. */
    fun reset() {
        synchronized(lock) { seen.clear() }
    }

    private fun markSeen(url: String): Boolean {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val last = seen[url]
            if (last != null && now - last < 30_000L) return false
            seen[url] = now
            if (seen.size > 200) {
                val cutoff = now - 60_000L
                val it = seen.entries.iterator()
                while (it.hasNext()) {
                    if (it.next().value < cutoff) it.remove()
                }
            }
            return true
        }
    }

    companion object {
        val FILE_EXTENSIONS = listOf(".pkg", ".zip", ".rar", ".7z", ".iso", ".bin", ".apk")

        fun isBlobOrData(url: String): Boolean {
            return url.startsWith("blob:", ignoreCase = true) ||
                url.startsWith("data:", ignoreCase = true)
        }

        /** ¿La URL apunta (por extensión) a un archivo descargable? */
        fun isFileUrl(url: String): Boolean {
            if (isBlobOrData(url)) return false
            val path = url.lowercase().substringBefore('?').substringBefore('#')
            if (path.isBlank()) return false
            return FILE_EXTENSIONS.any { path.endsWith(it) }
        }
    }
}
