package io.github.joeyparrish.backpacker.automation

import android.content.Context
import android.util.Log
import android.widget.Toast
import io.github.joeyparrish.backpacker.service.ScreenshotService
import io.github.joeyparrish.backpacker.service.TapperService
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

    suspend fun run() {
        Log.i(TAG, "AutomationEngine starting")
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

    // DEBUG: scan loop truncated — capture only, no image processing or gestures.
    private suspend fun scanLoop() {
        Log.d(TAG, "Scan loop: capturing screenshot")

        val screenshot = screenshotService.capture() ?: run {
            Log.w(TAG, "Screenshot returned null — VirtualDisplay not ready?")
            delay(2_000)
            return
        }

        val w = screenshot.width
        val h = screenshot.height
        screenshot.recycle()

        Log.i(TAG, "Screenshot captured: ${w}×${h}")
        Log.d(TAG, "Sleeping ${scanIntervalMs / 1000}s before next capture")
        delay(scanIntervalMs)
    }

    companion object {
        private const val TAG = "Backpacker.AutomationEngine"
    }
}
