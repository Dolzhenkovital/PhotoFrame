# PhotoFrame

Android photo-frame app: fullscreen slideshow of photos from Google Photos
(with a local cache) and from local storage, designed to run well on OLD,
weak devices (Android 6.0+, ~1 GB RAM).

## Locked product requirements

1. Google Photos source via the **Photos Picker API** (the old Library API is
   dead for third parties since 2025-03) with a local disk cache; cache size
   configurable in settings, **default 1 GB**.
2. Local storage source (folder picked via SAF; SD/USB friendly).
3. Orientation-aware display: vertical frame prefers portrait photos,
   horizontal prefers landscape; wrong-orientation photos are letterboxed,
   never dropped.
4. Slide-change timer with presets (`core/SlideshowIntervals.kt`), default 30 s.
5. ~10 selectable transition effects + Random (catalog in the
   slideshow-engine skill).
6. Motion-photo playback with a settings toggle, default OFF.
7. **minSdk 23 / targetSdk 35**, Kotlin + classic XML Views (no Compose),
   frugal with RAM/CPU/battery — old frames are the reference hardware.

## Architecture decisions (do not re-litigate casually)

- Package: `com.smartphonekey.photoframe`; single-activity app.
- Manual DI, plain executors (no coroutines dep in v1), SQLiteOpenHelper
  (no Room), Glide for decode, HttpURLConnection for the two REST endpoints.
- Rotation handled via `configChanges` — the slideshow never restarts.
- Dependency policy and bitmap budget: see the low-end-performance skill;
  it is binding for every PR.

## Skills (in `.claude/skills/` — read the matching one BEFORE coding)

| Skill | Covers |
|-------|--------|
| google-photos-picker | OAuth, picker sessions, download, cache design (+ references/caching.md) |
| local-photos | SAF folder source, permission matrix, EXIF, scan index |
| slideshow-engine | queue, orientation matching, timer, transitions (+ references/transitions.md) |
| motion-photo | detection formats, zero-copy playback, toggle semantics |
| low-end-performance | dependency policy, bitmap/memory budget, threading, verification |
| android-compat | API 23→35 behavior matrix and guard rules |
| ci-cd | pipelines, secrets, local build commands, PR conventions |

## Build & test

- CI: Gradle 8.9 + JDK 17 (see `.github/workflows/android-ci.yml`).
- Local: Android Studio (bundled JDK 17), or CLI with JAVA_HOME → JDK 17:
  `gradle testDebugUnitTest lintDebug assembleDebug`.
- No Gradle wrapper committed yet (no local Gradle at bootstrap time); CI
  provisions its own. If you add the wrapper, update workflows to `./gradlew`.

## Roadmap

- [x] Phase 0 — infrastructure: skeleton, skills, CI/CD with LLM review
- [ ] Phase 1 — slideshow core: local photos (SAF), queue, timer, transitions, settings screen
- [ ] Phase 2 — Google Photos: OAuth, picker (QR flow), cache + sync
- [ ] Phase 3 — motion photos, polish (Ken Burns, fill-crop toggle), release workflow

Implementation starts only after explicit go-ahead from the owner.
