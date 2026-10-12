package com.panda.pkgbrowser

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite: tabla `downloads` (una fila por descarga, con la sesión heredada
 * del navegador) y tabla `thread_info` (progreso por hilo para reanudar).
 */
class DownloadDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    /** Fila completa de `downloads`, con los datos de sesión para el motor. */
    data class DownloadRow(
        val item: DownloadItem,
        val cookies: String?,
        val referer: String?,
        val userAgent: String?,
        val acceptRanges: Boolean
    )

    companion object {
        private const val DB_NAME = "pkgbrowser.db"
        private const val DB_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE downloads(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                file_name TEXT NOT NULL,
                total_bytes INTEGER NOT NULL DEFAULT -1,
                downloaded_bytes INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'QUEUED',
                error TEXT,
                cookies TEXT,
                referer TEXT,
                user_agent TEXT,
                accept_ranges INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE thread_info(
                download_id INTEGER NOT NULL,
                thread_id INTEGER NOT NULL,
                start_pos INTEGER NOT NULL,
                end_pos INTEGER NOT NULL,
                finished_bytes INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(download_id, thread_id)
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1: sin migraciones.
    }

    fun insertDownload(
        url: String,
        fileName: String,
        totalBytes: Long,
        cookies: String?,
        referer: String?,
        userAgent: String,
        acceptRanges: Boolean
    ): Long {
        val cv = ContentValues().apply {
            put("url", url)
            put("file_name", fileName)
            put("total_bytes", totalBytes)
            put("downloaded_bytes", 0L)
            put("state", DownloadState.QUEUED.name)
            put("cookies", cookies)
            put("referer", referer)
            put("user_agent", userAgent)
            put("accept_ranges", if (acceptRanges) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("downloads", null, cv)
    }

    fun getItem(id: Long): DownloadItem? = getRow(id)?.item

    fun getRow(id: Long): DownloadRow? {
        readableDatabase.query(
            "downloads", null, "id=?", arrayOf(id.toString()), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return rowFromCursor(c)
        }
    }

    fun listItems(): List<DownloadItem> {
        val out = mutableListOf<DownloadItem>()
        readableDatabase.query(
            "downloads", null, null, null, null, null, "created_at DESC"
        ).use { c ->
            while (c.moveToNext()) out.add(itemFromCursor(c))
        }
        return out
    }

    fun updateState(id: Long, state: DownloadState, error: String? = null) {
        val cv = ContentValues().apply {
            put("state", state.name)
            if (error != null) {
                put("error", error)
            } else if (state != DownloadState.FAILED) {
                putNull("error")
            }
        }
        writableDatabase.update("downloads", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateProgress(id: Long, downloadedBytes: Long) {
        val cv = ContentValues().apply { put("downloaded_bytes", downloadedBytes) }
        writableDatabase.update("downloads", cv, "id=?", arrayOf(id.toString()))
    }

    fun setSlices(id: Long, slices: List<ThreadSlice>) {
        val db = writableDatabase
        db.delete("thread_info", "download_id=?", arrayOf(id.toString()))
        for (s in slices) {
            val cv = ContentValues().apply {
                put("download_id", s.downloadId)
                put("thread_id", s.threadId)
                put("start_pos", s.start)
                put("end_pos", s.end)
                put("finished_bytes", s.finished)
            }
            db.insert("thread_info", null, cv)
        }
    }

    fun clearSlices(id: Long) {
        writableDatabase.delete("thread_info", "download_id=?", arrayOf(id.toString()))
    }

    fun getSlices(id: Long): List<ThreadSlice> {
        val out = mutableListOf<ThreadSlice>()
        readableDatabase.query(
            "thread_info", null, "download_id=?", arrayOf(id.toString()),
            null, null, "thread_id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ThreadSlice(
                        downloadId = id,
                        threadId = c.getInt(c.getColumnIndexOrThrow("thread_id")),
                        start = c.getLong(c.getColumnIndexOrThrow("start_pos")),
                        end = c.getLong(c.getColumnIndexOrThrow("end_pos")),
                        finished = c.getLong(c.getColumnIndexOrThrow("finished_bytes"))
                    )
                )
            }
        }
        return out
    }

    fun updateSliceFinished(id: Long, threadId: Int, finished: Long) {
        val cv = ContentValues().apply { put("finished_bytes", finished) }
        writableDatabase.update(
            "thread_info", cv,
            "download_id=? AND thread_id=?", arrayOf(id.toString(), threadId.toString())
        )
    }

    fun deleteDownload(id: Long) {
        val db = writableDatabase
        db.delete("thread_info", "download_id=?", arrayOf(id.toString()))
        db.delete("downloads", "id=?", arrayOf(id.toString()))
    }

    private fun itemFromCursor(c: Cursor): DownloadItem {
        val stateName = c.getString(c.getColumnIndexOrThrow("state"))
        val state = try {
            DownloadState.valueOf(stateName)
        } catch (_: Exception) {
            DownloadState.QUEUED
        }
        return DownloadItem(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            url = c.getString(c.getColumnIndexOrThrow("url")) ?: "",
            fileName = c.getString(c.getColumnIndexOrThrow("file_name")) ?: "descarga.pkg",
            totalBytes = c.getLong(c.getColumnIndexOrThrow("total_bytes")),
            downloadedBytes = c.getLong(c.getColumnIndexOrThrow("downloaded_bytes")),
            state = state,
            error = c.getString(c.getColumnIndexOrThrow("error")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
        )
    }

    private fun rowFromCursor(c: Cursor): DownloadRow {
        return DownloadRow(
            item = itemFromCursor(c),
            cookies = c.getString(c.getColumnIndexOrThrow("cookies")),
            referer = c.getString(c.getColumnIndexOrThrow("referer")),
            userAgent = c.getString(c.getColumnIndexOrThrow("user_agent")),
            acceptRanges = c.getInt(c.getColumnIndexOrThrow("accept_ranges")) == 1
        )
    }
}
