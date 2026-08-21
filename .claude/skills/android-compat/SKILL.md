---
name: android-compat
description: >-
  Compatibility matrix for supporting Android 6.0 (API 23) through Android 15
  (API 35) in one APK: which platform behavior changes bite PhotoFrame at
  each API level and the required guards. Use this skill whenever writing
  version-dependent code (Build.VERSION checks), raising targetSdk, adding
  permissions, scheduling background work, or when anything "works on the new
  phone but not on the frame" (or vice versa).
---

# API 23 → 35 compatibility matrix

minSdk 23 / targetSdk 35 means the app must run under BOTH 2015 rules and
2025 rules. This table lists only changes that affect *this* app; guard with
`if (Build.VERSION.SDK_INT >= N)` and keep both branches testable.

| API | Change | What PhotoFrame must do |
|-----|--------|-------------------------|
| 23 | Runtime permissions; Doze mode | SAF path avoids storage permissions entirely (see local-photos). Doze defers network — schedule syncs with WorkManager charging constraint instead of exact timing |
| 24–25 | Multi-window | Nothing special; FIT_CENTER already adapts |
| 26 | Background execution limits; notification channels | No background services — all deferred work through WorkManager. Channels only if we ever post a notification |
| 28 | HEIC/HEIF decode appears; non-SDK API restrictions | Gate `image/heif` on `SDK_INT >= 28`; never touch non-SDK APIs |
| 29 | Scoped storage | Content URIs everywhere, zero raw `File` paths to shared storage |
| 30 | SAF can no longer grant the whole Downloads/root; package visibility (`<queries>`) | Folder picker fine for normal folders. Add `<queries>` for `CustomTabsService` when the OAuth flow opens a browser |
| 31 | Splash screen system UI; exact alarms restricted | No exact alarms (Handler ticks only run while foreground). Optional `androidx.core:core-splashscreen` later |
| 33 | `READ_MEDIA_IMAGES` replaces storage permission; per-app language | Only if the MediaStore source is implemented. Per-app language: plain `values-uk/` works |
| 34 | Partial media access; foreground-service types mandatory | Partial access = user's selection, respect it silently. We have no foreground services — keep it that way |
| 35 | **Edge-to-edge enforced** (status/nav bars transparent over the app); 16 KB page size | Our UI is fullscreen black — edge-to-edge is free, but settings screens must apply `WindowInsets` padding via `ViewCompat.setOnApplyWindowInsetsListener`. Pure-Kotlin/Java app: 16 KB pages are a non-issue (no NDK libs — keep it that way) |

## Cross-cutting rules

- Every `SDK_INT` branch gets a comment saying which device class hits it
  ("frame path" vs "modern phone path") — future readers must know what they
  can delete when minSdk finally rises.
- Never call an API above 23 without a guard; Android Lint's `NewApi` check
  runs in CI and is treated as an error, not a warning.
- Test matrix for instrumented tests (when added): API 23 emulator (the
  frame) and API 35 emulator (current phones). Behavior differences belong
  in tests, not in comments.
- WorkManager is the one scheduling abstraction for anything that may run
  when the activity is gone (cache sync). Everything visible runs on the
  activity's Handler. No AlarmManager, no JobScheduler directly, no services.
