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
 * SAF stays the primary mechanism (local-photos skill); this scanner backs
 * the explicit "Device gallery" source in settings — the provider cannot be
 * health-probed from an app (MANAGE_DOCUMENTS is signature-level), so the
 * fallback is user-chosen rather than autodetected.
 *
 * Rows are read via content:// media URIs — no raw file paths (scoped
 * storage, android-compat skill). MediaStore's indexed size/mtime drive the
 * same reuse rule as the SAF scanner, and new files get the same one-stream
 * inspection through [PhotoInspector].
 */
class MediaStoreScanner(private val resolver: ContentResolver) {

    private val inspector = PhotoInspector(resolver)

    /**
     * A scan whose completeness is explicit: [complete] is false when the
     * query or the cursor walk died mid-way. Callers must not overwrite a
     * healthy index with an incomplete result — a transient vendor-provider
     * failure would otherwise erase every indexed photo.
     */
    data class ScanResult(val items: List<PhotoItem>, val complete: Boolean)

    /**
     * [bucketId] narrows the scan to one folder (a MediaStore "bucket"); null
     * scans the whole gallery. [known] is the previous index keyed by uri.
     *
     * @throws SecurityException when the storage permission was revoked —
     * callers must NOT treat that as an empty gallery either.
     */
    fun scan(bucketId: Long?, known: Map<String, PhotoItem>): ScanResult {
        val out = ArrayList<PhotoItem>()
        var complete = true
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
        } catch (e: SecurityException) {
            throw e // revoked permission is a failure, never "no photos"
        } catch (e: Exception) {
            complete = false // broken provider: degrade, but say so
            null
        }
        // The whole walk is guarded too: old/vendor MediaStore providers can
        // throw mid-iteration (moveToNext/getLong), and a partial index is
        // strictly better than an IO-thread crash.
        try {
            cursor?.use { c ->
                while (c.moveToNext() && out.size < LocalScan.MAX_PHOTOS) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: id.toString()
                    val size = c.getLong(2)
                    // MediaStore mtime is in SECONDS; the SAF scanner stores
                    // milliseconds. The value only feeds the reuse comparison
                    // (same scanner writes, same scanner compares), but keep
                    // the unit consistent in the shared index anyway.
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
                    // Stale rows (file deleted behind the index) inspect to
                    // zero dimensions — drop, not index, dead entries.
                    if (LocalScan.keepMediaStoreItem(item)) out.add(item)
                }
            }
        } catch (e: SecurityException) {
            throw e // same rule mid-iteration: revoked access ≠ empty gallery
        } catch (e: Exception) {
            // Keep whatever was indexed before the provider misbehaved —
            // but flag the walk as incomplete so the caller keeps its index.
            complete = false
        }
        return ScanResult(out, complete)
    }

    /** One row of [listBuckets]: a device folder as MediaStore sees it. */
    data class Bucket(val id: Long, val name: String, val count: Int)

    /** Same completeness contract as [ScanResult], for the folder list. */
    data class BucketsResult(val buckets: List<Bucket>, val complete: Boolean)

    /**
     * Folders that contain photos, most populous first — the folder list for
     * the fallback picker dialog. Grouping happens in Kotlin rather than SQL:
     * GROUP BY in a ContentResolver selection is a non-SDK trick that broke
     * in API 30 (android-compat skill: no non-SDK APIs).
     */
    fun listBuckets(): BucketsResult {
        var complete = true
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
        } catch (e: SecurityException) {
            throw e // revoked permission must surface as such, not "no folders"
        } catch (e: Exception) {
            complete = false
            null
        }
        try {
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: ""
                    val prev = counts[id]
                    counts[id] = Bucket(id, prev?.name?.ifEmpty { name } ?: name,
                        (prev?.count ?: 0) + 1)
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            // A partial folder list beats crashing the IO thread — but the
            // caller must know it is partial, not a small gallery.
            complete = false
        }
        return BucketsResult(counts.values.sortedByDescending { it.count }, complete)
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
