package com.panda.pkgbrowser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import java.net.URLEncoder

/**
 * Navegador dentro de la app (patrón 1DM): WebView a pantalla completa con
 * barra de direcciones; [IDMStyleDownloadListener] captura las descargas
 * heredando la sesión (cookies + referer + UA).
 */
class BrowserActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var pageProgress: ProgressBar

    /** URL de la página actual; el DownloadListener la usa como Referer. */
    val currentPageUrl: String?
        get() = if (::webView.isInitialized) webView.url else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        pageProgress = findViewById(R.id.pageProgress)

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
        urlBar.setOnEditorActionListener { _, _, _ ->
            loadFromBar()
            true
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false // navegación interna, no abrir otro navegador
            }

            override fun onPageFinished(view: WebView, url: String) {
                urlBar.setText(url)
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
        webView.setDownloadListener(IDMStyleDownloadListener(this))

        if (savedInstanceState == null) {
            webView.loadUrl("https://www.google.com")
        } else {
            webView.restoreState(savedInstanceState)
        }

        requestNotificationPermission()
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
