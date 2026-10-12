package com.panda.pkgbrowser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.URL
import java.net.URLEncoder

/**
 * Navegador dentro de la app (patrón 1DM): WebView a pantalla completa con
 * barra de direcciones; [IDMStyleDownloadListener] captura las descargas
 * heredando la sesión (cookies + referer + UA).
 *
 * v1.2: [ResourceSniffer] olfatea recursos descargables durante la
 * navegación y muestra un banner no intrusivo para descargarlos con la
 * sesión; [BlobHook] registra las URLs pedidas por fetch/XHR para resolver
 * descargas `blob:`; cookies de terceros activadas para el paso de
 * verificación de Cloudflare.
 */
class BrowserActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var pageProgress: ProgressBar
    private lateinit var sniffBanner: TextView
    private lateinit var sniffer: ResourceSniffer

    /** URL candidata detectada por el sniffer (pendiente de confirmación). */
    private var sniffCandidateUrl: String? = null

    /** URL de la página actual; el DownloadListener la usa como Referer. */
    val currentPageUrl: String?
        get() = if (::webView.isInitialized) webView.url else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        pageProgress = findViewById(R.id.pageProgress)
        sniffBanner = findViewById(R.id.sniffBanner)

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        findViewById<Button>(R.id.btnForward).setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        findViewById<Button>(R.id.btnReload).setOnClickListener { webView.reload() }
        findViewById<Button>(R.id.btnGo).setOnClickListener { loadFromBar() }
        findViewById<Button>(R.id.btnDownloads).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        // v1.2: resolvedor automático de enlaces directos.
        findViewById<Button>(R.id.btnResolve).setOnClickListener { showResolveDialog() }
        sniffBanner.setOnClickListener { downloadSniffed() }
        urlBar.setOnEditorActionListener { _, _, _ ->
            loadFromBar()
            true
        }

        // Sesión completa para hosters: cookies propias y de terceros
        // (la verificación de Cloudflare usa iframes de terceros).
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        sniffer = ResourceSniffer { url ->
            // shouldInterceptRequest corre en hilo de red: volver al UI.
            runOnUiThread { showSniffBanner(url) }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                sniffer.sniffRequest(request.url?.toString(), request.method)
                return null // olfatear, no interceptar de verdad
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Si la navegación va a un archivo, la tomamos nosotros con
                // la sesión completa (mismo camino que el DownloadListener).
                return sniffer.handleNavigation(request.url?.toString())
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                hideSniffBanner()
                sniffer.reset()
            }

            override fun onPageFinished(view: WebView, url: String) {
                urlBar.setText(url)
                BlobHook.install(view)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                pageProgress.progress = newProgress
                pageProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                setTitle(title)
            }
        }
        webView.setDownloadListener(IDMStyleDownloadListener(this) {
            if (::webView.isInitialized) webView else null
        })

        if (savedInstanceState == null) {
            webView.loadUrl("https://www.google.com")
        } else {
            webView.restoreState(savedInstanceState)
        }

        requestNotificationPermission()
    }

    /** Banner no intrusivo: "descarga detectada, toca para descargar". */
    private fun showSniffBanner(url: String) {
        if (isFinishing) return
        sniffCandidateUrl = url
        val name = try {
            URL(url).path.substringAfterLast('/').substringBefore('?')
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } ?: "archivo"
        sniffBanner.text = "🔗 Descarga detectada: $name — toca para descargar"
        sniffBanner.visibility = View.VISIBLE
    }

    private fun hideSniffBanner() {
        sniffBanner.visibility = View.GONE
        sniffCandidateUrl = null
    }

    private fun downloadSniffed() {
        val url = sniffCandidateUrl ?: return
        hideSniffBanner()
        DownloadStarter.start(
            this, url,
            if (::webView.isInitialized) webView.settings.userAgentString else null,
            currentPageUrl
        )
    }

    private fun loadFromBar() {
        var text = urlBar.text.toString().trim()
        if (text.isEmpty()) return
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            text = if (text.contains(".") && !text.contains(" ")) {
                "https://$text"
            } else {
                "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
            }
        }
        webView.loadUrl(text)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }

    /**
     * v1.2 — Resolvedor: pega la URL de la página y la app obtiene sola el
     * enlace directo (WebView oculto + sniffer, hereda la sesión para que
     * los tokens firmados no den 403).
     */
    private fun showResolveDialog() {
        val input = EditText(this).apply {
            setText(currentPageUrl ?: "")
            hint = "URL de la página con el enlace"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("🔍 Resolver enlace directo")
            .setMessage("Pega la URL de la página y la app busca sola el enlace directo del archivo.")
            .setView(input)
            .setPositiveButton("Resolver") { _, _ ->
                val page = input.text.toString().trim()
                if (page.isNotEmpty()) startResolving(page)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startResolving(pageUrl: String) {
        var handle: LinkResolver.CancelHandle? = null
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(48, 24, 48, 24)
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Buscando enlace directo…")
            .setView(progressBar)
            .setNegativeButton("Cancelar") { _, _ -> handle?.cancel() }
            .setOnCancelListener { handle?.cancel() }
            .show()
        handle = LinkResolver.resolve(this, pageUrl, object : LinkResolver.Callback {
            override fun onResolved(directUrl: String, userAgent: String) {
                if (!this@BrowserActivity.isFinishing) progressDialog.dismiss()
                // El referer es la página: hereda la sesión donde se generó el token.
                DownloadStarter.start(this@BrowserActivity, directUrl, userAgent, pageUrl)
            }

            override fun onFailed(reason: String) {
                if (!this@BrowserActivity.isFinishing) progressDialog.dismiss()
                Toast.makeText(this@BrowserActivity, reason, Toast.LENGTH_LONG).show()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
