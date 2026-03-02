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

/**
 * Wraps the Android MediaProjection API to provide on-demand screen captures.
 *
 * Note: On Android 14+ the MediaProjection token is single-use per service start.
 * A new consent dialog must be shown each time AutomationService starts.
 */
class ScreenshotService(
    context: Context,
    private val mediaProjection: MediaProjection
) {
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
