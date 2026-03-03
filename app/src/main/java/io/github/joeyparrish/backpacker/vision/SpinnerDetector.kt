package io.github.joeyparrish.backpacker.vision

import android.graphics.Bitmap
import android.util.Log
import io.github.joeyparrish.backpacker.util.BitmapUtils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Detects the spinner circle on the Pokéstop detail screen and determines
 * whether a spin attempt succeeded. See PLAN.md §3b.
 *
 * All threshold constants are initial guesses — calibrate against real screenshots.
 */
class SpinnerDetector {

    /** Min radius (720p px) for spinner to be "in range". Calibrate from screenshots. */
    private val minInRangeRadius = 80.0

    private val cannyThresh = 100.0
    private val accThresh = 30.0

    /** HSV bounds for the "spun" (purple/grey) colour after a successful spin. */
    private val spunHsvLower = Scalar(120.0, 50.0, 80.0)
    private val spunHsvUpper = Scalar(160.0, 255.0, 255.0)

    /**
     * Detect the spinner circle and return its radius in 720p pixels, or null if not found.
     */
    fun detectSpinnerRadius(screenshot: Bitmap): Float? {
        val scaled = BitmapUtils.scaleTo720p(screenshot)
        val rgba = BitmapUtils.bitmapToMat(scaled)
        val gray = Mat()
        val circles = Mat()

        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, org.opencv.core.Size(9.0, 9.0), 2.0)

            Imgproc.HoughCircles(
                gray, circles, Imgproc.HOUGH_GRADIENT,
                /* dp = */ 1.0,
                /* minDist = */ gray.rows() / 4.0,
                /* param1 = */ cannyThresh,
                /* param2 = */ accThresh,
                /* minRadius = */ (minInRangeRadius * 0.5).toInt(),
                /* maxRadius = */ (gray.cols() * 0.55).toInt()
            )

            if (circles.cols() == 0) {
                Log.d(TAG, "No spinner circle detected")
                return null
            }

            var bestRadius = 0f
            for (i in 0 until circles.cols()) {
                val data = circles.get(0, i) ?: continue
                val r = data[2].toFloat()
                if (r > bestRadius) bestRadius = r
            }

            Log.d(TAG, "Spinner radius: $bestRadius px (min in-range: $minInRangeRadius)")
            return if (bestRadius > 0) bestRadius else null

        } finally {
            rgba.release()
            gray.release()
            circles.release()
        }
    }

    fun isInRange(screenshot: Bitmap): Boolean {
        val radius = detectSpinnerRadius(screenshot) ?: return false
        return radius >= minInRangeRadius
    }

    /**
     * Check for spin success by looking for the "spun" purple/grey colour in the
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
