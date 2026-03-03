package io.github.joeyparrish.backpacker.automation

import android.content.Context
import android.graphics.RectF
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import io.github.joeyparrish.backpacker.service.AutomationService
import io.github.joeyparrish.backpacker.service.ScreenshotService
import io.github.joeyparrish.backpacker.service.TapperService
import io.github.joeyparrish.backpacker.util.CoordinateTransform
import io.github.joeyparrish.backpacker.vision.PokestopDetector
import io.github.joeyparrish.backpacker.vision.SpinnerDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Coroutine-based state machine that orchestrates the full Pokéstop automation loop.
 * Run this inside a coroutine scope (see AutomationService); cancel the scope to stop.
 *
 * [context] is used for debug toasts only; pass the service's application context.
 */
class AutomationEngine(
    private val screenshotService: ScreenshotService,
    private val tapperService: TapperService,
    private val context: Context,
    private val scanIntervalMs: Long = 60_000L
) {
    @Volatile private var running = true

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val pokestopDetector = PokestopDetector()
    private val spinnerDetector = SpinnerDetector()

    // Cancelled before each new toast so rapid scans don't queue up or get rate-limited.
    private var lastToast: Toast? = null

    private var sessionSpins = 0
    private val sessionStartMs = System.currentTimeMillis()

    suspend fun run() {
        Log.i(TAG, "AutomationEngine starting")
        // Brief pause so any UI state changes (FAB icon, overlays) settle before first capture.
        delay(500)

        if (spinnerDebug) {
            runSpinnerDebugCheck()
            // Pause via the service so the FAB resets to IDLE and the notification updates.
            // The Intent is processed after this coroutine returns, so there is no cancel race.
            withContext(Dispatchers.Main) { AutomationService.pause(context) }
            return
        }

        while (running && coroutineContext.isActive) {
            try {
                scanLoop()
            } catch (e: Throwable) {
                Log.e(TAG, "Error in scan loop: $e")
                delay(5_000)
            }
        }
        Log.i(TAG, "AutomationEngine stopped")
    }

    fun stop() {
        running = false
    }

    /** Release pre-allocated OpenCV Mats held by the detectors. Call after [stop]. */
    fun release() {
        pokestopDetector.release()
        spinnerDetector.release()
    }

    private suspend fun scanLoop() {
        // Skip the expensive capture + CV work when the display is off.
        // The loop will naturally poll again after SCREEN_OFF_POLL_MS.
        if (!powerManager.isInteractive) {
            Log.d(TAG, "Screen is off — skipping scan")
            delay(SCREEN_OFF_POLL_MS)
            return
        }

        Log.d(TAG, "Scan loop: capturing screenshot")
        val t0 = System.currentTimeMillis()

        val screenshot = screenshotService.capture() ?: run {
            Log.w(TAG, "Screenshot returned null — VirtualDisplay not ready?")
            delay(2_000)
            return
        }
        val t1 = System.currentTimeMillis()
        Log.d(TAG, "perf: capture=${t1 - t0}ms")

        val w = screenshotService.deviceWidth
        val h = screenshotService.deviceHeight
        val result = pokestopDetector.detect(screenshot)
        screenshot.release()
        val t2 = System.currentTimeMillis()
        Log.d(TAG, "perf: detect=${t2 - t1}ms  stops=${result.passed.size}")

        if (debugScan) {
            fun RectF.toDevice() = RectF(
                CoordinateTransform.toDeviceX(left, w),
                CoordinateTransform.toDeviceY(top, w),
                CoordinateTransform.toDeviceX(right, w),
                CoordinateTransform.toDeviceY(bottom, w)
            )

            // Passed contours get a yellow box; rejected contours get a red box.
            val devicePassedBounds = result.passed.map { it.bounds.toDevice() }
            val deviceRejectedBounds = result.rejectedBounds.map { it.toDevice() }

            withContext(Dispatchers.Main) {
                lastToast?.cancel()
                lastToast = Toast.makeText(context, "Stops: ${result.passed.size}", Toast.LENGTH_SHORT)
                lastToast?.show()
                tapperService.showDebugMarkers(devicePassedBounds, deviceRejectedBounds)
            }
        } else {
            if (result.passed.isEmpty()) {
                Log.i(TAG, "No Pokéstops detected")
            } else {
                Log.i(TAG, "Detected ${result.passed.size} Pokéstop(s), attempting spins")
                for (disc in result.passed) {
                    if (!running || !coroutineContext.isActive) break
                    val ts = System.currentTimeMillis()
                    spinDisc(disc, w, h)
                    Log.d(TAG, "perf: spinDisc=${System.currentTimeMillis() - ts}ms")
                    tapperService.back()
                    delay(BACK_DELAY_MS)
                }
            }
        }

        val tDone = System.currentTimeMillis()
        Log.d(TAG, "perf: scan active=${tDone - t0}ms  interval=${scanIntervalMs}ms")
        Log.d(TAG, "Sleeping ${scanIntervalMs / 1000}s before next scan")
        delay(scanIntervalMs)
    }

    /**
     * Tap a detected disc, wait for the detail view to open, then attempt to spin
     * up to [MAX_SPIN_ATTEMPTS] times. Both range failures and network failures are
     * handled identically — if the spin doesn't register we just retry.
     *
     * Caller is responsible for navigating back to the map afterward.
     */
    private suspend fun spinDisc(disc: PokestopDetector.Disc, deviceWidth: Int, deviceHeight: Int) {
        val tapX = CoordinateTransform.toDeviceX(disc.centroid.x, deviceWidth)
        val tapY = CoordinateTransform.toDeviceY(disc.centroid.y, deviceWidth)
        Log.d(TAG, "Tapping disc at device (%.1f, %.1f)".format(tapX, tapY))
        tapperService.tap(tapX, tapY)
        delay(OPEN_DELAY_MS)

        // Swipe horizontally across the centre of the screen to spin the circle.
        val swipeY  = deviceHeight * 0.5f
        val swipeX1 = deviceWidth  * 0.25f
        val swipeX2 = deviceWidth  * 0.75f

        for (attempt in 1..MAX_SPIN_ATTEMPTS) {
            Log.d(TAG, "Spin attempt $attempt/$MAX_SPIN_ATTEMPTS")
            tapperService.swipe(swipeX1, swipeY, swipeX2, swipeY, SWIPE_DURATION_MS)
            delay(SPIN_RESULT_DELAY_MS)

            val check = screenshotService.capture()
            if (check == null) {
                Log.w(TAG, "Screenshot null during spin check — aborting disc")
                return
            }
            val success = spinnerDetector.isSpinSuccess(check)
            check.release()

            if (success) {
                sessionSpins++
                val elapsedHours = (System.currentTimeMillis() - sessionStartMs) / 3_600_000.0
                val spinsPerHour = sessionSpins / elapsedHours
                Log.i(TAG, "Spin succeeded on attempt $attempt (session total: $sessionSpins, %.1f/hr)".format(spinsPerHour))
                withContext(Dispatchers.Main) {
                    lastToast?.cancel()
                    lastToast = Toast.makeText(
                        context,
                        "Spins: $sessionSpins (%.1f/hr)".format(spinsPerHour),
                        Toast.LENGTH_SHORT
                    )
                    lastToast?.show()
                }
                return
            }
            Log.d(TAG, "Spin attempt $attempt failed (range or network)")
            if (attempt < MAX_SPIN_ATTEMPTS) delay(RETRY_DELAY_MS)
        }

        Log.w(TAG, "All $MAX_SPIN_ATTEMPTS spin attempts failed — moving on")
    }

    /**
     * One-shot spinner debug check. Captures a screenshot, runs [SpinnerDetector.detectState],
     * and shows a toast reporting whether the circle is cyan, purple, or absent.
     * If cyan, performs a swipe and then re-checks the result.
     * Caller sends AutomationService.pause() afterward to reset FAB and service state.
     */
    private suspend fun runSpinnerDebugCheck() {
        Log.d(TAG, "Spinner debug: capturing screenshot")
        val t0 = System.currentTimeMillis()
        val screenshot = screenshotService.capture()
        if (screenshot == null) {
            Log.w(TAG, "Screenshot null during spinner debug")
            withContext(Dispatchers.Main) {
                lastToast?.cancel()
                lastToast = Toast.makeText(context, "Spinner: no screenshot", Toast.LENGTH_LONG)
                lastToast?.show()
            }
            return
        }
        Log.d(TAG, "perf: capture=${System.currentTimeMillis() - t0}ms")

        val deviceWidth  = screenshotService.deviceWidth
        val deviceHeight = screenshotService.deviceHeight
        val t1 = System.currentTimeMillis()
        val state = spinnerDetector.detectState(screenshot)
        screenshot.release()
        Log.d(TAG, "perf: detectState=${System.currentTimeMillis() - t1}ms  result=$state")

        if (state == SpinnerDetector.SpinResult.CYAN) {
            Log.i(TAG, "Spinner: cyan — swiping")
            withContext(Dispatchers.Main) {
                lastToast?.cancel()
                lastToast = Toast.makeText(context, "Spinner: cyan — swiping", Toast.LENGTH_SHORT)
                lastToast?.show()
            }

            val swipeY  = deviceHeight * 0.5f
            val swipeX1 = deviceWidth  * 0.25f
            val swipeX2 = deviceWidth  * 0.75f
            tapperService.swipe(swipeX1, swipeY, swipeX2, swipeY, SWIPE_DURATION_MS)
            delay(SPIN_RESULT_DELAY_MS)

            val check = screenshotService.capture()
            val afterState = if (check != null) {
                spinnerDetector.detectState(check).also { check.release() }
            } else {
                null
            }

            val message = when (afterState) {
                SpinnerDetector.SpinResult.PURPLE -> "Spinner: purple (success!)"
                SpinnerDetector.SpinResult.CYAN   -> "Spinner: still cyan (failed)"
                SpinnerDetector.SpinResult.ABSENT -> "Spinner: absent after swipe"
                null                              -> "Spinner: no screenshot after swipe"
            }
            Log.i(TAG, message)
            withContext(Dispatchers.Main) {
                lastToast?.cancel()
                lastToast = Toast.makeText(context, message, Toast.LENGTH_LONG)
                lastToast?.show()
            }
            return
        }

        val message = when (state) {
            SpinnerDetector.SpinResult.PURPLE -> "Spinner: purple"
            SpinnerDetector.SpinResult.CYAN   -> "Spinner: cyan"  // unreachable
            SpinnerDetector.SpinResult.ABSENT -> "Spinner: absent"
        }
        Log.i(TAG, message)

        withContext(Dispatchers.Main) {
            lastToast?.cancel()
            lastToast = Toast.makeText(context, message, Toast.LENGTH_LONG)
            lastToast?.show()
        }
    }

    companion object {
        private const val TAG = "Backpacker.AutomationEngine"

        /** When true, each scan runs PokestopDetector and shows debug overlays. */
        @Volatile var debugScan = false

        /** When true, the next FAB activation takes one spinner screenshot and reports its state. */
        @Volatile var spinnerDebug = false

        // Poll interval when the screen is off — short enough to resume promptly,
        // long enough not to spin the CPU while the display is dark.
        private const val SCREEN_OFF_POLL_MS  = 5_000L

        // Timing constants — all may need tuning per device.
        private const val OPEN_DELAY_MS       = 1_000L  // wait for detail view animation
        private const val SWIPE_DURATION_MS   =   300L  // swipe gesture length
        private const val SPIN_RESULT_DELAY_MS = 1_500L  // wait for network/animation
        private const val RETRY_DELAY_MS      = 2_000L  // pause between retry attempts
        private const val BACK_DELAY_MS       =   600L  // wait for map to settle after back
        private const val MAX_SPIN_ATTEMPTS   =     3
    }
}
