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
            } catch (e: Throwable) {
                // Catch Throwable (not just Exception) so that Errors such as
                // OutOfMemoryError don't escape the coroutine and crash the process.
                Log.e(TAG, "Error in scan loop: $e")
                delay(5_000)
            }
        }
        Log.i(TAG, "AutomationEngine stopped")
    }

    fun stop() {
        running = false
    }

    // DEBUG: scan loop truncated — capture only, no image processing or gestures.
    private suspend fun scanLoop() {
        Log.d(TAG, "Scan loop: capturing screenshot")

        val screenshot = screenshotService.capture() ?: run {
            Log.w(TAG, "Screenshot returned null — VirtualDisplay not ready?")
            delay(2_000)
            return
        }

        Log.i(TAG, "Screenshot captured: ${screenshot.width}×${screenshot.height}")
        screenshot.recycle()

        Log.d(TAG, "Sleeping ${IDLE_SLEEP_MS / 1000}s before next capture")
        delay(IDLE_SLEEP_MS)
    }

    companion object {
        private const val TAG = "AutomationEngine"
    }
}
