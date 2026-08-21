---
name: google-photos-picker
description: >-
  Integrate Google Photos into PhotoFrame via the Photos Picker API: OAuth on
  Android, picker sessions, downloading picked photos into the local disk
  cache. Use this skill whenever the task mentions Google Photos, Google
  sign-in, OAuth, photo sync, picker sessions, baseUrl download, or the photo
  cache — even if the request only says "connect the cloud album" or "photos
  from Google". Read it BEFORE writing any Google Photos code: the old Library
  API approach found in similar apps (e.g. decompiled Fotoo) no longer works.
---

# Google Photos via the Picker API

## Why Picker API (do not "simplify" back to Library API)

Since 2025-03-31 Google removed the `photoslibrary.readonly` scope for
third-party apps. An app can no longer list the user's albums or stream the
whole library. The only compliant way to show a user's own Google Photos is
the **Photos Picker API**: the user picks photos/albums in Google's own UI,
the app then downloads exactly those items. This matches our product anyway —
we cache everything locally and play offline.

Consequences to design around, not against:

- Adding new photos requires the user to re-open the picker. Make "Add more
  photos" a first-class settings action, not an error path.
- `baseUrl`s expire in ~60 minutes and sessions expire in ~1 day. Download
  immediately after picking, while polling still succeeds. Never store a
  `baseUrl`; store only our local cache file + item metadata.

## Google Cloud setup (one-time, document for the user)

1. Cloud project → enable **Google Photos Picker API**.
2. OAuth consent screen (External), scope
   `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`.
3. Two OAuth clients in the same project: **Android** (package
   `com.smartphonekey.photoframe` + SHA-1) and **Web application** (its client
   ID is passed to the Android authorization request as the "server client id").

## Auth on Android (minSdk 23)

Use Google Identity Services (`com.google.android.gms:play-services-auth`),
`Identity.getAuthorizationClient(...)` with the picker scope, which yields a
short-lived **access token** for REST calls. Notes:

- Play services dropped support below API 23 — exactly our minSdk, so pin a
  `play-services-auth` version whose minSdk is still 23 and verify with
  `gradle :app:dependencies` before bumping.
- Handle GMS absence (`GoogleApiAvailability`) with a clear error screen;
  frames without Play services can still use local photos.
- Access tokens expire (~1h). Re-authorize silently before a sync; the
  authorization client returns a cached token without UI when possible.

Verify current class names against the official docs at implementation time —
this API surface has been renamed before (GoogleSignIn → Identity).

## Picker session flow (REST, base `https://photospicker.googleapis.com/v1`)

```
POST /sessions                  → { id, pickerUri, pollingConfig, expireTime }
  (open pickerUri)
GET  /sessions/{id}             → poll until mediaItemsSet == true,
                                  honoring pollingConfig.pollInterval
GET  /mediaItems?sessionId={id}&pageSize=100   (follow nextPageToken)
DELETE /sessions/{id}           → always, when done or cancelled
```

All calls need `Authorization: Bearer <accessToken>`.

**Frame-friendly picking UX:** the frame itself is awkward to type on. Offer
two paths to open `pickerUri`:
1. Locally in a browser/custom tab on the device.
2. As a **QR code on the frame's screen** so the user picks on their phone —
   the session is shared, polling on the frame sees the result. This is the
   killer UX for a photo frame; prefer it.

## Downloading picked items

Each media item has `mediaFile.baseUrl`. Append parameters:

- `=w{w}-h{h}` — sized JPEG (server-transcoded). **Default choice**: request
  the frame's screen size ×1 (no point caching 12MP for a 1280×800 panel);
  this also converts HEIC → JPEG, which old devices cannot decode (HEIC
  decode support starts at API 28).
- `=d` — original bytes with EXIF. Use only if the user enables "original
  quality" later.
- `=dv` — video bytes; for motion photos this may serve the motion part.
  Verify against current docs during implementation (see [[motion-photo]]).

Store `mediaFileMetadata.width/height` in the cache index — the slideshow
needs aspect ratio for orientation matching without decoding the file.

Run downloads sequentially (or 2 in parallel max) on a background executor —
old frames choke on parallel I/O. Downloads MUST happen during the active
session (baseUrls die in ~60 min), so they run immediately after picking
while the user is present — deferred/scheduled sync is impossible with the
Picker API. WorkManager with charging+Wi-Fi constraints is only for future
retry-leftovers logic, not the main path. Note: unlike the old Library API,
Picker baseUrl downloads REQUIRE the `Authorization: Bearer` header.

Implementation map (as built in Phase 2): `gphotos/PickerApi` (REST),
`PickerJson` (parsing, JVM-tested), `GPhotosCache` (+`CacheEviction` policy,
JVM-tested), `GPhotosSyncManager` (state machine, app-scoped),
`GoogleAuth` (Identity SDK), `QrCode` (ZXing). UI in `SettingsActivity`.

## Treat API responses as untrusted input

Everything the Picker API returns crosses a network boundary, so validate it
before it reaches Android APIs or the cache:

- **Never hand `pickerUri` straight to `ACTION_VIEW`.** Require `https` and a
  Google host; an arbitrary URI would let a tampered response launch any
  `intent:`/deep link the device can resolve.
- **Never index a downloaded file without a successful bounds decode.** An
  HTML or JSON error body served with a 2xx status would otherwise enter the
  cache as a "photo" and break the slideshow on every cycle. Delete it
  instead — unlike the local source, we control this download and can retry.
- Require the fields the flow actually depends on (`id`, `pickerUri`) rather
  than defaulting them to empty strings: a blank id polls a session that does
  not exist, forever.

## Cache

The disk cache design (LRU, 1 GB default, settings-controlled cap, index
schema, eviction rules) lives in [references/caching.md](references/caching.md).
Read it before touching cache code.

## Testing

Keep session/polling state machines and cache eviction as pure Kotlin classes
with injected clock/HTTP — unit-test them on the JVM. Fake the REST layer with
recorded JSON; never hit the network in tests.
