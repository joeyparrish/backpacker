package io.github.joeyparrish.backpacker.vision

import android.graphics.Bitmap
import android.util.Log
import io.github.joeyparrish.backpacker.util.BitmapUtils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Determines whether a Pokéstop spin attempt succeeded.
 *
 * Range is not checked — if the stop opens, we attempt to spin it.
 * Both network failures and out-of-range failures are handled by the
 * caller's retry loop.
 */
class SpinnerDetector {

    /** HSV bounds for the spun (purple) colour after a successful spin. Uncalibrated. */
    private val spunHsvLower = Scalar(120.0, 100.0, 80.0)
    private val spunHsvUpper = Scalar(160.0, 255.0, 255.0)

    /**
     * Check for spin success by looking for the spun (purple) colour in the
     * centre region of the screen.
     */
    fun isSpinSuccess(screenshot: Bitmap): Boolean {
        val scaled = BitmapUtils.scaleTo720p(screenshot)
        val w = scaled.width
        val h = scaled.height

        val roiLeft = (w * 0.25).toInt()
        val roiTop = (h * 0.30).toInt()
        val roiWidth = (w * 0.50).toInt()
        val roiHeight = (h * 0.40).toInt()
        val cropped = Bitmap.createBitmap(scaled, roiLeft, roiTop, roiWidth, roiHeight)

        val rgba = BitmapUtils.bitmapToMat(cropped)
        val hsv = Mat()
        val spunMask = Mat()

        return try {
            Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)
            Core.inRange(hsv, spunHsvLower, spunHsvUpper, spunMask)

            val spunPixels = Core.countNonZero(spunMask)
            val totalPixels = roiWidth * roiHeight
            val spunRatio = spunPixels.toFloat() / totalPixels

            Log.d(TAG, "Spun pixel ratio: $spunRatio")
            spunRatio > 0.10f

        } finally {
            rgba.release()
            hsv.release()
            spunMask.release()
        }
    }

    companion object {
        private const val TAG = "Backpacker.SpinnerDetector"
    }
}
