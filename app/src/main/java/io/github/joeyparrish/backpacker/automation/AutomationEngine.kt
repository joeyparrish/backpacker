// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

package io.github.joeyparrish.backpacker.automation

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
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
import org.opencv.android.Utils
import java.io.File
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
    private val scanIntervalMs: Long,
    private val session: AutomationService.SessionState
) {
    @Volatile private var running = true

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val pokestopDetector = PokestopDetector()
    private val spinnerDetector = SpinnerDetector()

    // Cancelled before each new toast so rapid scans don't queue up or get rate-limited.
    private var lastToast: Toast? = null

    suspend fun run() {
        Log.i(TAG, "AutomationEngine starting")
        // Brief pause so any UI state changes (FAB icon, overlays) settle before first capture.
        delay(SETTLE_DELAY_MS)

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
                delay(ERROR_RECOVERY_DELAY_MS)
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
            delay(CAPTURE_RETRY_MS)
            return
        }
        val t1 = System.currentTimeMillis()
        Log.d(TAG, "perf: capture=${t1 - t0}ms")

        val w = screenshotService.deviceWidth
        val h = screenshotService.deviceHeight
        val result = pokestopDetector.detect(screenshot)
        // Generate debug bitmap before releasing screenshot (debug mode only).
        val debugBitmap = if (debugScan) pokestopDetector.visualize(screenshot, result) else null
        // Capture failure bitmap before releasing (only when flag on and stops detected).
        val failureBitmap: Bitmap? = if (saveFailureScreenshots && result.passed.isNotEmpty()) {
            Bitmap.createBitmap(screenshot.cols(), screenshot.rows(), Bitmap.Config.ARGB_8888)
                .also { Utils.matToBitmap(screenshot, it) }
        } else null
        screenshot.release()
        val t2 = System.currentTimeMillis()
        Log.d(TAG, "perf: detect=${t2 - t1}ms  stops=${result.passed.size}")

        // Initially whatever the scan mode dictates, but can be overridden by
        // circumstances later.  (Multiple stops, failures, etc)
        var thisLoopDelayMs = scanIntervalMs

        if (debugScan) {
            quickToast("Stops: ${result.passed.size}");
            withContext(Dispatchers.Main) {
                tapperService.showDebugImage(debugBitmap!!)
            }
            // One scan per tap: stop the loop and pause the service, same as spinner debug.
            // Set running=false synchronously so the while loop exits before the next iteration,
            // then send the pause Intent so the service resets its state.
            running = false
            withContext(Dispatchers.Main) { AutomationService.pause(context) }
            return
        } else {
            if (result.passed.isEmpty()) {
                Log.i(TAG, "No Pokéstops detected")
            } else {
                Log.i(TAG, "Detected ${result.passed.size} Pokéstop(s), attempting spins")
                if (result.passed.size > 0) {
                    // Pick one disc at random.
                    val disc = result.passed.random()
                    val ts = System.currentTimeMillis()
                    val success = spinDisc(disc, w, h, failureBitmap)
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
    private suspend fun spinDisc(disc: PokestopDetector.Disc, deviceWidth: Int, deviceHeight: Int, failureBitmap: Bitmap? = null): Boolean {
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
            quickToast("Wrong spot tapped")
            if (failureBitmap != null) {
                saveFailureScreenshot(failureBitmap)  // recycles bitmap
            }

            // TODO: How we should back out from this state depends on other
            // elements on screen.  If we tapped a stop (identify by X in
            // bottom center), we should tap the X (though back gesture is also
            // OK).  If we are still in the map (identify by pokeball in bottom
            // center), we shouldn't do anything.  If we are in some other
            // state, like we tapped a Pokemon, we should gesture "back".
            // For now, and because these ideal checks would be complex, do
            // nothing.
            return false
        } else if (initialDiscState == SpinnerDetector.SpinResult.PURPLE) {
            Log.w(TAG, "Disc not ready - scan again")
            quickToast("Disc not ready")
            failureBitmap?.recycle()
            tapperService.back()
            return false
        }

        // CYAN — spinner is ready; bitmap no longer needed.
        failureBitmap?.recycle()

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
            session.spins++
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val lifetime = prefs.getInt(PREF_LIFETIME_SPINS, 0) + 1
                prefs.edit().putInt(PREF_LIFETIME_SPINS, lifetime).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist lifetime spins: $e")
            }
        }

        val elapsedHours = (System.currentTimeMillis() - session.startMs) / 3_600_000.0
        val spinsPerHour = session.spins / elapsedHours

        Log.i(TAG, "Spin $succeededOrFailed (final state: $finalDiscState, session total: ${session.spins}, %.1f/hr)".format(spinsPerHour))
        quickToast("Spin $succeededOrFailed. ${session.spins} spins (%.1f/hr)".format(spinsPerHour))

        tapperService.back()
        return success
    }

    /** Save [bitmap] to Pictures/Backpacker (MediaStore on API 29+, external files dir below). Always recycles [bitmap]. */
    private fun saveFailureScreenshot(bitmap: Bitmap) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(bitmap)
            } else {
                saveViaFileSystem(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save failure screenshot: $e")
        } finally {
            bitmap.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(bitmap: Bitmap) {
        pruneMediaStoreFailures()
        val filename = "failure_${System.currentTimeMillis()}.png"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Backpacker")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv
        ) ?: run {
            Log.e(TAG, "MediaStore.insert returned null for $filename")
            return
        }
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, cv, null, null)
            Log.i(TAG, "Saved failure screenshot: $filename")
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun pruneMediaStoreFailures() {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("Pictures/Backpacker%", "failure_%.png")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"
        val uris = mutableListOf<Uri>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                uris.add(ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol)
                ))
            }
        }
        val toDelete = uris.size - (MAX_FAILURE_SCREENSHOTS - 1)
        if (toDelete > 0) {
            uris.take(toDelete).forEach { context.contentResolver.delete(it, null, null) }
            Log.d(TAG, "Pruned $toDelete old failure screenshots")
        }
    }

    private fun saveViaFileSystem(bitmap: Bitmap) {
        val dir = File(context.getExternalFilesDir(null), "Backpacker").apply { mkdirs() }
        val existing = dir.listFiles { f -> f.name.startsWith("failure_") && f.name.endsWith(".png") }
            ?.sortedBy { it.name } ?: emptyList()
        val toDelete = existing.size - (MAX_FAILURE_SCREENSHOTS - 1)
        if (toDelete > 0) {
            existing.take(toDelete).forEach { it.delete() }
            Log.d(TAG, "Pruned $toDelete old failure screenshots from filesystem")
        }
        val filename = "failure_${System.currentTimeMillis()}.png"
        val outFile = File(dir, filename)
        outFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        Log.i(TAG, "Saved failure screenshot: ${outFile.absolutePath}")
    }

    private suspend fun quickToast(message: String) {
        withContext(Dispatchers.Main) {
            lastToast?.cancel()
            lastToast = Toast.makeText(
                context,
                message,
                Toast.LENGTH_SHORT
            )
            lastToast?.show()
        }
    }

    /**
     * One-shot spinner debug check. Captures a single screenshot, runs
     * [SpinnerDetector.detectState], and shows the visualization overlay and a
     * toast reporting the detected state.
     * Caller sends AutomationService.pause() afterward to reset FAB and service state.
     */
    private suspend fun runSpinnerDebugCheck() {
        Log.d(TAG, "Spinner debug: capturing screenshot")

        val shot = screenshotService.capture()
        if (shot == null) {
            Log.w(TAG, "Spinner debug: screenshot failed")
            quickToast("Screenshot failed")
            return
        }

        val state = spinnerDetector.detectState(shot)
        val bitmap: Bitmap = spinnerDetector.visualize(shot)
        shot.release()

        val message = when (state) {
            SpinnerDetector.SpinResult.CYAN   -> "Spinner: cyan (ready)"
            SpinnerDetector.SpinResult.PURPLE -> "Spinner: purple (spun)"
            SpinnerDetector.SpinResult.ABSENT -> "Spinner: absent"
        }
        Log.i(TAG, message)
        quickToast(message)
        withContext(Dispatchers.Main) {
            tapperService.showDebugImage(bitmap)
        }
    }

    companion object {
        private const val TAG = "Backpacker.AutomationEngine"

        /** When true, each scan runs PokestopDetector and shows debug overlays. */
        @Volatile var debugScan = false

        /** When true, the next FAB activation takes one spinner screenshot and reports its state. */
        @Volatile var debugSpinner = false

        /** When true, screenshots that led to ABSENT disc state are saved to Pictures/Backpacker. */
        @Volatile var saveFailureScreenshots = false

        // Poll interval when the screen is off — short enough to resume promptly,
        // long enough not to spin the CPU while the display is dark.
        private const val SCREEN_OFF_POLL_MS      = 5_000L

        // Timing constants
        private const val SETTLE_DELAY_MS         =   500L  // FAB/overlay settle after activation
        private const val CAPTURE_RETRY_MS        = 2_000L  // VirtualDisplay not ready yet
        private const val ERROR_RECOVERY_DELAY_MS = 5_000L  // pause after unexpected scan error
        private const val OPEN_DELAY_MS           = 1_000L  // wait for detail view animation
        private const val SWIPE_DURATION_MS       =   300L  // swipe gesture length
        private const val NUM_SPIN_ATTEMPTS       =    10L  // spin this many times
        private const val SPIN_RESULT_DELAY_MS    =   500L  // delay before checking spin result
        private const val SCAN_IMMEDIATELY_MS     = 1_500L  // scan "right away", but with time for the "back to map" animation to settle

        private const val MAX_FAILURE_SCREENSHOTS = 30

        const val PREFS_NAME         = "backpacker_prefs"
        const val PREF_LIFETIME_SPINS = "lifetime_spins"
    }
}
