# Backpacker

An Android app that automates Pokéstop spinning in Pokémon GO. It uses Android's
MediaProjection API to watch the screen and an AccessibilityService to inject
swipe gestures — no root, no network interception, no game file modification.

> **Note:** Automating game actions may violate Niantic's Terms of Service. Use
> at your own risk.

---

## Installation

### From a GitHub Release (recommended)

1. On your Android device, open **Settings → Security** (or **Apps → Special app
   access → Install unknown apps**) and allow installs from your browser or file
   manager.
2. Download the latest `app-debug.apk` from the
   [Releases](https://github.com/joeyparrish/backpacker/releases/latest) page.
3. Open the downloaded file and tap **Install**.

### From Source

```bash
git clone https://github.com/joeyparrish/backpacker.git
cd backpacker
./gradlew assembleDebug
# APK is at app/build/outputs/apk/debug/app-debug.apk
```

Install via ADB:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Setup

Open **Backpacker** and work through the three setup steps shown on screen:

1. **Enable Gesture Service** — tap the button to open Accessibility Settings,
   find *Backpacker Gesture Service*, and enable it.
2. **Allow Notifications** — required to keep the background service alive.
3. **Disable Battery Optimization** — prevents Android from killing the service
   while the screen is off.

Once all steps are done, toggle **Overlay enabled**. A system dialog will ask
for screen-capture permission — tap **Start now**. A small floating button will
appear on screen.

---

## Usage

Open Pokémon GO and use the floating button to control automation:

| Button state state | Meaning |
|---|---|
| Cyan pokestop icon (dim) | **Idle** — not running |
| Red house icon | **Stationary mode** — scans once per minute |
| Red car icon | **Moving mode** — scans once per 2 seconds |

Tap the button to cycle through states. Drag it to reposition.

When a Pokéstop disc is detected, the app taps it, swipes the spinner, and taps
back to the map automatically. Session spin count and a lifetime total are
tracked and shown in the main app.

### Debug modes (in the Backpacker app)

- **Debug scan** — on each scan, highlights detected disc positions with
  bounding boxes on a transparent overlay. Useful for tuning detection
  thresholds in source.
- **Debug spinner** — on the next tap, captures one screenshot and reports
  whether the spinner is cyan, purple, or absent. If cyan, performs one swipe
  and reports the result.

Both debug modes disable "moving" mode (the button only offers "idle" and
"stationary").  Debug flags are cleared when the overlay is turned off.

---

## Requirements

- Android 8.0+ (API 26)
- Pokémon GO installed

---

## License

[MIT](LICENSE.md) — Copyright 2026 Joey Parrish
