package com.smartphonekey.photoframe.source.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.smartphonekey.photoframe.core.PhotoItem

/**
 * Persistent index of the scanned photo folder. Plain SQLiteOpenHelper by
 * design (low-end-performance skill: no Room). The index is fully
 * rebuildable from a rescan, so schema upgrades just drop and recreate.
 */
class ScanIndexDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "photo_index.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE photos (
                uri TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                size INTEGER NOT NULL,
                mtime INTEGER NOT NULL,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                video_offset INTEGER NOT NULL DEFAULT -1,
                video_length INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Additive change: keep the existing index (a wipe would blank
            // the frame until a manual rescan). Migrated rows get the
            // NOT_SCANNED sentinel (-2), which forces the scanner to
            // re-inspect them once — the usual size/mtime reuse would
            // otherwise keep "no video" forever for pre-migration photos.
            db.execSQL("ALTER TABLE photos ADD COLUMN video_offset INTEGER NOT NULL DEFAULT -2")
            db.execSQL("ALTER TABLE photos ADD COLUMN video_length INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun loadAll(): List<PhotoItem> {
        val out = ArrayList<PhotoItem>()
        readableDatabase.rawQuery(
            "SELECT uri, name, size, mtime, width, height, video_offset, video_length FROM photos",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PhotoItem(
                        uri = c.getString(0),
                        displayName = c.getString(1),
                        sizeBytes = c.getLong(2),
                        lastModified = c.getLong(3),
                        width = c.getInt(4),
                        height = c.getInt(5),
                        videoOffsetBytes = c.getLong(6),
                        videoLengthBytes = c.getLong(7),
                    )
                )
            }
        }
        return out
    }

    /** Replaces the whole index atomically — a scan is the source of truth. */
    fun replaceAll(items: List<PhotoItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("photos", null, null)
            for (item in items) {
                val values = ContentValues().apply {
                    put("uri", item.uri)
                    put("name", item.displayName)
                    put("size", item.sizeBytes)
                    put("mtime", item.lastModified)
                    put("width", item.width)
                    put("height", item.height)
                    put("video_offset", item.videoOffsetBytes)
                    put("video_length", item.videoLengthBytes)
                }
                db.insertWithOnConflict(
                    "photos", null, values, SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
