package io.github.joeyparrish.backpacker.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps the Android MediaProjection API to provide on-demand screen captures.
 *
 * Android 14 (API 34) requirements:
 *   • A [MediaProjection.Callback] MUST be registered before calling createVirtualDisplay();
 *     omitting it causes createVirtualDisplay() to throw IllegalStateException.
 *   • The token is single-use per consent grant; a new dialog is required each start.
 *
 * @param onProjectionStop  Called (on the main thread) when the system revokes the
 *                          MediaProjection (e.g. the user dismisses the cast tile).
 *                          AutomationService uses this to stop itself cleanly.
 */
class ScreenshotService(
    context: Context,
    private val mediaProjection: MediaProjection,
    private val onProjectionStop: () -> Unit = {}
) {
    /** True once the system stops the MediaProjection from outside the app. */
    private val projectionStopped = AtomicBoolean(false)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val displayWidth: Int
    private val displayHeight: Int
    private val displayDpi: Int

    private val imageReader: ImageReader
    private val virtualDisplay: VirtualDisplay

    init {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        displayDpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            displayWidth, displayHeight,
            PixelFormat.RGBA_8888,
            /* maxImages = */ 2
        )

        // Required on Android 14+ (API 34+): register a callback BEFORE calling
        // createVirtualDisplay(), or the call throws IllegalStateException.
        // Passing null as the Handler runs the callback on the main-thread Looper.
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system or user")
                projectionStopped.set(true)
                onProjectionStop()
            }
        }, null)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "BackpackerCapture",
            displayWidth, displayHeight, displayDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null, null
        )

        Log.d(TAG, "VirtualDisplay created: ${displayWidth}x${displayHeight} @ ${displayDpi}dpi")
    }

    /**
     * Capture the current screen as a Bitmap (ARGB_8888).
     * Returns null if no image is available yet.
     * The caller owns the returned Bitmap and should recycle it when done.
     */
    fun capture(): Bitmap? {
        if (projectionStopped.get()) return null
        val image = imageReader.acquireLatestImage() ?: return null

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * displayWidth

            val paddedWidth = displayWidth + rowPadding / pixelStride
            val raw = Bitmap.createBitmap(paddedWidth, displayHeight, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)

            if (paddedWidth > displayWidth) {
                Bitmap.createBitmap(raw, 0, 0, displayWidth, displayHeight).also { raw.recycle() }
            } else {
                raw
            }
        } finally {
            image.close()
        }
    }

    fun release() {
        try { virtualDisplay.release() } catch (e: Exception) { Log.w(TAG, "VD release: $e") }
        try { imageReader.close() } catch (e: Exception) { Log.w(TAG, "IR close: $e") }
        try { mediaProjection.stop() } catch (e: Exception) { Log.w(TAG, "MP stop: $e") }
        Log.d(TAG, "ScreenshotService released")
    }

    val screenWidth: Int get() = displayWidth
    val screenHeight: Int get() = displayHeight

    companion object {
        private const val TAG = "ScreenshotService"
    }
}
