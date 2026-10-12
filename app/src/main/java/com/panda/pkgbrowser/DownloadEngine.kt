package com.panda.pkgbrowser

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Motor de descarga multi-hilo (implementación propia, sin dependencias):
 * - Segmentos con header `Range: bytes=<inicio>-<fin>` (x4 hilos).
 * - Progreso por hilo persistido en SQLite (`thread_info`): reanuda entre reinicios.
 * - Si el servidor ignora el `Range`, baja a un solo hilo y reintenta desde cero.
 * - Hereda la sesión del navegador (cookies + referer + UA) guardada en la BD.
 */
object DownloadEngine {

    private const val THREAD_COUNT = 4
    private const val BUFFER_SIZE = 32 * 1024
    private const val PERSIST_MS = 1500L

    interface Listener {
        fun onProgress(id: Long, downloaded: Long, total: Long)
        fun onStateChanged(id: Long, state: DownloadState)
    }

    /** Interna: el servidor devolvió 200 a una petición con Range → no soporta rangos. */
    private class RangeNotSupportedException : IOException()

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val runFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val downloadedCounters = ConcurrentHashMap<Long, AtomicLong>()

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun activeCount(): Int = runFlags.size

    fun isActive(id: Long): Boolean = runFlags.containsKey(id)

    fun start(ctx: Context, id: Long) {
        if (runFlags.putIfAbsent(id, AtomicBoolean(true)) != null) return // ya en marcha
        val appCtx = ctx.applicationContext
        val db = DownloadDb(appCtx)
        val initial = try {
            db.getItem(id)?.downloadedBytes ?: 0L
        } finally {
            db.close()
        }
        downloadedCounters[id] = AtomicLong(initial)
        Thread { runDownload(appCtx, id) }.start()
    }

    fun pause(ctx: Context, id: Long) {
        val flag = runFlags.remove(id)
        downloadedCounters.remove(id)
        flag?.set(false)
        val db = DownloadDb(ctx)
        try {
            val cur = db.getItem(id)?.state
            if (cur == DownloadState.DOWNLOADING || cur == DownloadState.QUEUED) {
                db.updateState(id, DownloadState.PAUSED)
                notifyState(id, DownloadState.PAUSED)
            }
        } finally {
            db.close()
        }
    }

    fun cancel(ctx: Context, id: Long) {
        runFlags.remove(id)?.set(false)
        downloadedCounters.remove(id)
        val db = DownloadDb(ctx)
        try {
            val item = db.getItem(id)
            if (item != null) {
                File(ctx.getExternalFilesDir(null), item.fileName).delete()
            }
            db.deleteDownload(id)
        } finally {
            db.close()
        }
        notifyState(id, DownloadState.CANCELLED)
    }

    // ---------------- coordinación ----------------

    private fun runDownload(ctx: Context, id: Long) {
        val db = DownloadDb(ctx)
        try {
            var attempt = 0
            var retry: Boolean
            do {
                retry = false
                val row = db.getRow(id)
                if (row == null) {
                    runFlags.remove(id)
                    downloadedCounters.remove(id)
                    return
                }
                if (row.item.state == DownloadState.COMPLETED) {
                    runFlags.remove(id)
                    downloadedCounters.remove(id)
                    return
                }
                db.updateState(id, DownloadState.DOWNLOADING)
                notifyState(id, DownloadState.DOWNLOADING)

                var slices = db.getSlices(id)
                val file = File(ctx.getExternalFilesDir(null), row.item.fileName)
                if (slices.isEmpty() || !file.exists()) {
                    // Slices nuevos, o el parcial se perdió: empezar de cero.
                    slices = buildSlices(row, THREAD_COUNT)
                    db.setSlices(id, slices)
                    db.updateProgress(id, 0L)
                    downloadedCounters[id]?.set(0L)
                }

                val flag = runFlags[id] ?: AtomicBoolean(false)
                val counter = downloadedCounters[id] ?: AtomicLong(0L)
                val firstError = AtomicReference<Exception?>()
                val latch = CountDownLatch(slices.size)

                for (slice in slices) {
                    val s = slice
                    Thread {
                        try {
                            downloadSlice(ctx, db, row, s, flag, counter)
                        } catch (e: Exception) {
                            firstError.compareAndSet(null, e)
                            flag.set(false)
                        } finally {
                            latch.countDown()
                        }
                    }.start()
                }
                latch.await()

                val err = firstError.get()
                if (err is RangeNotSupportedException && attempt == 0 && runFlags.containsKey(id)) {
                    // El servidor mintió en la sonda: a un hilo, desde cero.
                    db.clearSlices(id)
                    file.delete()
                    db.setSlices(id, listOf(ThreadSlice(id, 0, 0L, -1L, 0L)))
                    db.updateProgress(id, 0L)
                    counter.set(0L)
                    runFlags[id] = AtomicBoolean(true) // flag fresco: el anterior quedó en false
                    attempt++
                    retry = true
                } else if (err != null) {
                    // Error real: marcar FAILED solo si el usuario no pausó/canceló.
                    if (runFlags.containsKey(id)) {
                        db.updateState(id, DownloadState.FAILED, err.message ?: err.javaClass.simpleName)
                        notifyState(id, DownloadState.FAILED)
                    }
                    runFlags.remove(id)
                    downloadedCounters.remove(id)
                } else {
                    if (runFlags.containsKey(id)) {
                        val total = row.item.totalBytes
                        val done = counter.get()
                        if (total > 0 && done < total) {
                            db.updateState(id, DownloadState.FAILED, "Descarga incompleta")
                            notifyState(id, DownloadState.FAILED)
                        } else {
                            db.updateProgress(id, done)
                            db.updateState(id, DownloadState.COMPLETED)
                            notifyState(id, DownloadState.COMPLETED)
                        }
                    }
                    // Si no está en runFlags fue pausa/cancelación: el estado ya quedó puesto.
                    runFlags.remove(id)
                    downloadedCounters.remove(id)
                }
            } while (retry)
        } catch (e: Exception) {
            try {
                db.updateState(id, DownloadState.FAILED, e.message ?: e.javaClass.simpleName)
            } catch (_: Exception) {
            }
            notifyState(id, DownloadState.FAILED)
            runFlags.remove(id)
            downloadedCounters.remove(id)
        } finally {
            db.close()
        }
    }

    private fun buildSlices(row: DownloadDb.DownloadRow, n: Int): List<ThreadSlice> {
        val total = row.item.totalBytes
        if (!row.acceptRanges || total <= 0) {
            return listOf(ThreadSlice(row.item.id, 0, 0L, -1L, 0L))
        }
        val part = total / n
        return (0 until n).map { i ->
            val s = i * part
            val e = if (i == n - 1) total - 1 else (i + 1) * part - 1
            ThreadSlice(row.item.id, i, s, e, 0L)
        }
    }

    // ---------------- worker por hilo ----------------

    private fun downloadSlice(
        ctx: Context,
        db: DownloadDb,
        row: DownloadDb.DownloadRow,
        slice: ThreadSlice,
        flag: AtomicBoolean,
        counter: AtomicLong
    ) {
        val id = row.item.id
        val file = File(ctx.getExternalFilesDir(null), row.item.fileName)
        var conn: HttpURLConnection? = null
        var raf: RandomAccessFile? = null
        try {
            // Pre-asigna el tamaño total para que los hilos escriban por offset.
            if (slice.threadId == 0 && row.item.totalBytes > 0) {
                try {
                    RandomAccessFile(file, "rw").use { it.setLength(row.item.totalBytes) }
                } catch (_: Exception) {
                }
            }
            var pos = slice.start + slice.finished
            val end = slice.end
            if (end >= 0 && pos > end) return // slice ya completo

            conn = (URL(row.item.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", row.userAgent ?: "PkgBrowser/1.0")
                if (!row.cookies.isNullOrEmpty()) setRequestProperty("Cookie", row.cookies)
                if (!row.referer.isNullOrEmpty()) setRequestProperty("Referer", row.referer)
                if (end >= 0) {
                    setRequestProperty("Range", "bytes=$pos-$end")
                } else if (pos > 0) {
                    setRequestProperty("Range", "bytes=$pos-")
                }
            }
            conn.connect()
            val code = conn.responseCode
            val rangeRequested = end >= 0 || pos > 0
            if (rangeRequested && code == HttpURLConnection.HTTP_OK) {
                throw RangeNotSupportedException()
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code")
            }

            raf = RandomAccessFile(file, "rw")
            val input = conn.inputStream.buffered(BUFFER_SIZE)
            val buf = ByteArray(BUFFER_SIZE)
            var sliceFinished = slice.finished
            var lastPersist = SystemClock.elapsedRealtime()

            while (flag.get()) {
                val n = input.read(buf)
                if (n < 0) break
                var toWrite = n
                if (end >= 0 && pos + n - 1 > end) {
                    toWrite = (end - pos + 1).toInt()
                }
                raf.seek(pos)
                raf.write(buf, 0, toWrite)
                pos += toWrite
                sliceFinished += toWrite
                counter.addAndGet(toWrite.toLong())
                if (toWrite < n) break // fin del slice
                val now = SystemClock.elapsedRealtime()
                if (now - lastPersist > PERSIST_MS) {
                    db.updateSliceFinished(id, slice.threadId, sliceFinished)
                    db.updateProgress(id, counter.get())
                    notifyProgress(id, counter.get(), row.item.totalBytes)
                    lastPersist = now
                }
            }
            db.updateSliceFinished(id, slice.threadId, sliceFinished)
            db.updateProgress(id, counter.get())
            notifyProgress(id, counter.get(), row.item.totalBytes)
        } finally {
            try {
                raf?.close()
            } catch (_: Exception) {
            }
            conn?.disconnect()
        }
    }

    // ---------------- notificaciones ----------------

    private fun notifyProgress(id: Long, downloaded: Long, total: Long) {
        for (l in listeners) {
            try {
                l.onProgress(id, downloaded, total)
            } catch (_: Exception) {
            }
        }
    }

    private fun notifyState(id: Long, state: DownloadState) {
        for (l in listeners) {
            try {
                l.onStateChanged(id, state)
            } catch (_: Exception) {
            }
        }
    }
}
