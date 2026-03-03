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
import android.view.Display
import io.github.joeyparrish.backpacker.util.CoordinateTransform
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps the Android MediaProjection API to provide on-demand screen captures.
 *
 * The VirtualDisplay is created at [CoordinateTransform.NORM_WIDTH] (720) pixels wide so the GPU
 * composites into a smaller surface, reducing GPU work and eliminating any software downscaling.
 * [capture] returns a 720p RGBA [Mat] directly; callers do not need to scale or convert.
 *
 * [deviceWidth] / [deviceHeight] expose the native device resolution, which callers need for
 * gesture coordinate scaling (swipe/tap positions must be in device pixels, not 720p pixels).
 *
 * Android 14 (API 34) requirements:
 *   • A [MediaProjection.Callback] MUST be registered before calling createVirtualDisplay();
 *     omitting it causes createVirtualDisplay() to throw IllegalStateException.
 *   • The token is single-use per consent grant; a new dialog is required each start.
 *
 * @param onProjectionStop  Called (on the main thread) when the system revokes the
 *                          MediaProjection (e.g. the user dismisses the cast tile).
 */
class ScreenshotService(
    context: Context,
    private val mediaProjection: MediaProjection,
    private val onProjectionStop: () -> Unit = {}
) {
    /** True once the system stops the MediaProjection from outside the app. */
    private val projectionStopped = AtomicBoolean(false)

    /** Native device pixel dimensions — used by callers for gesture coordinate scaling. */
    val deviceWidth:  Int
    val deviceHeight: Int
    private val deviceDpi: Int

    /** Capture dimensions — always [CoordinateTransform.NORM_WIDTH] wide (720 px). */
    private val captureWidth:  Int
    private val captureHeight: Int

    private val imageReader: ImageReader
    private val virtualDisplay: VirtualDisplay

    init {
        // Use DisplayManager to obtain physical screen metrics.  Calling
        // WindowManager.getDefaultDisplay() from a Service context is deprecated and can behave
        // unexpectedly on Android 15 (the service has no associated window).
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        deviceWidth  = metrics.widthPixels
        deviceHeight = metrics.heightPixels
        deviceDpi    = metrics.densityDpi

        // Create the VirtualDisplay at 720p so the GPU composites into a smaller surface and
        // capture() can return a Mat at the correct resolution without any software scaling.
        val scale     = CoordinateTransform.NORM_WIDTH.toFloat() / deviceWidth
        captureWidth  = CoordinateTransform.NORM_WIDTH          // always 720
        captureHeight = (deviceHeight * scale).toInt()
        val captureDpi = (deviceDpi * scale).toInt()

        imageReader = ImageReader.newInstance(
            captureWidth, captureHeight,
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

        // Use flag 0 (no special flags) for screen capture.  VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        // requires the privileged CAPTURE_SECURE_VIDEO_OUTPUT permission on Android 15 and can
        // cause a SecurityException; it is not needed for plain screenshot capture.
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "BackpackerCapture",
            captureWidth, captureHeight, captureDpi,
            0,
            imageReader.surface,
            null, null
        )

        Log.d(TAG, "VirtualDisplay: ${captureWidth}x${captureHeight} @ ${captureDpi}dpi " +
                   "(device: ${deviceWidth}x${deviceHeight} @ ${deviceDpi}dpi)")
    }

    /**
     * Capture the current screen as a 720p RGBA [Mat].
     * Returns null if no image is available yet.
     * The caller owns the returned Mat and must call [Mat.release] when done.
     */
    fun capture(): Mat? {
        if (projectionStopped.get()) return null
        val image = imageReader.acquireLatestImage() ?: return null

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride   = plane.rowStride
            val rowPadding  = rowStride - pixelStride * captureWidth

            // Use a Bitmap as an intermediary so Utils.bitmapToMat() handles the exact
            // pixel-format conversion the CV pipeline expects (same path that was already
            // confirmed correct during calibration).
            val paddedWidth = captureWidth + rowPadding / pixelStride
            val raw = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)

            val bmp = if (paddedWidth > captureWidth) {
                Bitmap.createBitmap(raw, 0, 0, captureWidth, captureHeight)
                    .also { raw.recycle() }
            } else {
                raw
            }

            val mat = Mat()
            Utils.bitmapToMat(bmp, mat)
            bmp.recycle()
            mat
        } finally {
            image.close()
        }
    }

    fun release() {
        try { virtualDisplay.release() } catch (e: Exception) { Log.w(TAG, "VD release: $e") }
        try { imageReader.close()      } catch (e: Exception) { Log.w(TAG, "IR close: $e") }
        try { mediaProjection.stop()   } catch (e: Exception) { Log.w(TAG, "MP stop: $e") }
        Log.d(TAG, "ScreenshotService released")
    }

    companion object {
        private const val TAG = "Backpacker.ScreenshotService"
    }
}
