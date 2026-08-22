package com.smartphonekey.photoframe.source.local

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.smartphonekey.photoframe.core.PhotoItem
import com.smartphonekey.photoframe.motion.MotionPhotoDetector
import java.io.ByteArrayInputStream

/**
 * Turns one readable image URI into a [PhotoItem]: post-rotation dimensions,
 * EXIF rotation and motion-photo markers.
 *
 * Shared by both local scanners — [PhotoScanner] (SAF tree) and
 * [MediaStoreScanner] (MediaStore bucket). Once a scanner has a content URI
 * the work is identical, and old frames pay dearly for every stream open, so
 * the common case is one open per file, served from a single reusable head
 * buffer. The rare file whose SOF/EXIF live beyond the 256 KB head (a huge
 * embedded thumbnail) pays up to two extra full-stream opens — acceptable
 * because it is a per-file exception, not the scan's steady state.
 *
 * One buffer for the whole scan (scans run on a single IO thread): a fresh
 * 256 KB allocation per file would be real GC churn on a 1 GB frame with a
 * 10k-photo card. [inspect] is synchronized so that sharing one instance
 * between two scans is merely slow rather than silently wrong.
 */
class PhotoInspector(private val resolver: ContentResolver) {

    private val headBuffer = ByteArray(MotionPhotoDetector.HEAD_BYTES)

    /** One stream open: head buffer → bounds + EXIF + motion markers. */
    @Synchronized
    fun inspect(uri: String, name: String, size: Long, mtime: Long): PhotoItem {
        var width = 0
        var height = 0
        var rotation = 0
        var motion: MotionPhotoDetector.Result? = null
        try {
            val headLength = readHead(uri)
            if (headLength > 0) {
                // EXIF (APP1) sits right after the JPEG SOI marker, so the
                // head buffer has it even when the DIMENSIONS live further
                // in — read rotation independently of where bounds come from.
                rotation = try {
                    ExifInterface(ByteArrayInputStream(headBuffer, 0, headLength))
                        .rotationDegrees
                } catch (e: Exception) {
                    0 // truncated/absent EXIF in the head → no rotation
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(headBuffer, 0, headLength, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    width = options.outWidth
                    height = options.outHeight
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
                // If the SOF was not in the head, the EXIF APP1 may not have
                // been either — this file's metadata layout is unusual, so
                // re-read orientation from the full stream like the bounds.
                if (width > 0 && rotation == 0) {
                    rotation = try {
                        resolver.openInputStream(Uri.parse(uri))?.use { stream ->
                            ExifInterface(stream).rotationDegrees
                        } ?: 0
                    } catch (e: Exception) {
                        0
                    }
                }
            }
            if (width > 0 && (rotation == 90 || rotation == 270)) {
                val t = width
                width = height
                height = t
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
                // <= 0: EOF, or a misbehaving provider returning 0 for a
                // non-empty request — either way looping again cannot help.
                if (read <= 0) break
                filled += read
            }
            filled
        } ?: 0
}
