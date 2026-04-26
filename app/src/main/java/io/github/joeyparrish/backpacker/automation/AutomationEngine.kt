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
import androidx.annotation.RequiresApi
import io.github.joeyparrish.backpacker.service.AutomationService
import io.github.joeyparrish.backpacker.service.ScreenshotService
import io.github.joeyparrish.backpacker.service.TapperService
import io.github.joeyparrish.backpacker.util.CoordinateTransform
import io.github.joeyparrish.backpacker.vision.ExitButtonDetector
import io.github.joeyparrish.backpacker.vision.PassengerDetector
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
 * [context] is used for SharedPreferences access; pass the service's application context.
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
    private val exitButtonDetector = ExitButtonDetector()
    private val passengerDetector = PassengerDetector()
    private val pokestopDetector = PokestopDetector()
    private val spinnerDetector = SpinnerDetector()

    suspend fun run() {
        Log.i(TAG, "AutomationEngine starting")
        val tRunStart = System.currentTimeMillis()

        // Initialise the HUD stats line so the user sees "0 spins (0.0/hr)" immediately
        // rather than a blank second line.
        updateHud("")

        if (debugSpinner) {
            runSpinnerDebugCheck()
            Log.d(TAG, "perf: debug total=${System.currentTimeMillis() - tRunStart}ms")
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
        exitButtonDetector.release()
        passengerDetector.release()
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

        // Check for an exit button (gym, menu, egg viewer, Pokémon viewer, etc.) before
        // anything else.  Tapping it is always the right recovery — and it overrides the
        // passenger detector because the buddy screen shows a "play" button that the
        // passenger detector would otherwise match first.
        val exitButton = exitButtonDetector.detect(screenshot)
        if (exitButton != null) {
            screenshot.release()
            val tapX = CoordinateTransform.toDeviceX(exitButton.x, w)
            val tapY = CoordinateTransform.toDeviceY(exitButton.y, w)
            Log.i(TAG, "Exit button detected — tapping at ($tapX, $tapY)")
            tapperService.tap(tapX, tapY)
            updateHud("Exiting to map")
            delay(DISMISS_DELAY_MS)
            return
        }

        // Check for the speed-warning dialog before scanning for Pokéstops.
        // If the "I'm a Passenger" button (or any matching green pill) is visible, tap it
        // and rescan immediately rather than trying to spin through an obscured map.
        val passengerButton = passengerDetector.detect(screenshot)
        if (debugPassenger) {
            val debugBitmap = passengerDetector.visualize(screenshot, passengerButton)
            screenshot.release()
            val status = if (passengerButton != null) "Passenger button found" else "No passenger button"
            Log.i(TAG, status)
            updateHud(status)
            withContext(Dispatchers.Main) { tapperService.showDebugImage(debugBitmap) }
            running = false
            withContext(Dispatchers.Main) { AutomationService.pause(context) }
            return
        } else if (passengerButton != null) {
            screenshot.release()
            val tapX = CoordinateTransform.toDeviceX(passengerButton.x, w)
            val tapY = CoordinateTransform.toDeviceY(passengerButton.y, w)
            Log.i(TAG, "Speed warning detected — tapping dismiss button at ($tapX, $tapY)")
            tapperService.tap(tapX, tapY)
            updateHud("Speed warning dismissed")
            delay(DISMISS_DELAY_MS)
            return  // rescan immediately on the next iteration
        }

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
            updateHud("Pokéstops: ${result.passed.size}")
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
                updateHud("Pokéstops: ${result.passed.size}")
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

        val tDone = System.currentTimeMillis()
        Log.d(TAG, "perf: scan active=${tDone - t0}ms  interval=${scanIntervalMs}ms")
        Log.d(TAG, "Sleeping ${thisLoopDelayMs / 1000}s before next scan")
        delay(thisLoopDelayMs)
    }

    private suspend fun checkDiscState(): SpinnerDetector.SpinResult? {
        val tCapture = System.currentTimeMillis()
        val check = screenshotService.capture()
        val tDetect = System.currentTimeMillis()
        val state = if (check != null) {
            spinnerDetector.detectState(check).also { check.release() }
        } else {
            null
        }
        val tDone = System.currentTimeMillis()
        Log.d(TAG, "perf: spinner capture=${tDetect - tCapture}ms  detect=${tDone - tDetect}ms  state=$state")
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
            updateHud("Wrong spot tapped")
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
            updateHud("Disc not ready")
            failureBitmap?.recycle()
            tapperService.back()
            return false
        }

        // CYAN — spinner is ready; bitmap no longer needed.
        failureBitmap?.recycle()

        // Swipe horizontally across the centre of the screen to spin the
        // circle.  After each swipe, check the colour — break early if we
        // already see a non-cyan result so we stop swiping once the server
        // responds.  The checkDiscState() call also provides per-call timing
        // data (capture + detectState) in logcat.
        val swipeY  = deviceHeight * 0.5f
        val swipeX1 = deviceWidth  * 0.25f
        val swipeX2 = deviceWidth  * 0.75f
        var finalDiscState: SpinnerDetector.SpinResult? = null
        for (attempt in 1..NUM_SPIN_ATTEMPTS) {
            tapperService.swipe(swipeX1, swipeY, swipeX2, swipeY, SWIPE_DURATION_MS)
            finalDiscState = checkDiscState()
            // Purple means the server accepted the spin — stop swiping.
            if (finalDiscState == SpinnerDetector.SpinResult.PURPLE) {
                Log.d(TAG, "Early spin exit after attempt $attempt: state=$finalDiscState")
                break
            }
        }

        val success = finalDiscState == SpinnerDetector.SpinResult.PURPLE

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

        updateHud("Spin $succeededOrFailed")
        Log.i(TAG, "Spin $succeededOrFailed (final state: $finalDiscState, session total: ${session.spins})")

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

    private suspend fun updateHud(status: String) {
        val elapsedHours = (System.currentTimeMillis() - session.startMs) / 3_600_000.0
        val spinsPerHour = if (elapsedHours > 0) session.spins / elapsedHours else 0.0
        val stats = "${session.spins} spins (%.1f/hr)".format(spinsPerHour)

        withContext(Dispatchers.Main) {
            tapperService.updateHud(status, stats)
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

        val t0 = System.currentTimeMillis()
        val shot = screenshotService.capture()
        val t1 = System.currentTimeMillis()
        if (shot == null) {
            Log.w(TAG, "Spinner debug: screenshot failed")
            updateHud("Screenshot failed")
            return
        }

        val state = spinnerDetector.detectState(shot)
        val t2 = System.currentTimeMillis()
        val bitmap: Bitmap = spinnerDetector.visualize(shot)
        val t3 = System.currentTimeMillis()
        shot.release()
        Log.d(TAG, "perf: capture=${t1-t0}ms  detect=${t2-t1}ms  visualize=${t3-t2}ms  total=${t3-t0}ms")

        val message = when (state) {
            SpinnerDetector.SpinResult.CYAN   -> "Spinner: cyan (ready)"
            SpinnerDetector.SpinResult.PURPLE -> "Spinner: purple (spun)"
            SpinnerDetector.SpinResult.ABSENT -> "Spinner: absent"
        }
        Log.i(TAG, message)
        val t4 = System.currentTimeMillis()
        updateHud(message)
        val t5 = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            tapperService.showDebugImage(bitmap)
        }
        val t6 = System.currentTimeMillis()
        Log.d(TAG, "perf: updateHud=${t5-t4}ms  showDebugImage=${t6-t5}ms")
    }

    companion object {
        private const val TAG = "Backpacker.AutomationEngine"

        /** When true, each scan runs PokestopDetector and shows debug overlays. */
        @Volatile var debugScan = false

        /** When true, the next FAB activation takes one spinner screenshot and reports its state. */
        @Volatile var debugSpinner = false

        /** When true, screenshots that led to ABSENT disc state are saved to Pictures/Backpacker. */
        @Volatile var saveFailureScreenshots = false

        /** When true, each scan runs PassengerDetector and shows the debug overlay. */
        @Volatile var debugPassenger = false

        // Poll interval when the screen is off — short enough to resume promptly,
        // long enough not to spin the CPU while the display is dark.
        private const val SCREEN_OFF_POLL_MS      = 5_000L

        // Timing constants
        private const val DISMISS_DELAY_MS        =   800L  // wait for speed-warning dialog to animate away
        private const val CAPTURE_RETRY_MS        = 2_000L  // VirtualDisplay not ready yet
        private const val ERROR_RECOVERY_DELAY_MS = 5_000L  // pause after unexpected scan error
        private const val OPEN_DELAY_MS           =   900L  // wait between tap & spin
        private const val SWIPE_DURATION_MS       =   300L  // swipe gesture length
        private const val NUM_SPIN_ATTEMPTS       =     7L  // spin this many times
        private const val SCAN_IMMEDIATELY_MS     = 1_200L  // scan "right away", but with time for the "back to map" animation to settle

        private const val MAX_FAILURE_SCREENSHOTS = 30

        const val PREFS_NAME         = "backpacker_prefs"
        const val PREF_LIFETIME_SPINS = "lifetime_spins"
    }
}
