package com.smartphonekey.photoframe.source.local

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import com.smartphonekey.photoframe.core.PhotoItem

/**
 * Fallback local source: enumerate device photos through MediaStore instead
 * of a SAF folder pick.
 *
 * Exists because some frame firmwares ship a broken ExternalStorageProvider
 * — the reference Allwinner frame reports its primary volume at a path that
 * does not exist (`/storage/emulated/sdcard`), so the SAF picker cannot even
 * open "Internal storage" while MediaStore on the same firmware works fine.
 * SAF stays the primary mechanism (local-photos skill); this scanner is only
 * offered when the picker is unusable (see SafHealth).
 *
 * Rows are read via content:// media URIs — no raw file paths (scoped
 * storage, android-compat skill). MediaStore's indexed size/mtime drive the
 * same reuse rule as the SAF scanner, and new files get the same one-stream
 * inspection through [PhotoInspector].
 */
class MediaStoreScanner(private val resolver: ContentResolver) {

    private val inspector = PhotoInspector(resolver)

    /**
     * [bucketId] narrows the scan to one folder (a MediaStore "bucket"); null
     * scans the whole gallery. [known] is the previous index keyed by uri.
     */
    fun scan(bucketId: Long?, known: Map<String, PhotoItem>): List<PhotoItem> {
        val out = ArrayList<PhotoItem>()
        val mimes = LocalScan.imageMimes(Build.VERSION.SDK_INT)
        // MIME filter in SQL keeps the cursor small on gallery-wide scans.
        val mimePlaceholders = mimes.joinToString(",") { "?" }
        var selection = "${MediaStore.Images.Media.MIME_TYPE} IN ($mimePlaceholders)"
        var args = mimes.toTypedArray()
        if (bucketId != null) {
            selection += " AND ${MediaStore.Images.Media.BUCKET_ID} = ?"
            args += bucketId.toString()
        }
        val cursor = try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PROJECTION, selection, args, null
            )
        } catch (e: Exception) {
            null // a broken provider must degrade to "no photos", not a crash
        }
        cursor?.use { c ->
            while (c.moveToNext() && out.size < LocalScan.MAX_PHOTOS) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: id.toString()
                val size = c.getLong(2)
                // MediaStore mtime is in SECONDS; the SAF scanner stores
                // milliseconds. The value only feeds the reuse comparison
                // (same scanner writes, same scanner compares), but keep the
                // unit consistent in the shared index anyway.
                val mtime = c.getLong(3) * 1000L
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                ).toString()
                val cached = known[uri]
                val item = if (LocalScan.isReusable(cached, size, mtime)) {
                    cached!!
                } else {
                    inspector.inspect(uri, name, size, mtime)
                }
                out.add(item)
            }
        }
        return out
    }

    /** One row of [listBuckets]: a device folder as MediaStore sees it. */
    data class Bucket(val id: Long, val name: String, val count: Int)

    /**
     * Folders that contain photos, most populous first — the folder list for
     * the fallback picker dialog. Grouping happens in Kotlin rather than SQL:
     * GROUP BY in a ContentResolver selection is a non-SDK trick that broke
     * in API 30 (android-compat skill: no non-SDK APIs).
     */
    fun listBuckets(): List<Bucket> {
        val counts = HashMap<Long, Bucket>()
        // Same MIME filter as scan(): counting rows the scanner would later
        // skip both wastes the cursor walk and advertises buckets that
        // would scan to zero photos.
        val mimes = LocalScan.imageMimes(Build.VERSION.SDK_INT)
        val cursor = try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                BUCKET_PROJECTION,
                "${MediaStore.Images.Media.MIME_TYPE} IN (${mimes.joinToString(",") { "?" }})",
                mimes.toTypedArray(),
                null
            )
        } catch (e: Exception) {
            null
        }
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: ""
                val prev = counts[id]
                counts[id] = Bucket(id, prev?.name?.ifEmpty { name } ?: name,
                    (prev?.count ?: 0) + 1)
            }
        }
        return counts.values.sortedByDescending { it.count }
    }

    companion object {
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
        )

        private val BUCKET_PROJECTION = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
    }
}
