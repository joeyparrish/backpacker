package io.github.joeyparrish.backpacker.vision

import android.graphics.Bitmap
import android.util.Log
import io.github.joeyparrish.backpacker.util.BitmapUtils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Determines the state of the Pokéstop spinner circle on the detail screen.
 *
 * Range is not checked — if the stop opens, we attempt to spin it.
 * Both network failures and out-of-range failures are handled by the
 * caller's retry loop.
 */
class SpinnerDetector {

    /** Possible states of the spinner circle. */
    enum class SpinResult { CYAN, PURPLE, ABSENT }

    /** HSV bounds for the spun (purple) colour after a successful spin. Uncalibrated. */
    private val spunHsvLower = Scalar(120.0, 100.0,  80.0)
    private val spunHsvUpper = Scalar(160.0, 255.0, 255.0)

    /** HSV bounds for the ready-to-spin (cyan/blue) circle colour. Uncalibrated. */
    private val cyanHsvLower = Scalar( 85.0, 100.0, 100.0)
    private val cyanHsvUpper = Scalar(130.0, 255.0, 255.0)

    /**
     * Detect the state of the spinner circle in the centre region of the screen.
     * Returns [SpinResult.PURPLE] if the circle appears spun, [SpinResult.CYAN] if
     * it appears ready to spin, or [SpinResult.ABSENT] if neither colour is dominant.
     *
     * NOTE: the ROI is not yet calibrated to the exact circle position — fix later.
     */
    fun detectState(screenshot: Bitmap): SpinResult {
        val scaled = BitmapUtils.scaleTo720p(screenshot)
        val w = scaled.width
        val h = scaled.height

        val roiLeft   = (w * 0.25).toInt()
        val roiTop    = (h * 0.30).toInt()
        val roiWidth  = (w * 0.50).toInt()
        val roiHeight = (h * 0.40).toInt()
        val cropped = Bitmap.createBitmap(scaled, roiLeft, roiTop, roiWidth, roiHeight)

        val rgba = BitmapUtils.bitmapToMat(cropped)
        val hsv  = Mat()
        val mask = Mat()

        return try {
            Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsv,  hsv, Imgproc.COLOR_RGB2HSV)

            val total = (roiWidth * roiHeight).toFloat()

            Core.inRange(hsv, spunHsvLower, spunHsvUpper, mask)
            val purpleRatio = Core.countNonZero(mask) / total
            Log.d(TAG, "Purple ratio: $purpleRatio")
            if (purpleRatio > 0.10f) return SpinResult.PURPLE

            Core.inRange(hsv, cyanHsvLower, cyanHsvUpper, mask)
            val cyanRatio = Core.countNonZero(mask) / total
            Log.d(TAG, "Cyan ratio: $cyanRatio")
            if (cyanRatio > 0.10f) return SpinResult.CYAN

            SpinResult.ABSENT
        } finally {
            rgba.release()
            hsv.release()
            mask.release()
        }
    }

    /** Returns true if the spinner circle appears to have turned purple (spin succeeded). */
    fun isSpinSuccess(screenshot: Bitmap): Boolean =
        detectState(screenshot) == SpinResult.PURPLE

    companion object {
        private const val TAG = "Backpacker.SpinnerDetector"
    }
}
