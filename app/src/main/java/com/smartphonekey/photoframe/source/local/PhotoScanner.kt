package com.smartphonekey.photoframe.source.local

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import com.smartphonekey.photoframe.core.PhotoItem
import com.smartphonekey.photoframe.motion.MotionPhotoDetector
import java.io.ByteArrayInputStream

/**
 * Scans a SAF folder tree for images.
 *
 * Uses DocumentsContract child queries directly (one query per directory),
 * NOT DocumentFile.listFiles() which issues one IPC round-trip per file and
 * is unusably slow on big folders on old devices (local-photos skill).
 *
 * Per new/changed file the scanner opens ONE stream and reads the head
 * (256 KB) into memory; dimensions (bounds decode), EXIF rotation and
 * motion-photo markers are all parsed from that buffer — old frames pay for
 * every stream open, so batching the reads matters. Results are reused from
 * [known] on rescans when size+mtime are unchanged.
 */
class PhotoScanner(private val resolver: ContentResolver) {

    fun scan(treeUri: Uri, known: Map<String, PhotoItem>): List<PhotoItem> {
        val out = ArrayList<PhotoItem>()
        val dirs = ArrayDeque<Pair<String, Int>>() // documentId to depth
        dirs.addLast(DocumentsContract.getTreeDocumentId(treeUri) to 0)

        while (dirs.isNotEmpty() && out.size < MAX_PHOTOS) {
            val (dirId, depth) = dirs.removeFirst()
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            val cursor = try {
                resolver.query(childrenUri, PROJECTION, null, null, null)
            } catch (e: Exception) {
                null // provider hiccup on one dir must not kill the scan
            }
            cursor?.use { c ->
                while (c.moveToNext() && out.size < MAX_PHOTOS) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: docId
                    val mime = c.getString(2) ?: ""
                    val size = c.getLong(3)
                    val mtime = c.getLong(4)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH) dirs.addLast(docId to depth + 1)
                    } else if (mime in imageMimes()) {
                        val uri = DocumentsContract
                            .buildDocumentUriUsingTree(treeUri, docId).toString()
                        val cached = known[uri]
                        val item =
                            if (cached != null && cached.sizeBytes == size &&
                                cached.lastModified == mtime && cached.width > 0 &&
                                cached.videoOffsetBytes != PhotoItem.MOTION_NOT_SCANNED
                            ) {
                                cached
                            } else {
                                inspect(uri, name, size, mtime)
                            }
                        out.add(item)
                    }
                }
            }
        }
        return out
    }

    // One buffer for the whole scan (the scanner runs on a single IO
    // thread): a fresh 256 KB allocation per file would be real GC churn on
    // a 1 GB frame with a 10k-photo card.
    private val headBuffer = ByteArray(MotionPhotoDetector.HEAD_BYTES)

    /** One stream open: head buffer → bounds + EXIF + motion markers. */
    private fun inspect(uri: String, name: String, size: Long, mtime: Long): PhotoItem {
        var width = 0
        var height = 0
        var motion: MotionPhotoDetector.Result? = null
        try {
            val headLength = readHead(uri)
            if (headLength > 0) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(headBuffer, 0, headLength, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    width = options.outWidth
                    height = options.outHeight
                }
                if (width > 0) {
                    val rotation = try {
                        ExifInterface(ByteArrayInputStream(headBuffer, 0, headLength))
                            .rotationDegrees
                    } catch (e: Exception) {
                        0 // truncated/absent EXIF in the head → no rotation
                    }
                    if (rotation == 90 || rotation == 270) {
                        val t = width
                        width = height
                        height = t
                    }
                }
                motion = MotionPhotoDetector.detect(headBuffer, headLength, size)
            }
            if (width <= 0) {
                // Rare: dimensions live past the head (huge embedded
                // thumbnail). Fall back to a full-stream bounds decode.
                resolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        width = options.outWidth
                        height = options.outHeight
                    }
                }
            }
        } catch (e: Exception) {
            // Unreadable file: dims stay 0 → classified SQUARE, shown anyway.
        }
        return PhotoItem(
            uri = uri,
            displayName = name,
            sizeBytes = size,
            lastModified = mtime,
            width = width,
            height = height,
            videoOffsetBytes = motion?.videoOffsetBytes ?: -1L,
            videoLengthBytes = motion?.videoLengthBytes ?: 0L,
        )
    }

    /** Fills [headBuffer]; returns the byte count read (0 when unreadable). */
    private fun readHead(uri: String): Int =
        resolver.openInputStream(Uri.parse(uri))?.use { stream ->
            var filled = 0
            while (filled < headBuffer.size) {
                val read = stream.read(headBuffer, filled, headBuffer.size - filled)
                if (read < 0) break
                filled += read
            }
            filled
        } ?: 0

    private fun imageMimes(): Set<String> =
        if (Build.VERSION.SDK_INT >= 28) MIMES_WITH_HEIF else MIMES_BASE

    companion object {
        private const val MAX_DEPTH = 5
        private const val MAX_PHOTOS = 20_000

        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        private val MIMES_BASE = setOf("image/jpeg", "image/png", "image/webp")

        // HEIC/HEIF decoding exists only from API 28 (android-compat skill).
        private val MIMES_WITH_HEIF = MIMES_BASE + setOf("image/heif", "image/heic")
    }
}
