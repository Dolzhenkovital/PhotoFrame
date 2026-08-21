---
name: slideshow-engine
description: >-
  The heart of PhotoFrame: the slideshow loop — photo queue and shuffling,
  orientation-aware photo selection (portrait photos on a vertical frame,
  landscape on a horizontal one), the change-interval timer with presets, and
  transition effects between photos. Use this skill whenever the task touches
  the slideshow, photo switching, timers, intervals, transitions, animations,
  rotation/orientation behavior, fullscreen display, or preloading — even if
  the request just says "make the photos change nicer".
---

# Slideshow engine

## Architecture (two ImageViews, one loop)

The display surface is a `FrameLayout` with **two stacked ImageViews** (`back`
and `front`). Showing the next photo means: decode into the hidden view →
animate the swap → the views trade roles. This is the entire rendering model;
no RecyclerView, no ViewPager — nothing else survives on old hardware as well
as two plain views.

The loop is a small state machine driven by a `Handler` on the main looper:

```
IDLE → PRELOADING (decode next bitmap on background executor)
     → READY      (bitmap set on hidden view)
     → waiting for timer tick
     → TRANSITION (animate, 300–800 ms)
     → IDLE
```

Preload the *next* photo immediately after a transition finishes, not when
the timer fires — the timer tick must only start an animation, never I/O.
If decode is slower than the interval (huge photo, slow SD), skip the tick
and fire when ready; never queue up ticks.

## Timer

- Presets live in `core/SlideshowIntervals.kt` (already in the repo):
  5 s, 10 s, 15 s, 30 s, 1 m, 2 m, 5 m, 10 m, 30 m, 1 h; default 30 s.
- Use `handler.postDelayed`, cancel-and-repost on every change; do not use
  `Timer`/`ScheduledExecutor` (extra thread, drifts after Doze).
- Pause the loop in `onPause`, resume with a fresh tick in `onResume` —
  never animate while not visible (wasted battery, and `TextureView` /
  animations misbehave in background).

## Orientation-aware selection

Classify every photo once, at index time, using **post-EXIF-rotation**
dimensions (see local-photos skill):

```
ratio = width / height
PORTRAIT  if ratio < 0.9
SQUARE    if 0.9..1.1
LANDSCAPE if ratio > 1.1
```

Device orientation comes from `resources.configuration.orientation`, updated
in `onConfigurationChanged` (the manifest already opts into
`configChanges="orientation|screenSize|..."` so rotation does NOT recreate
the activity — the slideshow must not restart when the user rotates the
frame).

Selection rule: build the playback queue from the matching bucket (+ SQUARE,
which fits both). Photos of the "wrong" orientation are **not dropped** —
they stay in a fallback queue used when the matching bucket is exhausted, so
a user with 95% landscape photos still sees everything on a vertical frame
(letterboxed, centered on black). Starving whole albums is a bug; showing a
letterboxed photo is not.

Shuffle: Fisher–Yates over the queue, reshuffle when exhausted, and keep a
no-repeat window of `min(queueSize/2, 20)` so reshuffles don't show the same
photo twice in a row.

## Display

- `ImageView.scaleType = FIT_CENTER` on black background. Do not crop by
  default (people hate beheaded relatives); a "fill & crop" toggle can come
  later.
- Fullscreen immersive: `WindowInsetsControllerCompat` (androidx handles the
  API 23 vs 30+ split). Re-hide bars on window focus regain.
- Decode bitmaps at screen size, RGB_565 — the exact recipe, Glide setup and
  memory budget are in [[low-end-performance]]; follow it strictly here, this
  loop is where the app lives or dies on old frames.

## Transitions

Ten effects + Random mode. The exact catalog, parameters and the
`Transition` interface contract are in
[references/transitions.md](references/transitions.md) — read it before
adding or modifying any effect. Config keys: `transition_effect` (enum name,
default `CROSSFADE`), duration fixed per effect (no user setting in v1).

## Testing

Queue building, orientation bucketing, shuffle window and timer scheduling
are pure Kotlin with an injected clock — unit-test all of them (edge cases:
empty source, single photo, all-wrong-orientation). Animation code itself is
verified by eye on a real frame; keep it thin and dumb.
