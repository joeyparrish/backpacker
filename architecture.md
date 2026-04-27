# Backpacker - Architecture

"Backpacker" automates Pokéstop spinning in Pokémon GO. It runs as a background
service, looks at the screen through Android's MediaProjection API, and injects
gestures through an AccessibilityService - no root, no game file modification,
no network interception.

---

## Technology Stack

| Concern | Technology |
|---|---|
| Language | Kotlin |
| Build | Android Gradle Plugin 8.3.2, Gradle 8.7, Kotlin 1.9.22 |
| Min SDK | API 26 (Android 8.0) |
| Target SDK | API 35 |
| Screen capture | Android `MediaProjection` API |
| Gesture injection | Android `AccessibilityService` (`dispatchGesture`) |
| Computer vision | OpenCV for Android `org.opencv:opencv:4.12.0` (Maven Central) |
| Overlay UI | `TYPE_ACCESSIBILITY_OVERLAY` window via the AccessibilityService |
| Background execution | `ForegroundService` with persistent notification |

---

## Project Structure

```
app/src/main/java/io/github/joeyparrish/backpacker/
├── BackpackerApp.kt           - Application class: OpenCV init, notification channel
├── service/
│   ├── AutomationService.kt   - ForegroundService; owns MediaProjection lifecycle
│   ├── TapperService.kt       - AccessibilityService; gesture dispatch + overlay windows
│   └── ScreenshotService.kt   - MediaProjection wrapper; captures frames as OpenCV Mats
├── vision/
│   ├── PokestopDetector.kt     - HSV mask + contour analysis → disc centroids; visualize() for debug
│   ├── SpinnerDetector.kt      - Fixed-geometry annular ring mask → spinner state; visualize() for debug
│   ├── PassengerDetector.kt    - White dialog → green pill inside it → tap target; visualize() for debug
│   ├── ExitButtonDetector.kt   - Two circular masks (standard + menu Y positions) → white or aqua exit button → tap target; visualize() for debug
│   └── EscapeButtonDetector.kt - Canny edge template matching against reference icon → encounter escape button → tap target; visualize() for debug
├── automation/
│   └── AutomationEngine.kt    - Coroutine state machine; drives the full spin loop
├── ui/
│   ├── MainActivity.kt        - Collapsible Setup / Automation / Debug sections; app header; version display; overlay + debug switches
│   ├── OverlayView.kt         - Floating FAB: IDLE / HOUSE / CAR states
│   ├── HudView.kt             - Persistent two-line status overlay (bottom-left)
│   └── VisionDebugView.kt     - Full-screen tap-to-dismiss Bitmap overlay for vision debug
└── util/
    └── CoordinateTransform.kt - 720p ↔ device-pixel coordinate scaling
```

---

## Component Architecture

Three Android system components work together. They communicate through direct
object references (held as singletons) rather than through Intents, because the
objects are tightly coupled and live in the same process.

### ScreenshotService (MediaProjection)

Wraps a `VirtualDisplay` backed by an `ImageReader`. On each capture request it
acquires the latest `Image`, copies the pixel data into a Bitmap (for correct
channel ordering via `Utils.bitmapToMat`), converts it to an OpenCV `Mat`, and
returns it. The Bitmap is immediately recycled; only the Mat is returned.

The VirtualDisplay is created at 720 × (device-aspect) pixels regardless of
native resolution. This normalises all downstream CV work to a consistent pixel
space and eliminates a software downscale step that previously cost ~20 ms per
frame on a mid-range device.

`deviceWidth` and `deviceHeight` expose the *native* resolution so the
automation engine can translate 720p CV coordinates into actual touch positions.

### TapperService (AccessibilityService)

Provides three gesture primitives:

- `tap(x, y)` - 50 ms point stroke
- `swipe(x1, y1, x2, y2, durationMs)` - straight-line stroke; `suspend`; delays
  for `durationMs` after dispatch so the coroutine waits for gesture completion
- `back()` - `performGlobalAction(GLOBAL_ACTION_BACK)`

Also owns three overlay windows:

- **OverlayView** - draggable FAB, cycles IDLE → HOUSE → CAR → IDLE on tap
- **HudView** - persistent two-line status overlay (bottom-left); shows latest
  action and session spin stats; `FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCHABLE` so
  input passes through to the game
- **VisionDebugView** - full-screen tap-to-dismiss overlay for vision debug
  bitmaps; captures back gesture so it does not fall through to the game

All use `TYPE_ACCESSIBILITY_OVERLAY` which requires no `SYSTEM_ALERT_WINDOW`
permission.

### AutomationService (ForegroundService)

Owns the MediaProjection token and the `AutomationEngine` coroutine. Three-state
lifecycle:

```
PREPARING → READY → RUNNING
```

- **PREPARING**: service enters foreground before the consent dialog (Android 14
  requires this timing).
- **READY**: consent granted; `ScreenshotService` and VirtualDisplay are live;
  no capture loop yet.
- **RUNNING**: coroutine scope and `AutomationEngine` created; capture loop
  active.

Transitioning from RUNNING to READY (pause) keeps the MediaProjection token
alive so the user can resume without a new consent dialog.

---

## The Automation Loop

```
run()
│
├─ [spinner debug mode]        → one-shot capture, report disc state, auto-pause
├─ [exit button debug mode]    → one-shot capture, show button circle overlay, auto-pause
├─ [escape button debug mode]  → one-shot capture, show Canny edge overlay, auto-pause
│
└─ SCAN LOOP (until stopped)
    │
    ├─ If screen off → sleep SCREEN_OFF_POLL_MS → repeat
    │
    ├─ capture() → Mat (720p RGBA)
    │   └─ If null → sleep CAPTURE_RETRY_MS, retry
    │
    ├─ ExitButtonDetector.detect(screenshot)
    │   └─ If button found → tap, sleep DISMISS_DELAY_MS, repeat loop immediately
    │      (checked first — overrides PassengerDetector on buddy screen)
    │
    ├─ EscapeButtonDetector.detect(screenshot)
    │   └─ If button found → tap, sleep DISMISS_DELAY_MS, repeat loop immediately
    │      (handles accidental Pokémon encounter taps)
    │
    ├─ PassengerDetector.detect(screenshot)
    │   ├─ [passenger debug mode] → one-shot visualize, auto-pause
    │   └─ If button found → tap, sleep DISMISS_DELAY_MS, repeat loop immediately
    │
    ├─ PokestopDetector.detect(screenshot) → DetectionResult
    │   └─ If no passing discs → sleep scanIntervalMs → repeat
    │
    ├─ Pick one disc at random → spinDisc()
    │   │
    │   ├─ tap(centroid)
    │   ├─ sleep OPEN_DELAY_MS - detail view animation
    │   │
    │   ├─ checkDiscState() - initial check
    │   │   ├─ null / ABSENT → wrong tap target → return false
    │   │   │   (next iteration's exit/escape checks handle recovery)
    │   │   ├─ PURPLE → already spun → back(), return false
    │   │   └─ CYAN → proceed
    │   │
    │   ├─ up to NUM_SPIN_ATTEMPTS × [swipe(left→right, SWIPE_DURATION_MS) → checkDiscState()]
    │   │   (swipe() is suspend; each call waits for gesture completion)
    │   │   Break early if state == PURPLE (server accepted the spin)
    │   │
    │   ├─ success = (finalState == PURPLE)
    │   │
    │   ├─ back()
    │   ├─ if success → sessionSpins++, lifetime_spins++ (SharedPrefs)
    │   └─ return success
    │
    ├─ If !success or multiple discs → thisLoopDelay = SCAN_IMMEDIATELY_MS
    └─ else → thisLoopDelay = scanIntervalMs

Scan intervals:
  HOUSE mode - slow (stationary)
  CAR mode   - fast (driving)
```

---

## Computer Vision

### Pokéstop Disc Detection (PokestopDetector)

**Problem:** Pokéstop discs are 3D planes viewed from an elevated angle. As a
disc rotates around its vertical axis, its apparent *width* varies continuously
from a full ellipse (facing the camera) to a near-zero-width sliver (edge-on).
Height stays roughly constant. Colour is consistent. Shape metrics other than
height are unreliable.

**Algorithm:**

1. Convert RGBA → HSV (`Imgproc.cvtColor`)
2. `Core.inRange` with a cyan HSV mask
   - Calibrated: H=85–105, S=150–225, V=185–255
3. Morphological close (5×5 kernel) to fill small gaps in the mask
4. `Imgproc.findContours`
5. Filter contours:
   - Bounding-box **height** 36–110 px at 720p (intentionally low — a stop near
     the spin-radius boundary appears small; height is still stable)
   - Bounding-box centre must fall within a spin-radius ellipse centred on the
     screen (NX=0.516, NY=0.233 semi-axes) — stops outside tapping range are
     skipped
   - Bounding-box centre must not fall within any of five named exclusion zones
     (game UI elements: pokeball bar, experience bar, etc.)
6. Compute centroid via image moments (not bounding-box centre — the disc image
   sits above the pole, so the bounding-box midpoint lands on the pole rather
   than the tappable disc).

**Why not template matching:** The disc has no fixed shape - it varies
continuously with rotation angle. There is no single representative template.

**Why not filter on area alone:** An edge-on disc is a tall thin sliver with
near-zero area. Height is the stable discriminant.

### Spinner State Detection (SpinnerDetector)

After opening a stop, the large spinner ring is either:
- **Cyan/blue** - ready to spin
- **Purple** - already spun (cooldown)
- **Absent** - circle not found (wrong screen, animation in progress)

**Algorithm:**

1. Convert RGBA → HSV.
2. Build a fixed-geometry annular mask over the known spinner ring position
   (defined as fractions of frame dimensions; computed once on first call and
   reused). Center: 50% x, 48.0% y. Outer radius: 43.85% of width. Inner
   radius: 39.35% of width.
3. AND the ring mask against purple / cyan HSV colour masks in turn.
4. Report the colour whose ring-pixel fraction exceeds 70%. If neither, ABSENT.

**Calibrated values:**
- Outer radius: 43.85% of width; inner: 39.35%
- Purple HSV: H=114–155, S=75–205, V=145–255 (expanded for overcast/low-light)
- Cyan HSV: H=90–115, S=175–240, V=155–255
- Purple threshold: 42% of strip-ring pixels (lower because spin-reward items
  can cover the bottom half of the ring)
- Cyan threshold: 70% of ring pixels

Purple is checked against a narrower centre-strip mask (8% of frame width) to
reduce interference from adjacent UI colour; cyan uses the full ring mask.

**Why a fixed mask instead of HoughCircles:** HoughCircles sometimes found
circles on the map when the detail view was not open, causing false spins.
A fixed-geometry mask eliminates this failure mode entirely and is faster.

**Why an annular mask instead of a full-circle or ROI:** The disc photo in the
centre of the spinner view is also coloured and could trigger false positives.
The ring mask isolates the spinner band and ignores the interior.

### Speed-Warning Dialog Detection (PassengerDetector)

Detects the "You're going too fast!" dialog (and any other PoGO dialog that
uses the same gradient pill button) so the engine can dismiss it without
stalling.

**Algorithm:**

1. Convert to greyscale; threshold at brightness 230 to isolate near-white
   pixels.
2. Find the largest bright contour covering ≥ 12% of screen area — that is
   the white dialog box. If none found, return null (no dialog present).
3. Compute the full-image green HSV mask (H=35–110, S>60, V>120), then zero
   out every pixel outside the dialog bounding rect. This prevents the
   greenish background overlay from connecting to the button through the
   dialog edge, which would merge them into one contour and destroy the
   aspect-ratio signal.
4. Find contours inside the restricted mask; select the largest one with
   aspect ratio ≥ 3.0 and area ≥ 10 000 px² — that is the pill button.
5. Return the bounding-rect centre as the tap target.

**Why detect the dialog box first:** The green background overlay and the
button share the same HSV range. Without the dialog as an ROI, they touch
at the dialog edge (even without morphological operations) and OpenCV sees
one large blob. The dialog box is a reliable prerequisite: the button only
exists inside it.

**Why no morphological close:** A morph close was tried to fill the
white-text holes inside the button, but it bridged the button to the
background, causing the merge described above. `RETR_EXTERNAL` ignores
internal holes, so the outer button boundary is intact without it.

**Button appearance:** Yellow-green to teal horizontal gradient with white
text. The pale left side has low saturation (~S=60), hence the loose S
lower bound. Other PoGO dialogs with the same gradient button style are
matched intentionally.

### Exit Button Detection (ExitButtonDetector)

Detects the bottom-centre circular X button that dismisses gyms, menus, the egg
viewer, the Pokémon viewer, and similar full-screen overlays.

**Two colour variants:**
- White — white fill with aqua outline/icon (gym, raid, battle screens)
- Green — aqua fill with yellow-green icon (menus, egg viewer, Pokémon viewer)

**Two candidate positions** (Y varies by screen type):
- Standard: NY=0.941 (2259/2400) — gym, spinner detail, most views
- Menu: NY=0.929 (2230/2400) — main menu, bag, egg viewer, Pokémon viewer

**Algorithm:** For each candidate, build a circular mask (radius NX=0.048),
measure the fraction of masked pixels matching the white HSV range (S≤30, V≥235)
and the aqua range. The candidate with the highest max(white, aqua) ratio that
also exceeds DETECT_THRESHOLD (0.63) is selected and its centre returned.
Using the best-signal candidate rather than the first over threshold prevents
the two overlapping circles from mis-selecting when the button sits at the higher
position (the lower circle still gets partial signal from overlap).

### Pokémon Encounter Escape Button (EscapeButtonDetector)

Detects the top-left running-figure icon shown when a wild Pokémon encounter is
open. Tapping it flees the encounter and returns to the map.

**Why not colour thresholding:** The icon is white, so any white content in that
screen region (UI, backgrounds) produces a high white-pixel ratio with no icon
present. Counting pixels is not shape-sensitive enough.

**Algorithm — Canny edge template matching:**
1. At construction, load the reference crop (`res/raw/escape_icon.png` — the
   unmodified screenshot region including the drop shadow and background),
   convert to greyscale, and compute Canny edges. The strong edge at the
   white-icon / dark-shadow boundary is the distinctive signal.
2. Rescale the edge template once per frame-size change.
3. In detect(), extract a slightly padded greyscale ROI from the screenshot at
   the known button position (NX=0.060–0.145, NY=0.049–0.085), compute its
   Canny edges, and run `matchTemplate(TM_CCOEFF_NORMED)`.
4. Return the button centre if the best match score ≥ MATCH_THRESHOLD (0.3,
   to be calibrated).

On a uniform white background there are no edges in the ROI, so the score is
near zero. On the encounter screen the icon outline produces a strong match.

---

## Why These Design Decisions

**Success = `finalState == PURPLE`:** The engine checks state after *each* swipe
and breaks out of the swipe loop the moment PURPLE is seen. Because the check
runs frequently, it catches the PURPLE state before the animation frames obscure
it. Checking between swipes also stops unnecessary extra swipes once the server
has accepted the spin.

**Spin by many rapid swipes, check state once after:** Multiple swipes cost
negligible extra time, but substantially improve reliability. GPS jitter while
driving can cause momentary range failures; multiple swipes mean at least some
will register. Extra swipes after a successful spin accelerate the animation
and leave the ring in a more stable state for the post-spin detector check.

**No range pre-check:** If the stop is slightly out of range the spin will fail,
which the engine detects via the `finalState != CYAN` check. Range failures and
network failures are handled identically. Adding a range check would require
parsing additional UI elements (distance badge) for no net gain.

**HOUSE (slow) and CAR (fast) scan modes:** When stationary, re-scanning every
second wastes CPU and battery - stops don't appear or disappear that fast. While
driving, new stops scroll into view every few seconds so a shorter interval is
worthwhile.

**Screen-off skip:** The VirtualDisplay continues compositing when the screen
is off, but there is nothing useful to detect. Skipping CV work while
`!powerManager.isInteractive` reduces CPU load during pocket time.

**720p VirtualDisplay:** Creating the VirtualDisplay at the normalised capture
resolution (720 × proportional height) eliminates the per-frame software
downscale that previously ran on the CPU after capture. On a mid-range device
this saved ~20 ms/frame and reduced battery overhead by ~50% in 30-minute tests.

**Mat pre-allocation:** OpenCV Mats are JNI-allocated objects. Allocating and
freeing them per scan creates GC pressure and JNI malloc/free overhead. Instance
fields in `PokestopDetector` and `SpinnerDetector` hold scratch Mats that are
reused (cleared with `setTo(Scalar(0.0))`) across calls.

**AccessibilityService for gestures:** `dispatchGesture` injects touches into
any app with no root required and no hooking of the target process. The only
requirement is that the user manually enables the service in Android Settings →
Accessibility.

**`swipe()` is `suspend`:** `dispatchGesture` returns immediately; the gesture
executes asynchronously. Without an explicit `delay(durationMs)` the coroutine
fires all 10 swipes near-simultaneously, which the gesture system collapses or
drops. Making `swipe` suspend ensures each swipe completes before the next.

---

## What Didn't Work

**JitPack OpenCV (`iamareebjamal/opencv-android`):** All versions ≥ 4.3.0 fail
to build on JitPack due to broken JNI packaging. Switched to the official
`org.opencv:opencv:4.12.0` on Maven Central.

**OpenCV < 4.12.0 on 16KB-page devices:** Android 15 devices require both (a)
ELF LOAD segments aligned to 0x4000 and (b) uncompressed `.so` files
offset-aligned to 16KB within the APK ZIP. OpenCV 4.9.0 and 4.10.0 have 4KB
LOAD alignment. AGP 8.3+ handles the ZIP alignment; OpenCV 4.12.0 fixes the ELF
alignment.

**AGP 8.1.x + Java 21:** AGP 8.1.x uses jlink internally and fails with Java 21.
Upgrading to AGP 8.3.2 resolved this; no `org.gradle.java.home` override needed.

**`Activity.RESULT_OK = -1` as getIntExtra sentinel:** The service passes the
MediaProjection result code via an Intent extra. Using -1 as a "not set"
sentinel collides with `Activity.RESULT_OK`. Fixed by using `Int.MIN_VALUE`.

**Center-first disc ordering:** Sorting detected discs by distance from screen
centre before spinning seemed logical (the closest stop is most likely in range)
but caused the engine to repeatedly attempt the same difficult stop instead of
trying an easier nearby one. Replaced with random selection and rescanning to
handle movement (deliberate or drift) between spins.

**The original retry loop structure:** The first `spinDisc()` implementation
used a loop that checked disc state between each swipe and attempted to back
out and re-enter if the state changed. This got out of sync with the actual UI.
It would back out to the map, swipe the map, then try to back out of the game
entirely. Replaced with the current single-check-before / single-check-after
structure.

**`finalState != CYAN` as the success condition:** Used briefly to avoid the
spin-animation intermediate ABSENT state when checking once after all swipes.
Replaced with `finalState == PURPLE` once the engine was changed to check state
after *each* swipe — catching PURPLE before the animation can obscure it, and
stopping extra swipes as soon as the server accepts the spin.

**`OverlayView` inflated without a theme context:** `LayoutInflater` from a
Service does not carry the app's Material theme. `FloatingActionButton` throws
"You need to use a Theme.MaterialComponents theme" at runtime. Fixed by wrapping
with `ContextThemeWrapper(context, R.style.Theme_Backpacker)` before inflating.

**`TYPE_ACCESSIBILITY_OVERLAY` Y-coordinate offset:** The overlay window's
content area starts below the status bar even with `FLAG_LAYOUT_IN_SCREEN`. The
`VirtualDisplay` captures from physical y=0. This was not a problem once the
debug overlay switched to a full-screen `MATCH_PARENT × MATCH_PARENT` window
with `FIT_XY` scaling — the image fills the window and the slight offset is
imperceptible for debug use.

**`FLAG_NOT_FOCUSABLE` leaks back gesture:** Overlay windows using
`FLAG_NOT_FOCUSABLE` do not receive key events, so back button / swipe-from-edge
falls through to the underlying app. Fixed in `VisionDebugView` by omitting
the flag and using a `FrameLayout` container that intercepts `KEYCODE_BACK`.

**HoughCircles for spinner detection:** HoughCircles found circles on the map
when the detail view was not open, causing the engine to swipe the map. Replaced
with a fixed-geometry annular ring mask (fractions of frame dimensions) that
only examines the known spinner position, eliminating the false-positive failure
mode entirely.
