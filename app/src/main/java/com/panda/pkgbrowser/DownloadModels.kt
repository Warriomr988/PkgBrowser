package com.panda.pkgbrowser

/** Estados posibles de una descarga. */
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/** Fila de la tabla `downloads`. */
data class DownloadItem(
    val id: Long,
    val url: String,
    val fileName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val state: DownloadState,
    val error: String?,
    val createdAt: Long
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0

    val sizeText: String
        get() = if (totalBytes > 0) {
            "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
        } else {
            formatBytes(downloadedBytes)
        }
}

/** Trozo de descarga asignado a un hilo (tabla `thread_info`). end = -1 → hasta el final. */
data class ThreadSlice(
    val downloadId: Long,
    val threadId: Int,
    val start: Long,
    val end: Long,
    val finished: Long
)

/** Resultado de la sonda previa (patrón 1DM: hereda sesión + lee Content-Disposition). */
sealed interface ProbeResult {
    data class Ok(
        val fileName: String,
        val totalBytes: Long,
        val acceptRanges: Boolean,
        val cookies: String?,
        val referer: String?,
        val userAgent: String
    ) : ProbeResult

    data class Failed(val reason: String) : ProbeResult
}

/** Resultado del envío al Remote Package Installer de GoldHEN. */
sealed interface Ps4SendResult {
    data class Ok(val statusLine: String) : Ps4SendResult
    data class Err(val message: String) : Ps4SendResult
}

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
}
