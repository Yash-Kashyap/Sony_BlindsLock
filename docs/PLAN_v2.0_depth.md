# Plan v2.0 — "pressed-in" Xperia blinds (depth dimple)

Author: Claude Opus 5.5 (lead/plan), 2026-11-12. Builder: Claude Sonnet (or GPT Codex after 2026-11-13).
Scope: `app/src/main/java/com/yash/blindslock/BlindsView.java` (renderer + dynamics) and its tests.
Public API of BlindsView stays the same (`configure`, `setScene`, `resetToLocked`, `startReveal`,
`setBackdropClear`, `forceFinishAllowed`, `setForceRectRenderer`, `Listener`). LockActivity must not change.

## 1. What is wrong in v1.9

v1.9 models each slat as a **hinged prism that flips outward** (`geom()` puts a fold edge at top/bottom,
shows an "outgoing" and an "incoming" face). The slat nearest the finger turns the MOST.
Shading is a flat white/black tint. There is no perspective and no Z depth at all.

## 2. What the original does (evidence)

Sources: Sony "BlindsView Tutorial Demo" video (frames at 43.8–45.6 s, 18–30 s); the reverse-engineered
renderer of the XDA app `com.othlocks.xperia.blinds` documented in
github.com/Brazzo978/L.L.E-Legacy-Lockscreen-Effects (`LLEUnified/ports/xperia-z1-blinds-worker/AUDIT.md`,
`LLEUnified/src/com/codex/lle/XperiaBlindsEffectView.java`). That repo is PolyForm-Noncommercial: we only
take the *ideas/numbers*, we do not copy code.

Facts:
- It is a **dimple**, not a hinge. With normalized distance `d = (slatCenterY - touchY) / halfWindow`
  in (-1, 1):
  - depth (push INTO the screen): `fold = (1 + cos(PI*d)) * s` → max at the finger, 0 at window edge.
  - tilt: `rotateX(17° * sin(PI*d) * s)` → **0 at the finger**, max at |d| = 0.5, opposite sign above vs
    below. So slats above and below slope down into the finger like a V. That is the "goes inwards" look.
  - `s` = one global spring (stiffness 400, damping ratio 0.85), target 1 while finger down, 0 on release.
  - perspective via `android.graphics.Camera` (eye 576 px in front), `translateZ(3 * fold)`.
  - small in-plane roll: `(0.5 - touchX/width) * 3° * fold` → slats lean toward the finger's side.
- Shading by tilt sign: one side brightened (`OVERLAY` white, alpha up to ~99/255 × 2 × |wave·s|),
  other side darkened (`LightingColorFilter` multiply down to ~155/255). In Sony video: slats ABOVE the
  finger dark, slats BELOW bright.
- Each folded slat gets a black gradient shadow from its TOP edge (0xbb000000 → transparent, length
  50 px × (1 − |d|)) and 2 px seams (0xbb2b2b2b) on top + bottom edges.
- Black shows in the gaps (slats shrink when tilted / pushed back). 17 bands, window = 5 of 17.
- Unlock: quick fade of the face (global alpha from 40 ms, done ~200 ms), per-band fade staggered outward
  from the finger (near-finger bands vanish first, 300 ms). Sony video: lock face crossfades to home quickly.

## 3. New model (build this)

Keep v1.9 strengths: textured triangle mesh (`drawVertices`), Android 9 rect fallback, band size from
settings (`bandHalfWidth()`), haptics, reveal/backdrop contract, `onBlindsSettled`.

### 3.1 Dynamics (replace per-slat `angle[]/velocity[]` springs)
- One press spring `s, sVel`. Target `S_TARGET = 1.0` while touching (optionally `+0.25*progress` so the dent
  deepens as you swipe — tunable, start at 0.25). Stiffness `SPRING_K = 400`, damping ratio `0.85`,
  integrate with the analytic damped-spring step (dt clamp 1/30 s) or existing sub-stepping.
- Touch point used for the dent: `px, py` follow the finger with frame-rate independent smoothing
  `p += (finger - p) * (1 - exp(-dt * 30))` (removes jitter, no visible lag). On DOWN snap to finger.
- On UP without unlock: target 0 → spring back (that gives the small settle bounce, damping 0.85).
- Keep a tiny tap ripple: on DOWN add `sVel += 6`.

### 3.2 Geometry per vertex (mesh, COLS = 18 columns per slat, 2 rows: top edge + bottom edge)
For slat i (top `t`, bottom `b`, center `yc`, height `h`), `d = (yc - py) / halfW`; skip if |d| >= 1
(draw as flat batch like today).
For each column x:
```
wx    = 0.55 + 0.45 * exp(-(x - px)^2 / (2 * (0.35 w)^2))     // dent strongest under the finger in X
z     = Z_MAX * s * 0.5 * (1 + cos(PI d)) * wx                 // >0 = into the screen
theta = THETA_MAX * s * sin(PI d) * wx                          // d<0 (above finger) => theta<0
for edge ly in { -h/2 (top), +h/2 (bottom) }:
    yv = yc + ly * cos(theta)
    zv = z  - ly * sin(theta)      // above finger: bottom edge deeper; below: top edge deeper (V dent)
    k  = F / (F + zv)              // perspective, eye at (px, py) — dent converges to the finger
    X  = px + (x  - px) * k
    Y  = py + (yv - py) * k
```
Texture coords stay the slat's own slice (u = x, v = t or b) — no crop/zoom.
Defaults (tunable constants at top of file):
`F = 1.6 * max(w, h)`, `Z_MAX = 0.10 * F` (≈ 9 % shrink at the finger), `THETA_MAX = 30°`
(original 17° looks too flat vs the Sony video gaps; tune on device 17–35°), `ROLL = 3°` in-plane
lean `(0.5 - px/w) * ROLL * fold` about the slat center.
Check the signs on device: slats must visibly sink, the edges nearest the finger must be the deepest,
black gaps must open between slats, and nothing may poke OUT toward the viewer.

### 3.3 Draw order
Nearer (less deep) slats must cover deeper ones. Order: flat batches first (they never overlap the
window), then the window slats sorted by descending `z` (the finger slat first, window edges last).
Vertex order in the buffer = paint order.

### 3.4 Shading (per-vertex colour quads, same buffer style as today)
- Tilt light: `L = sin(theta) * SHADE` (SHADE ≈ 0.75). Slats above finger (theta<0) → black alpha
  `|L| * 0.45`; below (theta>0) → white alpha `|L| * 0.30`. Verify against video: above = dark, below = bright.
- Depth occlusion: extra black alpha `0.35 * (z / Z_MAX)` (deeper = darker). This is what sells "inward".
- Top-edge shadow: per slat in the window, a quad from the projected top edge down by
  `min(h*0.6, 50dp*(1-|d|)) * k`, colour black alpha `0.73*s` at top → 0 at bottom (needs per-vertex colour,
  which colorQuad supports).
- Seams: 1.5 dp lines at projected top and bottom edges, colour `0x2b2b2b`, alpha `0.73 * min(1, fold*4)`.
- Gaps: the backdrop (`Color.argb(…, 6, 8, 12)`) already shows through. Keep it opaque black-ish while
  locked.
- Remove `shadeA/shadeB/edge/geom` prism code and the incoming-face quads.

### 3.5 Unlock reveal (replace 90° flip)
- On `startReveal`: keep `s` at its current value (dent stays), set per-slat alpha delay
  `delay_i = 120 ms * |d_i|` (from the finger outward), each slat fades over 160 ms; the whole face
  (`faceAlpha` / `applyFaceAlpha`) fades from 40 ms to 220 ms. Keep `backdrop`, `allowFinishWhenOpaque`,
  `onRevealFinished` exactly as today. Per-slat alpha = multiply vertex alpha of texture? drawVertices has
  no per-vertex alpha for the texture → draw the fading slats with a black-alpha colour quad over them
  only if needed; simpler acceptable version: global face fade + dent springing to 0 (original behaviour).
  Builder: implement the simple version first, stagger only if it looks better.

### 3.6 Android 9 fallback (`drawWithRects`)
Approximate: per slat, scale about (px, py) by `k` at slat center, height `h*cos(theta)*k`, colour rects for
shade/shadow/seams. No twist columns needed (use 6 columns for wx).

## 4. Settings
Keep "Blinds that move with your finger" (bandSlats) = window size; default changes 6 → 5 (original 5/17).
Keep slat height setting. Optional later: a "Depth" slider (Z_MAX) — not in this task.

## 5. Tests / verification
1. `./gradlew testReleaseUnitTest` — update BlindsRenderTest expectations (it renders frames to
   `build/frames/*.png`; look at them: press at 1/3 height, mid-swipe, release, reveal).
2. Add one assert test: for a press at y, the slat at the finger has theta≈0 and the largest z; slats at
   d=±0.5 have opposite theta signs; all projected slats stay inside their unprojected slot (k<=1, nothing
   pops out).
3. Device: build release, install, screen-record (`adb shell screenrecord`), compare side by side with
   Sony frames at 43.8–45.6 s. Tune THETA_MAX, Z_MAX, shade alphas.

## 6. Build prerequisites (not done yet)
- JDK 17+ (only Zulu JRE 11 present), Android SDK 35 + build-tools.
- `keystore/` missing → cannot sign as v1.9. Ask the user if they have the keystore. Otherwise new debug/
  release key and uninstall v1.9 first (user loses app settings). Bump versionCode 11 / versionName 2.0.
