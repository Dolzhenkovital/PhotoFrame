---
name: low-end-performance
description: >-
  The performance bible for PhotoFrame: hard rules that keep the app running
  on old, weak photo frames (Android 6.0, 512 MB–1 GB RAM, slow flash, tired
  GPU). Consult this skill before adding ANY dependency, writing bitmap/image
  code, choosing an architecture component, or touching the render/animation
  path — and whenever the task mentions performance, memory, OOM, lag, jank,
  battery, APK size, or "the frame is slow".
---

# Low-end performance rules

The reference device is a ~2016 tablet-turned-frame: 1 GB RAM (app heap cap
64–128 MB), 4 slow cores, eMMC/SD storage, 1280×800 screen, Android 6.0.
Every rule below exists because breaking it visibly hurts that device.

## Dependency policy (the strongest lever)

- Allowed baseline: `core-ktx`, `appcompat`, `preference`, `exifinterface`,
  Glide, `play-services-auth` (Google source only), `zxing:core` (QR for the
  picker URI — pure Java, no transitive deps). Everything else needs a
  written justification in the PR description.
- Banned outright: Jetpack Compose (RAM/APK cost), Hilt/Dagger (use manual
  constructor injection — the app has ~10 classes that need wiring), RxJava,
  Retrofit+OkHttp for our two REST endpoints (`HttpURLConnection` is enough),
  Room (plain `SQLiteOpenHelper`), any analytics/crash SDK in v1.
- Release build already minifies + shrinks resources; add
  `resourceConfigurations += listOf("en", "uk")` when translations appear.
  Target APK ≤ 4 MB.

## Bitmap budget (where OOM lives)

- **At most 3 decoded bitmaps alive**: current, next (preload), transient
  during transition. Nothing else may hold a bitmap reference — no "recent
  photos" arrays, no thumbnail grids without Glide.
- Decode at **screen size**, never intrinsic size: Glide
  `.override(screenW, screenH).downsample(AT_MOST)`.
- **`RGB_565`** default format (`.format(PREFER_RGB_565)`): half the memory
  of ARGB_8888; on a photo frame panel the banding is invisible. 1280×800
  ×2 B ≈ 2 MB per photo → 3 bitmaps ≈ 6 MB. That is the entire image budget.
- Glide setup via `AppGlideModule`: `MemoryCategory.LOW`,
  `diskCacheStrategy(NONE)` for files from our own cache (they ARE the disk
  cache), small memory cache (~2 screen-sizes).
- No `android:largeHeap`. It doesn't fix leaks, it hides them until the
  device swaps itself to death.

## Threading

- Exactly two background executors, created once in the Application class:
  `decodeExecutor` (1 thread) and `ioExecutor` (1–2 threads: downloads, DB,
  scans). Main thread does UI only. No coroutines dependency in v1 — plain
  executors + `Handler(mainLooper)` callbacks keep the dex small and the
  behavior obvious; revisit only if async code becomes genuinely hard to read.
- Nothing on the main thread may open a file, query a ContentResolver, or
  touch SQLite. StrictMode (debug builds only) enforces this:
  `detectDiskReads/Writes/Network` + `penaltyLog`.

## Render path

- Transition rules (hardware layers, animatable properties only) are in the
  slideshow-engine skill — they are performance rules; treat them as part of
  this document.
- Overdraw: the window background is black, ImageViews sit directly on it —
  never add intermediate containers with backgrounds. Check with the GPU
  overdraw dev tool: photo area must stay uncolored (1× draw).
- No animations while a MediaPlayer is preparing (motion photos) — the old
  GPU can composite a video OR animate views, not both.

## Battery / thermal

- `FLAG_KEEP_SCREEN_ON` only — never a WakeLock (screen-on is the product;
  CPU wakelocks are a bug).
- Sync (downloads) only while charging + on Wi-Fi by default. Frames live on
  chargers; phones running the app as a bedside frame will thank us.
- Honor `isPowerSaveMode`: pause Ken Burns drift and motion-photo autoplay.

## Verification (do this, don't guess)

```bash
adb shell dumpsys meminfo com.smartphonekey.photoframe   # PSS after 30 min slideshow: target < 60 MB
adb shell dumpsys gfxinfo com.smartphonekey.photoframe   # jank % during transitions: target < 5%
```

Any PR that touches bitmaps, transitions, or adds a dependency states the
before/after of at least the meminfo number on the oldest available device
(or the API 23 emulator with 1 GB RAM profile as a proxy).
