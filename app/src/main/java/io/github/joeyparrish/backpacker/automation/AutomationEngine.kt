package io.github.joeyparrish.backpacker.automation

import android.util.Log
import io.github.joeyparrish.backpacker.service.ScreenshotService
import io.github.joeyparrish.backpacker.service.TapperService
import io.github.joeyparrish.backpacker.util.CoordinateTransform
import io.github.joeyparrish.backpacker.vision.PokestopDetector
import io.github.joeyparrish.backpacker.vision.SpinnerDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Coroutine-based state machine that orchestrates the full Pokéstop automation loop.
 * Run this inside a coroutine scope (see AutomationService); cancel the scope to stop.
 */
class AutomationEngine(
    private val screenshotService: ScreenshotService,
    private val tapperService: TapperService
) {
    private val pokestopDetector = PokestopDetector()
    private val spinnerDetector = SpinnerDetector()

    @Volatile private var running = true

    // Timing constants (ms) — tune per device
    private val IDLE_SLEEP_MS = 60_000L
    private val TAP_TO_DETAIL_MS = 1_000L
    private val RANGE_CHECK_MS = 500L
    private val SPIN_RESULT_MS = 1_500L
    private val SPIN_RETRY_MS = 2_000L
    private val BACK_TO_MAP_MS = 700L
    private val MAX_SPIN_ATTEMPTS = 3

    // Swipe parameters (720p normalised pixels) — tune from real screenshots
    private val SPIN_Y_NORM = 480f
    private val SPIN_X_START_NORM = 180f
    private val SPIN_X_END_NORM = 540f
    private val SPIN_DURATION_MS = 300L

    suspend fun run() {
        Log.i(TAG, "AutomationEngine starting")
        while (running && coroutineContext.isActive) {
            try {
                scanLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Error in scan loop: $e")
                delay(5_000)
            }
        }
        Log.i(TAG, "AutomationEngine stopped")
    }

    fun stop() {
        running = false
    }

    private suspend fun scanLoop() {
        Log.d(TAG, "Scan loop iteration")

        val screenshot = screenshotService.capture() ?: run {
            Log.w(TAG, "Screenshot returned null — VirtualDisplay not ready?")
            delay(2_000)
            return
        }

        val screenWidth = screenshotService.screenWidth
        val discs = pokestopDetector.detect(screenshot)
        screenshot.recycle()

        if (discs.isEmpty()) {
            Log.d(TAG, "No Pokéstops detected. Sleeping ${IDLE_SLEEP_MS / 1000}s.")
            delay(IDLE_SLEEP_MS)
            return
        }

        Log.d(TAG, "Found ${discs.size} disc(s). Processing...")

        for (normPt in discs) {
            if (!running || !coroutineContext.isActive) return

            val tapX = CoordinateTransform.toDeviceX(normPt.x, screenWidth)
            val tapY = CoordinateTransform.toDeviceY(normPt.y, screenWidth)
            tapperService.tap(tapX, tapY)
            delay(TAP_TO_DETAIL_MS)

            delay(RANGE_CHECK_MS)
            val detailShot = screenshotService.capture() ?: continue
            val inRange = spinnerDetector.isInRange(detailShot)
            detailShot.recycle()

            if (!inRange) {
                Log.d(TAG, "Stop out of range — backing out")
                tapperService.back()
                delay(BACK_TO_MAP_MS)
                continue
            }

            var spinSuccess = false
            for (attempt in 1..MAX_SPIN_ATTEMPTS) {
                if (!running) break

                Log.d(TAG, "Spin attempt $attempt/$MAX_SPIN_ATTEMPTS")
                val swipeX1 = CoordinateTransform.toDeviceX(SPIN_X_START_NORM, screenWidth)
                val swipeX2 = CoordinateTransform.toDeviceX(SPIN_X_END_NORM, screenWidth)
                val swipeY  = CoordinateTransform.toDeviceY(SPIN_Y_NORM, screenWidth)
                tapperService.swipe(swipeX1, swipeY, swipeX2, swipeY, SPIN_DURATION_MS)

                delay(SPIN_RESULT_MS)

                val resultShot = screenshotService.capture() ?: break
                spinSuccess = spinnerDetector.isSpinSuccess(resultShot)
                resultShot.recycle()

                if (spinSuccess) {
                    Log.i(TAG, "Spin succeeded on attempt $attempt")
                    break
                }

                if (attempt < MAX_SPIN_ATTEMPTS) delay(SPIN_RETRY_MS)
            }

            if (!spinSuccess) Log.w(TAG, "All spin attempts exhausted for this stop")

            tapperService.back()
            delay(BACK_TO_MAP_MS)
        }

        Log.d(TAG, "All discs processed. Re-scanning in ${IDLE_SLEEP_MS / 1000}s.")
        delay(IDLE_SLEEP_MS)
    }

    companion object {
        private const val TAG = "AutomationEngine"
    }
}
