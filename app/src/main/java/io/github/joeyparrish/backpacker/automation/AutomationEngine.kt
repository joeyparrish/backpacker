package io.github.joeyparrish.backpacker.automation

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import android.widget.Toast
import io.github.joeyparrish.backpacker.service.ScreenshotService
import io.github.joeyparrish.backpacker.service.TapperService
import io.github.joeyparrish.backpacker.util.CoordinateTransform
import io.github.joeyparrish.backpacker.vision.PokestopDetector
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

    private val pokestopDetector = PokestopDetector()

    suspend fun run() {
        Log.i(TAG, "AutomationEngine starting")
        // Brief pause so any UI state changes (FAB icon, overlays) settle before first capture.
        delay(500)
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

    private suspend fun scanLoop() {
        Log.d(TAG, "Scan loop: capturing screenshot")

        val screenshot = screenshotService.capture() ?: run {
            Log.w(TAG, "Screenshot returned null — VirtualDisplay not ready?")
            delay(2_000)
            return
        }

        val w = screenshot.width
        val h = screenshot.height

        if (debugScan) {
            val discs = pokestopDetector.detect(screenshot)
            screenshot.recycle()

            val deviceCentroids = discs.map { d ->
                PointF(
                    CoordinateTransform.toDeviceX(d.centroid.x, w),
                    CoordinateTransform.toDeviceY(d.centroid.y, w)
                )
            }
            val deviceBounds = discs.map { d ->
                RectF(
                    CoordinateTransform.toDeviceX(d.bounds.left, w),
                    CoordinateTransform.toDeviceY(d.bounds.top, w),
                    CoordinateTransform.toDeviceX(d.bounds.right, w),
                    CoordinateTransform.toDeviceY(d.bounds.bottom, w)
                )
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Stops: ${discs.size}", Toast.LENGTH_SHORT).show()
                tapperService.showDebugMarkers(deviceCentroids, deviceBounds)
            }
        } else {
            screenshot.recycle()
        }

        Log.i(TAG, "Screenshot captured: ${w}×${h}")
        Log.d(TAG, "Sleeping ${scanIntervalMs / 1000}s before next capture")
        delay(scanIntervalMs)
    }

    companion object {
        private const val TAG = "Backpacker.AutomationEngine"

        /** When true, each scan runs PokestopDetector and shows debug overlays. */
        @Volatile var debugScan = false
    }
}
