---
name: local-photos
description: >-
  Play photos from the device's local storage (internal, SD card, USB): folder
  picking, MediaStore vs Storage Access Framework, the runtime-permission
  matrix from API 23 to 35, and EXIF handling. Use this skill whenever the
  task touches local photos, folders, galleries, SD cards, USB drives, storage
  permissions, READ_MEDIA_IMAGES, SAF, MediaStore, or file scanning — even if
  the request just says "show photos from the device".
---

# Local photos source

## Primary mechanism: SAF folder pick (works identically on API 23–35)

Use `Intent.ACTION_OPEN_DOCUMENT_TREE` → user picks a folder once → call
`contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`.

Why this is the default and not MediaStore:

- Zero runtime permissions on every API level we support — the whole
  permission matrix below becomes irrelevant for this path.
- Works for SD cards and USB OTG drives, which is how people actually load
  old photo frames.
- Survives reboots (persisted permission), revocable by re-picking.

Enumerate children with `DocumentsContract.buildChildDocumentsUriUsingTree` +
`ContentResolver.query` directly. Do **not** use `DocumentFile.listFiles()`
for scanning — it issues one IPC query per file and is brutally slow on large
folders and old devices. Query columns: document id, display name, MIME type,
size, last modified. Recurse into subdirectories (MIME
`vnd.android.document/directory`) with a depth limit (~5) and cache the
listing (see below).

Accept MIME `image/jpeg`, `image/png`, `image/webp`; add `image/heif` only on
API 28+ (no HEIC decoder before that — see [[low-end-performance]]).

## Scan index

Scanning a 10k-photo SD card takes seconds-to-minutes on old hardware. Scan
once on a background executor, store results in a small SQLite table
(`uri, name, size, mtime, width, height, orientation`), and rescan only when
the user asks or the folder's mtime changes. Width/height/orientation come
from a bounds-only decode + EXIF (below) at scan time, so the slideshow can do
orientation matching without touching files.

## MediaStore alternative (implemented: the "Device gallery" source)

Shipped as a fallback because real frame firmwares exist whose SAF provider
is broken: the reference Allwinner frame (BGS-102K-T, Android 6.0.1) reports
its primary volume at a path that does not exist (`/storage/emulated/sdcard`),
so DocumentsUI cannot open "Internal storage" at all while MediaStore on the
same firmware works fine. The provider cannot be probed from an app
(`MANAGE_DOCUMENTS` is signature-level), so the fallback is a user-visible
second source (`MediaStoreScanner` + "Device gallery" in settings), not
automatic detection. SAF remains the primary mechanism.

Permission matrix — every branch must exist since this path is implemented:

| API | Required permission |
|-----|--------------------|
| 23–32 | `READ_EXTERNAL_STORAGE` (runtime request) |
| 33+ | `READ_MEDIA_IMAGES` (+ `READ_MEDIA_VIDEO` for motion parts) |
| 34+ | Handle partial access: `READ_MEDIA_VISUAL_USER_SELECTED` — the user may grant only some photos; treat that as the selection, don't nag |

Query `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`, group by
`BUCKET_DISPLAY_NAME` for a folder list. On API 29 add
`android:requestLegacyExternalStorage` **only if** raw `File` paths are ever
used — they should not be; stick to content URIs everywhere.

## EXIF and orientation

Use `androidx.exifinterface.media.ExifInterface` (works from an
`InputStream`, so it is SAF-friendly). Read `TAG_ORIENTATION` and translate to
degrees; a 4000×3000 JPEG with orientation 6 is a **portrait** photo — the
aspect classification for [[slideshow-engine]] must use post-rotation
dimensions. Also read `TAG_DATETIME_ORIGINAL` for future sort modes and check
`isMotionPhoto`-related XMP only via [[motion-photo]] detection, not here.

## Testing

The scanner and classification logic take a `List<ScannedItem>`-producing
interface; unit-test with fakes. Instrumented tests (later) cover the real
SAF query on API 23 and 35 emulators — see the ci-cd skill for how those jobs
are wired.
