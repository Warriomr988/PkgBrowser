package com.panda.pkgbrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Lista de descargas (nombre, tamaño, progreso, estado) con ⏸/▶/❌ y,
 * por cada descarga completa, el botón "⬆ Enviar a PS4".
 * Arriba: campo de IP de la PS4, recordada en SharedPreferences.
 */
class DownloadsActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var ps4IpEdit: EditText
    private val adapter = DownloadAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshList()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        title = getString(R.string.downloads)

        listView = findViewById(R.id.downloadList)
        ps4IpEdit = findViewById(R.id.ps4Ip)
        listView.adapter = adapter

        val prefs = getSharedPreferences("pkgbrowser", Context.MODE_PRIVATE)
        ps4IpEdit.setText(prefs.getString("ps4_ip", "192.168.1.88"))

        findViewById<Button>(R.id.btnSaveIp).setOnClickListener {
            val ip = ps4IpEdit.text.toString().trim()
            prefs.edit().putString("ps4_ip", ip).apply()
            Toast.makeText(this, "IP de la PS4 guardada", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnBrowser).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnStopBridge).setOnClickListener {
            DownloadService.stopBridge(this)
            refreshBridgeBanner()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun refreshList() {
        val db = DownloadDb(this)
        try {
            adapter.items = db.listItems()
            adapter.notifyDataSetChanged()
        } finally {
            db.close()
        }
        refreshBridgeBanner()
    }

    /** Banner "🌉 Puente activo" con botón Detener (se refresca cada segundo). */
    private fun refreshBridgeBanner() {
        val banner = findViewById<LinearLayout>(R.id.bridgeBanner)
        if (DownloadService.bridgeActive) {
            banner.visibility = View.VISIBLE
            findViewById<TextView>(R.id.bridgeBannerText).text =
                "🌉 Puente activo: ${DownloadService.bridgeFileName ?: ""}"
        } else {
            banner.visibility = View.GONE
        }
    }

    private fun ps4Ip(): String {
        val t = ps4IpEdit.text.toString().trim()
        return t.ifEmpty { "192.168.1.88" }
    }

    private fun stateText(item: DownloadItem): String {
        return when (item.state) {
            DownloadState.QUEUED -> "En cola"
            DownloadState.DOWNLOADING -> "Descargando"
            DownloadState.PAUSED -> "Pausada"
            DownloadState.COMPLETED -> "Completada"
            DownloadState.FAILED -> "Error: ${item.error ?: "?"}"
            DownloadState.CANCELLED -> "Cancelada"
        }
    }

    private fun confirmCancel(item: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar descarga")
            .setMessage("¿Eliminar \"${item.fileName}\" y su archivo parcial?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DownloadService.cancelDownload(this, item.id)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Flujo "⬆ Enviar a PS4": pide al [DownloadService] que levante el puente
     * persistente sirviendo el archivo en http://<ip-lan>:8080/pkg y manda la
     * orden al RPI de GoldHEN.
     *
     * El puente queda ACTIVO (con notificación) hasta que el usuario lo
     * detenga: la PS4 tarda minutos/horas en bajar el PKG. Bug v1.0: el
     * servidor vivía en un hilo de esta actividad y se detenía en el `finally`
     * a los segundos del 200 OK, dejando a la PS4 sin servidor a mitad de la
     * descarga ("No se ha podido descargar").
     */
    private fun sendToPs4(item: DownloadItem) {
        val file = File(getExternalFilesDir(null), item.fileName)
        if (!file.exists()) {
            Toast.makeText(this, "Archivo no encontrado en el teléfono", Toast.LENGTH_LONG).show()
            return
        }
        val ip = ps4Ip()
        val dlg = AlertDialog.Builder(this)
            .setTitle("Enviando a PS4…")
            .setMessage("Levantando puente y contactando $ip")
            .setCancelable(false)
            .create()
        dlg.show()

        Thread {
            try {
                val lanIp = NetUtils.getLanIp()
                    ?: throw IllegalStateException("No se encontró la IP LAN del teléfono")
                // El puente vive en el servicio: sobrevive a esta pantalla y al
                // diálogo. NO se detiene aquí.
                DownloadService.startBridge(this@DownloadsActivity, file)
                val fileUrl = "http://$lanIp:8080/pkg"
                val result = Ps4Sender.sendInstall(ip, fileUrl)
                runOnUiThread {
                    dlg.dismiss()
                    when (result) {
                        is Ps4SendResult.Ok -> AlertDialog.Builder(this)
                            .setTitle("🌉 Puente activo")
                            .setMessage(
                                "Orden aceptada (${result.statusLine}).\n\n" +
                                    "Deja la app abierta hasta que la PS4 termine de " +
                                    "descargar e instalar; luego detén el puente con ⏹."
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        is Ps4SendResult.Err -> {
                            // Sin orden aceptada no tiene sentido dejar el puente arriba.
                            DownloadService.stopBridge(this@DownloadsActivity)
                            AlertDialog.Builder(this)
                                .setTitle("Error")
                                .setMessage(result.message)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    refreshList()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    DownloadService.stopBridge(this@DownloadsActivity)
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private inner class DownloadAdapter : BaseAdapter() {
        var items: List<DownloadItem> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            val item = items[position]

            view.findViewById<TextView>(R.id.txtName).text = item.fileName
            view.findViewById<TextView>(R.id.txtInfo).text =
                "${item.sizeText} · ${stateText(item)}"
            view.findViewById<ProgressBar>(R.id.rowProgress).progress = item.progressPercent

            val btnToggle = view.findViewById<Button>(R.id.btnToggle)
            val btnCancel = view.findViewById<Button>(R.id.btnCancel)
            val btnSend = view.findViewById<Button>(R.id.btnSendPs4)

            // Reset por reciclaje de vistas.
            btnToggle.visibility = View.VISIBLE
            btnSend.visibility = if (item.state == DownloadState.COMPLETED) View.VISIBLE else View.GONE

            when (item.state) {
                DownloadState.DOWNLOADING -> {
                    btnToggle.text = getString(R.string.pause)
                    btnToggle.setOnClickListener {
                        DownloadService.pauseDownload(this@DownloadsActivity, item.id)
                    }
                }
                DownloadState.PAUSED, DownloadState.QUEUED, DownloadState.FAILED -> {
                    btnToggle.text = getString(R.string.resume)
                    btnToggle.setOnClickListener {
                        DownloadService.startDownload(this@DownloadsActivity, item.id)
                    }
                }
                DownloadState.COMPLETED, DownloadState.CANCELLED -> {
                    btnToggle.visibility = View.GONE
                }
            }
            btnCancel.setOnClickListener { confirmCancel(item) }
            btnSend.setOnClickListener { sendToPs4(item) }
            return view
        }
    }
}
