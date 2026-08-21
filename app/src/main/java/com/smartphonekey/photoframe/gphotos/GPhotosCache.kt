package com.smartphonekey.photoframe.gphotos

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import android.net.Uri
import com.smartphonekey.photoframe.core.PhotoItem
import java.io.File
import java.security.MessageDigest

/**
 * Disk cache for photos downloaded from Google Photos (caching.md design):
 * app-private dir (external when mounted — often an SD card on frames),
 * SQLite index as the single source of truth, tmp+rename crash safety,
 * LRU eviction against the user-set cap.
 */
class GPhotosCache(context: Context) {

    private val appContext = context.applicationContext
    private val db = Db(appContext)
    private val pendingShown = HashMap<String, Long>() // file_name → timestamp

    val mediaDir: File by lazy {
        val base = appContext.getExternalFilesDir("gphotos")
            ?: File(appContext.filesDir, "gphotos")
        File(base, "media").apply { mkdirs() }
    }

    /** Startup sweep: drop *.tmp and heal file↔row mismatches. Call on IO thread. */
    fun sweep() {
        val files = mediaDir.listFiles()?.associateBy { it.name } ?: emptyMap()
        files.values.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        val rows = db.listAll()
        val rowNames = rows.mapTo(HashSet()) { it.fileName }
        files.values
            .filter { !it.name.endsWith(".tmp") && it.name !in rowNames }
            .forEach { it.delete() }
        rows.filter { it.fileName !in files }
            .forEach { db.delete(it.fileName) }
    }

    fun fileNameFor(item: PickedItem): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(item.id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val ext = when (item.mimeType) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg" // sized baseUrl downloads are JPEG-transcoded
        }
        return digest + ext
    }

    fun contains(item: PickedItem): Boolean = db.exists(fileNameFor(item))

    /**
     * Moves a fully-downloaded tmp file into place and indexes it.
     * Dimensions are measured from the actual bytes — server-side resizing
     * bakes in EXIF rotation, so a bounds decode is the ground truth.
     */
    fun commit(item: PickedItem, tmp: File, now: Long): Boolean {
        val name = fileNameFor(item)
        val final = File(mediaDir, name)
        if (!tmp.renameTo(final)) {
            tmp.delete()
            return false
        }
        var width = 0
        var height = 0
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(final.absolutePath, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                width = options.outWidth
                height = options.outHeight
            }
        } catch (e: Exception) {
            // Unknown dims → SQUARE bucket; still displayable.
        }
        db.upsert(
            Db.Row(
                fileName = name,
                itemId = item.id,
                mime = item.mimeType,
                width = width,
                height = height,
                sizeBytes = final.length(),
                downloadedAt = now,
                lastShownAt = 0L,
            )
        )
        return true
    }

    fun loadAllAsPhotoItems(): List<PhotoItem> = db.listAll().map { row ->
        PhotoItem(
            uri = Uri.fromFile(File(mediaDir, row.fileName)).toString(),
            displayName = row.fileName,
            sizeBytes = row.sizeBytes,
            lastModified = row.downloadedAt,
            width = row.width,
            height = row.height,
        )
    }

    fun photoCount(): Int = db.count()

    /**
     * Slideshow hook — called on the MAIN thread, so it only records into a
     * map; flush() (background thread) writes the batch out (caching.md rule:
     * no DB write per slide on weak flash).
     */
    fun noteShown(uri: String, now: Long) {
        if (!uri.contains("/gphotos/")) return
        val name = uri.substringAfterLast('/')
        synchronized(pendingShown) { pendingShown[name] = now }
    }

    /** Call from a background thread (onPause path or before eviction). */
    fun flush() {
        val batch: Map<String, Long>
        synchronized(pendingShown) {
            if (pendingShown.isEmpty()) return
            batch = HashMap(pendingShown)
            pendingShown.clear()
        }
        db.updateLastShown(batch)
    }

    /** Applies the LRU policy; returns true when the cap is too small. */
    fun evictToCap(capBytes: Long): Boolean {
        flush()
        val entries = db.listAll().map {
            CacheEviction.Entry(it.fileName, it.sizeBytes, it.lastShownAt, it.downloadedAt)
        }
        for (name in CacheEviction.selectVictims(entries, capBytes)) {
            File(mediaDir, name).delete()
            db.delete(name)
        }
        return CacheEviction.capTooSmall(entries, capBytes)
    }

    fun totalBytes(): Long = db.listAll().sumOf { it.sizeBytes }

    fun clearAll() {
        synchronized(pendingShown) { pendingShown.clear() }
        mediaDir.listFiles()?.forEach { it.delete() }
        db.clear()
    }

    private class Db(context: Context) :
        SQLiteOpenHelper(context, "gphotos_cache.db", null, 1) {

        data class Row(
            val fileName: String,
            val itemId: String,
            val mime: String,
            val width: Int,
            val height: Int,
            val sizeBytes: Long,
            val downloadedAt: Long,
            val lastShownAt: Long,
        )

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE media (
                    file_name TEXT PRIMARY KEY,
                    item_id TEXT NOT NULL,
                    mime TEXT NOT NULL,
                    width INTEGER NOT NULL DEFAULT 0,
                    height INTEGER NOT NULL DEFAULT 0,
                    size_bytes INTEGER NOT NULL,
                    downloaded_at INTEGER NOT NULL,
                    last_shown_at INTEGER NOT NULL DEFAULT 0
                )"""
            )
            db.execSQL("CREATE INDEX idx_item ON media(item_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS media")
            onCreate(db)
        }

        fun upsert(row: Row) {
            val values = ContentValues().apply {
                put("file_name", row.fileName)
                put("item_id", row.itemId)
                put("mime", row.mime)
                put("width", row.width)
                put("height", row.height)
                put("size_bytes", row.sizeBytes)
                put("downloaded_at", row.downloadedAt)
                put("last_shown_at", row.lastShownAt)
            }
            writableDatabase.insertWithOnConflict(
                "media", null, values, SQLiteDatabase.CONFLICT_REPLACE
            )
        }

        fun exists(fileName: String): Boolean =
            readableDatabase.rawQuery(
                "SELECT 1 FROM media WHERE file_name = ?", arrayOf(fileName)
            ).use { it.moveToFirst() }

        fun listAll(): List<Row> {
            val out = ArrayList<Row>()
            readableDatabase.rawQuery(
                """SELECT file_name, item_id, mime, width, height,
                          size_bytes, downloaded_at, last_shown_at FROM media""",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Row(
                            fileName = c.getString(0),
                            itemId = c.getString(1),
                            mime = c.getString(2),
                            width = c.getInt(3),
                            height = c.getInt(4),
                            sizeBytes = c.getLong(5),
                            downloadedAt = c.getLong(6),
                            lastShownAt = c.getLong(7),
                        )
                    )
                }
            }
            return out
        }

        fun count(): Int =
            readableDatabase.rawQuery("SELECT COUNT(*) FROM media", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }

        fun updateLastShown(batch: Map<String, Long>) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for ((name, time) in batch) {
                    db.execSQL(
                        "UPDATE media SET last_shown_at = ? WHERE file_name = ?",
                        arrayOf(time, name)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        fun delete(fileName: String) {
            writableDatabase.delete("media", "file_name = ?", arrayOf(fileName))
        }

        fun clear() {
            writableDatabase.delete("media", null, null)
        }
    }

}
