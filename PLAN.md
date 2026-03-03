# Pokéstop Automator — Problem Statement & Implementation Plan

## Overview

"Pogo Backpacker"

A sideloaded Android app (APK) that runs as a background service and automates spinning Pokéstops in Pokémon GO. It uses no root access, no game file modification, and no network interception — it operates entirely by looking at the screen and simulating touch input, exactly as a user would.

**Reference implementation:** [Fate/Grand Automata (FGA)](https://github.com/Fate-Grand-Automata/FGA) is a nearly identical architecture applied to a different mobile game. Its source is MIT-licensed and should be consulted heavily during development. It uses the same three-component stack: MediaProjection + AccessibilityService + OpenCV.

---

## Technology Stack

| Concern | Technology |
|---|---|
| Language | Kotlin |
| Build system | Android Gradle (AGP 8.x) |
| Min SDK | API 26 (Android 8.0) — required for foreground service types |
| Target SDK | API 34+ |
| Screen capture | Android `MediaProjection` API |
| Gesture injection | Android `AccessibilityService` (`dispatchGesture`) |
| Computer vision | OpenCV for Android (`org.opencv:opencv:4.12.0` from Maven Central) |
| Overlay UI | `TYPE_ACCESSIBILITY_OVERLAY` window via the AccessibilityService |
| Background execution | `ForegroundService` with persistent notification |

---

## Required Android Permissions

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

The AccessibilityService declaration in `AndroidManifest.xml` provides gesture and overlay capabilities without additional permissions. MediaProjection requires a runtime user consent prompt each time the service starts (on Android 14+, the token cannot be reused across service restarts).

No `SYSTEM_ALERT_WINDOW` permission is required — the overlay is drawn via `TYPE_ACCESSIBILITY_OVERLAY`, which is granted through the Accessibility Service itself.

---

## Architecture: Three Core Components

### 1. `ScreenshotService` (MediaProjection)

Captures the screen on demand as a `Bitmap`.

- Starts a `VirtualDisplay` backed by an `ImageReader`
- On each capture request, acquires the latest `Image` from the `ImageReader`, converts it to a `Bitmap`, and returns it
- Scales all captures to a normalized internal resolution (720p recommended, consistent with FGA's approach) before passing to the vision layer — this makes all CV thresholds resolution-independent
- Must be started with a user-approved `MediaProjectionManager.createScreenCaptureIntent()` result
- On Android 14+: the permission Intent is single-use; a new consent must be obtained each time the foreground service starts. Store the result Intent in the service before starting MediaProjection.
- The foreground service must declare `foregroundServiceType="mediaProjection"` in the manifest

### 2. `TapperService` (AccessibilityService)

Injects touch gestures into the device.

- Extends `AccessibilityService`
- Declares `android:canPerformGestures="true"` in its XML config
- Exposes two public methods:
  - `tap(x: Float, y: Float)` — builds a `GestureDescription` with a single `StrokeDescription` (point path, ~50ms duration)
  - `swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long)` — builds a stroke along a path
- All coordinates are in the normalized 720p space and must be scaled to actual device pixels before dispatch
- Also provides `performGlobalAction(GLOBAL_ACTION_BACK)` for the back gesture
- Draws the floating toggle overlay as a `WindowManager`-attached view using `TYPE_ACCESSIBILITY_OVERLAY`

**Note:** The user must manually enable this service in Android Settings → Accessibility. It cannot be enabled programmatically. The app's main activity should guide the user there via a deep-link Intent.

### 3. `AutomationEngine` (Coroutine-based state machine)

Orchestrates all logic. Runs on a background coroutine dispatcher inside the foreground service.

---

## Computer Vision Layer

All CV runs on captured `Bitmap`s converted to OpenCV `Mat` objects.

### 3a. Pokéstop Disc Detection (Map Screen)

**Problem:** Pokéstop discs are vertical planes in 3D space, viewed from an elevated camera angle. Their rotation around the vertical (Z) axis determines their apparent width on screen. A disc facing the camera appears as a tall ellipse; a disc seen nearly edge-on appears as a thin vertical sliver. Height on screen stays roughly constant regardless of rotation; width varies dramatically — potentially down to just a few pixels. Color is therefore the most reliable discriminant, and shape filtering must accommodate extreme aspect ratios.

**Approach: HSV color filtering + contour analysis**

1. Convert screenshot `Mat` from RGBA to HSV color space (`Imgproc.cvtColor`)
2. Apply `Core.inRange` with an HSV mask for cyan. Starting bounds:
   - Hue: 85–105 (cyan sits around 90–100 in OpenCV's 0–180 scale)
   - Saturation: 150–255
   - Value: 150–255
   - These bounds must be tuned against real screenshots. Provide annotated screenshots to calibrate.
3. Apply morphological operations (`Imgproc.morphologyEx` with MORPH_CLOSE) to fill small gaps in the mask
4. Find contours (`Imgproc.findContours`)
5. Filter contours by bounding box **height** (the stable dimension):
   - Discard contours whose bounding box height falls outside the expected disc height range (calibrate from screenshots at 720p)
   - Do **not** filter on width, area, or circularity — a nearly edge-on disc has near-zero width and will fail all three
   - Optionally discard contours whose bounding box height is much greater than expected (to reject tall UI chrome)
6. For each surviving contour, compute the centroid — this is the tap target

**Why not template matching:** Template matching requires a fixed reference image. Because disc width varies continuously with rotation, there is no single representative template. Color + height-bounded contour detection handles the full range of orientations without any training data.

**Skipping already-spun (purple) discs:** No separate detection pipeline is needed. The cyan HSV mask simply will not match purple discs. They are ignored automatically.

### 3b. Spin Success Detection (Stop Detail Screen)

After tapping a disc, the stop detail view opens. We immediately attempt to spin —
no range check is performed. If the stop is slightly out of range, the spin will
simply fail, which is handled identically to a network failure.

**Initial state check:** Before swiping, capture and call `SpinnerDetector.detectState()`:
- ABSENT or null → tapped something wrong (map shifted, Pokémon collision, etc.); bail out without a back gesture
- PURPLE → disc already spun; tap back and bail
- CYAN → disc is ready; proceed with swipes

**Spin attempt:** Fire 10 rapid swipes across the circle centre. Multiple swipes cost almost
nothing extra and improve robustness against GPS jitter and network latency.
No inter-swipe checking needed.

**Success detection:** After a short settle delay, call `detectState()` again.
- success = `finalDiscState != null && finalDiscState != CYAN`
- Rationale: the spin animation may briefly make the ring appear ABSENT before settling to
  PURPLE. Anything that is no longer CYAN means we successfully triggered the spin.
- Failure (still CYAN) → log and return false; the outer loop will retry promptly.

---

## Main Automation Loop

```
START (toggle activated)
│
├─ SCAN LOOP
│   ├─ If screen off → sleep 5s → repeat
│   ├─ Capture screenshot
│   ├─ Run Pokéstop disc detection (HSV + contour)
│   ├─ If no cyan discs found → sleep scanInterval → repeat SCAN LOOP
│   └─ If cyan discs found:
│       ├─ Pick one disc at random
│       │
│       ├─ TAP disc centroid
│       ├─ Wait 1s for detail view to open
│       │
│       ├─ checkDiscState() — initial check
│       │   ├─ ABSENT or null → "wrong spot", return false (no back gesture)
│       │   ├─ PURPLE → "already spun", back(), return false
│       │   └─ CYAN → proceed
│       │
│       ├─ Fire 10 sequential swipes (~300ms each, swipe() is suspend)
│       ├─ Wait 500ms for spin animation to settle
│       │
│       ├─ checkDiscState() — final check
│       │   ├─ not CYAN (or null) → success; back(), sessionSpins++
│       │   └─ still CYAN → failure; back()
│       │
│       ├─ If failure OR multiple discs remain → thisLoopDelay = 500ms
│       └─ Else → thisLoopDelay = scanInterval (60s HOUSE / 5s CAR)
│
└─ sleep thisLoopDelay → repeat SCAN LOOP
```

---

## Toggle UI

**Recommendation: Floating overlay button** (same approach as FGA).

A floating play/stop button drawn by the `TapperService` using `WindowManager` + `TYPE_ACCESSIBILITY_OVERLAY`. This is simpler to interact with than a notification action (no drawer swipe required) and stays visually present while the game is in the foreground.

Implementation:
- Inflate a small `FrameLayout` with a circular `FloatingActionButton`
- Attach via `WindowManager.addView` with `LayoutParams` using `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE`
- Position in a corner (e.g., bottom-right), draggable via `onTouchListener` so it doesn't block game UI
- Button state: idle (▶) / running (⏹) / error (⚠)
- The persistent foreground service notification (required by Android for long-running services) serves as a secondary indicator and can include a stop action for accessibility

---

## Timing & Coordination Notes

- All waits between steps should use `delay()` in a coroutine, not `Thread.sleep`
- Screenshot capture should be on-demand (not streaming) to minimize battery impact
- The GPS-wander issue means disc coordinates are only valid for a single interaction cycle — never cache them across scan loops
- Between the tap on a disc and the range check, a brief wait (800–1200ms) is needed for the detail view animation to complete. This duration may need tuning per device.
- After back-navigation, wait for the map to settle before scanning again (600ms baseline, may need tuning)

---

## Project Structure

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/.../pokestop/
│   │   ├── service/
│   │   │   ├── AutomationService.kt         # Foreground service, owns MediaProjection
│   │   │   ├── TapperService.kt             # AccessibilityService, gesture dispatch + overlay
│   │   │   └── ScreenshotService.kt         # MediaProjection screen capture
│   │   ├── vision/
│   │   │   ├── PokestopDetector.kt          # HSV + contour disc detection
│   │   │   └── SpinnerDetector.kt           # HoughCircles, spin success detection
│   │   ├── automation/
│   │   │   └── AutomationEngine.kt          # Main coroutine state machine
│   │   ├── ui/
│   │   │   ├── MainActivity.kt             # Onboarding: guide user through permissions
│   │   │   └── OverlayView.kt              # Floating toggle button
│   │   └── util/
│   │       └── CoordinateTransform.kt       # Normalize 720p ↔ device pixels
│   └── res/
│       └── xml/
│           └── tapper_service.xml          # AccessibilityService config
```

---

## Calibration & Tuning (Pre-requisites Before First Run)

The following values cannot be hardcoded without real screenshots and must be tuned:

| Parameter | What to measure | How |
|---|---|---|
| Cyan HSV range | Color of a ready (blue/cyan) disc | Capture screenshot, sample disc pixels in HSV |
| Min disc bounding box height | Smallest disc visible on screen | Measure bounding box height in pixels at 720p |
| Max disc bounding box height | Largest disc visible | Same — should not vary much from min |
| Spin success color range | Circle color after successful spin | Sample in HSV |
| Step delays | Time for animations to settle | Empirical testing on target device |

**Provide at least 10–15 annotated screenshots** of:
- Cyan (ready) discs at various rotations — especially thin/edge-on ones
- The spinner detail view when in range
- The spinner detail view when out of range
- The spinner after a successful spin

These should be captured at the native device resolution. The CV pipeline will internally normalize to 720p.

---

## Known Risks & Mitigations

| Risk | Mitigation |
|---|---|
| GPS wander or network failure causes spin not to register | Retry loop (max 3 attempts, 2s apart); both failure modes handled identically |
| Android kills background service (battery optimization) | Foreground service + notification; instruct user to exempt app from battery optimization (see dontkillmyapp.com for device-specific steps) |
| MediaProjection consent required on Android 14+ | Re-prompt user at each service start; handle `onActivityResult` in MainActivity and pass token to service |
| Game UI updates change visual appearance | HSV bounds and circle size thresholds stored as configurable constants, easy to retune |
| Niantic bot detection | This automation mimics natural user interaction timing and does not touch network traffic. However, account action risk is non-zero and is the user's responsibility. |

---

## Setup Flow for End User

1. Install the APK (sideloaded; enable "Install from unknown sources" in device settings)
2. Open the app → MainActivity walks through:
   a. Enable Accessibility Service (deep-link to Settings)
   b. Grant notification permission
   c. Grant MediaProjection (prompted when service starts)
   d. Disable battery optimization for the app
3. Open Pokémon GO
4. Tap ▶ on the floating overlay to start automation
5. Tap ⏹ to stop

---

## Implementation Order (Suggested)

1. Project scaffolding: Gradle setup, OpenCV dependency, manifest
2. `TapperService`: AccessibilityService shell, confirm gesture dispatch works (test with a simple tap on a known coordinate)
3. `ScreenshotService`: MediaProjection setup, confirm screenshot capture works (write a bitmap to storage for visual verification)
4. `PokestopDetector`: HSV + contour pipeline; test with static screenshots; tune HSV bounds
5. `SpinnerDetector`: spin-success colour detection; test with static screenshots
6. `AutomationEngine`: Wire up the state machine with real device testing
7. Overlay UI: Floating button, drag-to-reposition
8. MainActivity: Permission onboarding flow
9. End-to-end testing and timing tuning
