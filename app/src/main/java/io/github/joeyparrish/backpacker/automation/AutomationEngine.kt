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
    private val scanIntervalMs: Long
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

        if (debugSpinner) {
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

        // Initially whatever the scan mode dictates, but can be overridden by
        // circumstances later.  (Multiple stops, failures, etc)
        var thisLoopDelayMs = scanIntervalMs

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
                if (result.passed.size > 0) {
                    // Pick one disc at random.
                    val disc = result.passed.random()
                    val ts = System.currentTimeMillis()
                    val success = spinDisc(disc, w, h)
                    Log.d(TAG, "perf: spinDisc=${System.currentTimeMillis() - ts}ms")

                    // If we fail, or if there are multiple discs, scan again
                    // immediately.
                    if (!success || result.passed.size > 1) {
                        thisLoopDelayMs = SCAN_IMMEDIATELY_MS
                    }
                }
            }
        }

        val tDone = System.currentTimeMillis()
        Log.d(TAG, "perf: scan active=${tDone - t0}ms  interval=${scanIntervalMs}ms")
        Log.d(TAG, "Sleeping ${thisLoopDelayMs / 1000}s before next scan")
        delay(thisLoopDelayMs)
    }

    private suspend fun checkDiscState(): SpinnerDetector.SpinResult? {
        val check = screenshotService.capture()
        val state = if (check != null) {
            spinnerDetector.detectState(check).also { check.release() }
        } else {
            null
        }
        return state
    }

    /**
     * Tap a detected disc, then attempt to spin.  Attempts to return to the
     * map in all cases.
     *
     * Returns true on success.
     */
    private suspend fun spinDisc(disc: PokestopDetector.Disc, deviceWidth: Int, deviceHeight: Int): Boolean {
        val tapX = CoordinateTransform.toDeviceX(disc.centroid.x, deviceWidth)
        val tapY = CoordinateTransform.toDeviceY(disc.centroid.y, deviceWidth)
        Log.d(TAG, "Tapping disc at device (%.1f, %.1f)".format(tapX, tapY))
        tapperService.tap(tapX, tapY)
        delay(OPEN_DELAY_MS)

        // Make sure the map didn't shift under us and that we didn't collide
        // with a Pokemon or notification of some kind.
        val initialDiscState = checkDiscState()
        if (initialDiscState == SpinnerDetector.SpinResult.ABSENT ||
            initialDiscState == null) {
            Log.w(TAG, "Wrong spot tapped - scan again")

            // FIXME: How we back out from this state depends on other elements
            // on screen.  If we tapped a stop (X in bottom center), we need to
            // tap the X.  If we are still in the map (pokeball in bottom
            // center), we need to do nothing.  If we are in some other state,
            // like we tapped a Pokemon, we need to gesture "back".

            return false
        } else if (initialDiscState == SpinnerDetector.SpinResult.PURPLE) {
            Log.w(TAG, "Disc not ready - scan again")

            // FIXME: Tap the "X" instead
            tapperService.back()

            return false
        }

        // Swipe horizontally across the centre of the screen to spin the
        // circle.  Do it several time rapidly.  This fires off several network
        // requests and deals with little GPS issues while driving, at very
        // little cost.  No need to verify each spin and retry.  And extra
        // spins after success will just settle the animation more quickly so
        // the detector can run better afterwards.
        val swipeY  = deviceHeight * 0.5f
        val swipeX1 = deviceWidth  * 0.25f
        val swipeX2 = deviceWidth  * 0.75f
        for (attempt in 1..NUM_SPIN_ATTEMPTS) {
            tapperService.swipe(swipeX1, swipeY, swipeX2, swipeY, SWIPE_DURATION_MS)
        }
        delay(SPIN_RESULT_DELAY_MS)

        // We know coming into the spin loop above that we were once looking at
        // cyan.  A failure to detect cyan might be because the spin animation
        // is still going, in which case we may also fail to detect purple.  So
        // success isn't purple, it's anything that isn't cyan.
        val finalDiscState = checkDiscState()
        val success = finalDiscState != null && finalDiscState != SpinnerDetector.SpinResult.CYAN

        val succeededOrFailed = if (success) "succeeded" else "failed"
        if (success) {
            sessionSpins++
        }

        val elapsedHours = (System.currentTimeMillis() - sessionStartMs) / 3_600_000.0
        val spinsPerHour = sessionSpins / elapsedHours

        Log.i(TAG, "Spin $succeededOrFailed (final state: $finalDiscState, session total: $sessionSpins, %.1f/hr)".format(spinsPerHour))
        withContext(Dispatchers.Main) {
            lastToast?.cancel()
            lastToast = Toast.makeText(
                context,
                "Spin $succeededOrFailed. $sessionSpins spins (%.1f/hr)".format(spinsPerHour),
                Toast.LENGTH_SHORT
            )
            lastToast?.show()
        }

        // FIXME: Tap the "X" instead
        tapperService.back()

        return success
    }

    /**
     * One-shot spinner debug check. Captures a screenshot, runs [SpinnerDetector.detectState],
     * and shows a toast reporting whether the circle is cyan, purple, or absent.
     * If cyan, performs a swipe and then re-checks the result.
     * Caller sends AutomationService.pause() afterward to reset FAB and service state.
     */
    private suspend fun runSpinnerDebugCheck() {
        Log.d(TAG, "Spinner debug: capturing screenshot")
        val beforeState = checkDiscState()

        if (beforeState == SpinnerDetector.SpinResult.CYAN) {
            Log.i(TAG, "Spinner: cyan — swiping")
            withContext(Dispatchers.Main) {
                lastToast?.cancel()
                lastToast = Toast.makeText(context, "Spinner: cyan — swiping", Toast.LENGTH_SHORT)
                lastToast?.show()
            }

            val swipeY  = screenshotService.deviceHeight * 0.5f
            val swipeX1 = screenshotService.deviceWidth  * 0.25f
            val swipeX2 = screenshotService.deviceWidth  * 0.75f
            tapperService.swipe(swipeX1, swipeY, swipeX2, swipeY, SWIPE_DURATION_MS)
            delay(SPIN_RESULT_DELAY_MS)

            val afterState = checkDiscState()
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

        val message = when (beforeState) {
            SpinnerDetector.SpinResult.PURPLE -> "Spinner: purple"
            SpinnerDetector.SpinResult.CYAN   -> "Spinner: cyan"  // unreachable
            SpinnerDetector.SpinResult.ABSENT -> "Spinner: absent"
            null                              -> "Screenshot failed"
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
        @Volatile var debugSpinner = false

        // Poll interval when the screen is off — short enough to resume promptly,
        // long enough not to spin the CPU while the display is dark.
        private const val SCREEN_OFF_POLL_MS   = 5_000L

        // Timing constants
        private const val OPEN_DELAY_MS        = 1_000L  // wait for detail view animation
        private const val SWIPE_DURATION_MS    =   300L  // swipe gesture length
        private const val NUM_SPIN_ATTEMPTS    =    10L  // spin this many times
        private const val SPIN_RESULT_DELAY_MS =   500L  // delay before checking spin result
        private const val SCAN_IMMEDIATELY_MS  =   500L  // scan right away
    }
}
