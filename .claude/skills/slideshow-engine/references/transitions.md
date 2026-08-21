# Transition effects catalog

## Contract

```kotlin
interface Transition {
    /** Animate outgoing/incoming views; MUST call onEnd exactly once,
     *  and MUST leave both views at alpha=1, translation=0, scale=1,
     *  rotation=0 with only `incoming` visible on top. */
    fun run(outgoing: ImageView, incoming: ImageView, onEnd: () -> Unit)
}
```

Rules that keep old GPUs happy:

- Use `ViewPropertyAnimator` only (`view.animate()...`), never
  ObjectAnimator XML sets, never animating layout params.
- `withLayer()` on both views for the duration of the animation (hardware
  layer), dropped automatically when it ends.
- Animate only alpha / translationX/Y / scaleX/Y / rotation — properties the
  RenderThread composites without re-drawing the bitmap.
- Duration 300–800 ms; interpolators noted per effect. Reset every property
  in a `finally`-style end action — a transition interrupted by a settings
  change must not leave a view half-translated.

## Catalog (enum `TransitionEffect`)

| # | Enum | Effect | Recipe (in = incoming, out = outgoing) |
|---|------|--------|----------------------------------------|
| 1 | `CROSSFADE` | Fade one into the other (default) | in.alpha 0→1 over 400 ms, out.alpha 1→0 simultaneously; linear |
| 2 | `FADE_BLACK` | Dip to black | out.alpha→0 (250 ms), then in.alpha 0→1 (250 ms); accelerate/decelerate |
| 3 | `SLIDE_LEFT` | New photo pushes in from the right | in.translationX w→0; out.translationX 0→−w; 450 ms, decelerate |
| 4 | `SLIDE_RIGHT` | From the left | mirror of SLIDE_LEFT |
| 5 | `SLIDE_UP` | From the bottom | in.translationY h→0; out.translationY 0→−h |
| 6 | `SLIDE_DOWN` | From the top | mirror of SLIDE_UP |
| 7 | `ZOOM_IN` | New photo grows from center | in.scale 0.7→1 + alpha 0→1; out.alpha→0; 500 ms, decelerate |
| 8 | `ZOOM_OUT` | Old photo falls away | out.scale 1→1.15 + alpha→0 over in at alpha 0→1; 500 ms |
| 9 | `KEN_BURNS` | Slow drift while displayed, crossfade between | during display: current view scale 1→1.08 with random pivot over the full interval (cap at 60 s); switch itself = CROSSFADE. Skip the drift when interval > 60 s or battery saver is on |
| 10 | `ROTATE_FADE` | Slight rotate + fade | in.rotation −8°→0 + alpha 0→1; out.alpha→0; 450 ms |
| 11 | `RANDOM` | Different effect each switch | uniformly pick 1–10, never the same twice in a row |

Settings UI shows them in this order with a live mini-preview later; v1 can
be a simple list.

## Adding a new effect

Add the enum entry + one `Transition` object + a row here + a unit test that
runs it with mock views asserting `onEnd` fires once and properties are
reset (use Robolectric only if plain JUnit with mocked views is impossible).
