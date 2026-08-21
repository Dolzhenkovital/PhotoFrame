---
name: motion-photo
description: >-
  Detect and play Motion Photos (live photos with an embedded video: Google
  MicroVideo/MotionPhoto formats and Samsung SEF) in the PhotoFrame slideshow,
  with a settings toggle. Use this skill whenever the task mentions motion
  photos, live photos, MicroVideo, embedded video in JPEG, XMP metadata, or
  "photos that move" — including the settings switch that enables/disables
  the feature.
---

# Motion photo support

> **Implementation status (Phase 3):** XMP-based detection
> (`motion/MotionPhotoDetector`, head-only read shared with the scanner's
> bounds/EXIF buffer) + zero-copy playback (`motion/MotionPlayer`,
> TextureView above the stills). Legacy Samsung SEF-only files (S7–S10 era,
> no XMP marker) are NOT detected — that needs a per-file tail scan, too
> expensive at index time on old frames; it stays on the backlog. Modern
> Samsungs (~2021+) write MotionPhoto v1 XMP and work.

## What a motion photo physically is

One file: a normal JPEG (or HEIC) with an MP4 appended at the end. Metadata
in the JPEG's XMP (APP1 segment) says where the video starts:

- **Google, legacy "MicroVideo"** (Pixel ≤3):
  `GCamera:MicroVideo="1"`, `GCamera:MicroVideoOffset=<bytes from EOF>`.
  Video starts at `fileLength - offset`.
- **Google, "MotionPhoto v1"** (modern):
  `GCamera:MotionPhoto="1"` plus a `Container:Directory` XMP structure whose
  `Item` with `Item:Mime="video/mp4"` carries `Item:Length` (bytes from EOF,
  same math) and `Item:Padding`.
- **Samsung**: proprietary SEF trailer; the marker string
  `MotionPhoto_Data` precedes the MP4. Find it by scanning the last ~10 MB
  for the marker; video runs from just after the marker to EOF.

## Detection — cheap, hand-rolled, no libraries

Do not pull a metadata library for this. Detection = read the first ~128 KB,
find the XMP APP1 segment, substring-search for `MotionPhoto` /
`MicroVideo`; for Samsung, scan the tail for `MotionPhoto_Data`. Run it once
at index time (local scan or cache download) and store `has_motion` +
`video_offset`/`video_length` in the index DB — never probe files during the
slideshow.

Write this as a pure Kotlin `MotionPhotoDetector` over a `RandomAccessSource`
interface and unit-test it against a few small fixture files committed under
`app/src/test/resources/` (a real Pixel motion photo trimmed to a few hundred
KB keeps the repo light).

## Playback (zero-copy)

`MediaPlayer.setDataSource(FileDescriptor, offset, length)` plays the
embedded MP4 straight from the JPEG — no extraction, no temp files. Render
into a `TextureView` stacked above the ImageViews (visible only during
playback), muted (`setVolume(0f, 0f)`).

Sequence when the slideshow shows a motion photo and the toggle is ON:

1. Show the still (normal pipeline, transition included).
2. Prepare MediaPlayer async; on prepared → fade TextureView in (150 ms),
   play once.
3. On completion / any error → fade back to the still, release the player.

Rules for old hardware:

- **One MediaPlayer instance, ever.** Create → play → `release()` before the
  next slide; leaked players are the #1 OOM/ANR source on API 23 devices.
- Any `MediaPlayer` error (unsupported codec, broken trailer) downgrades
  silently to the still photo. The user must never see an error because a
  video track failed — this feature is a garnish, not a meal.
- HEIC-based motion photos: below API 28 the *still* itself can't be decoded;
  the Google Photos path already sidesteps this by caching server-transcoded
  JPEGs (see google-photos-picker skill) — those lose the video track, which
  is the correct trade-off on old frames. Local HEIC motion photos on
  API < 28: skip entirely.
- Respect battery saver (`PowerManager.isPowerSaveMode`): don't autoplay.

## Settings

`motion_photos_enabled` boolean, **default OFF** (this is the
weak-hardware-safe default; video decode wakes the SoC every slide). From
Google Photos, downloading the motion part (`=dv`) happens only when the
toggle is ON at sync time — flipping it ON later triggers a supplementary
sync, not a cache wipe.
