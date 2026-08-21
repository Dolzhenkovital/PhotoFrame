package com.smartphonekey.photoframe.source.local

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.smartphonekey.photoframe.core.PhotoItem

/**
 * Scans a SAF folder tree for images.
 *
 * Uses DocumentsContract child queries directly (one query per directory),
 * NOT DocumentFile.listFiles() which issues one IPC round-trip per file and
 * is unusably slow on big folders on old devices (local-photos skill).
 *
 * Per new/changed file [PhotoInspector] opens ONE stream and reads the head
 * (256 KB) into memory; dimensions (bounds decode), EXIF rotation and
 * motion-photo markers are all parsed from that buffer — old frames pay for
 * every stream open, so batching the reads matters. Results are reused from
 * [known] on rescans when size+mtime are unchanged.
 */
class PhotoScanner(private val resolver: ContentResolver) {

    private val inspector = PhotoInspector(resolver)

    fun scan(treeUri: Uri, known: Map<String, PhotoItem>): List<PhotoItem> {
        val out = ArrayList<PhotoItem>()
        val dirs = ArrayDeque<Pair<String, Int>>() // documentId to depth
        dirs.addLast(DocumentsContract.getTreeDocumentId(treeUri) to 0)
        val mimes = LocalScan.imageMimes(Build.VERSION.SDK_INT)

        while (dirs.isNotEmpty() && out.size < LocalScan.MAX_PHOTOS) {
            val (dirId, depth) = dirs.removeFirst()
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            val cursor = try {
                resolver.query(childrenUri, PROJECTION, null, null, null)
            } catch (e: Exception) {
                null // provider hiccup on one dir must not kill the scan
            }
            cursor?.use { c ->
                while (c.moveToNext() && out.size < LocalScan.MAX_PHOTOS) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: docId
                    val mime = c.getString(2) ?: ""
                    val size = c.getLong(3)
                    val mtime = c.getLong(4)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < LocalScan.MAX_DEPTH) dirs.addLast(docId to depth + 1)
                    } else if (mime in mimes) {
                        val uri = DocumentsContract
                            .buildDocumentUriUsingTree(treeUri, docId).toString()
                        val cached = known[uri]
                        val item = if (LocalScan.isReusable(cached, size, mtime)) {
                            cached!!
                        } else {
                            inspector.inspect(uri, name, size, mtime)
                        }
                        out.add(item)
                    }
                }
            }
        }
        return out
    }

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
