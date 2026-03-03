package io.github.joeyparrish.backpacker.vision

import android.graphics.Bitmap
import android.util.Log
import io.github.joeyparrish.backpacker.util.BitmapUtils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Determines the state of the Pokéstop spinner circle on the detail screen.
 *
 * Detection strategy:
 *   1. Convert frame to HSV (for colour masks) and greyscale (for Hough).
 *   2. HoughCircles on the greyscale image to locate the spinner circle.
 *      Expected outer radius: 35–50% of the normalised 720px width.
 *      If found, use detected centre + radius; if not, fall back to hardcoded defaults.
 *   3. Derive inner radius = outerR × RING_INNER_OUTER_RATIO (measured ≈ 0.90).
 *   4. Build an annular mask and AND it against purple / cyan colour masks.
 *   5. Report the colour whose ring-pixel fraction exceeds RING_DETECT_THRESHOLD.
 *
 * Measured on a 684px-wide device (normalised to 720px):
 *   outer radius ≈ 43.9% of width, inner/outer ≈ 90%.
 */
class SpinnerDetector {

    /** Possible states of the spinner circle. */
    enum class SpinResult { CYAN, PURPLE, ABSENT }

    /** HSV bounds for the spun (purple) colour. Uncalibrated. */
    private val spunHsvLower = Scalar(120.0, 100.0,  80.0)
    private val spunHsvUpper = Scalar(160.0, 255.0, 255.0)

    /** HSV bounds for the ready-to-spin (cyan/blue) colour. Uncalibrated. */
    private val cyanHsvLower = Scalar( 85.0, 100.0, 100.0)
    private val cyanHsvUpper = Scalar(130.0, 255.0, 255.0)

    /**
     * Detect the state of the spinner ring.
     * Returns [SpinResult.PURPLE] if spun, [SpinResult.CYAN] if ready, [SpinResult.ABSENT] otherwise.
     */
    fun detectState(screenshot: Bitmap): SpinResult {
        val scaled = BitmapUtils.scaleTo720p(screenshot)
        val w = scaled.width   // always 720 after normalisation
        val h = scaled.height  // varies by device aspect ratio

        val rgba      = BitmapUtils.bitmapToMat(scaled)
        val hsv       = Mat()
        val gray      = Mat()
        val circles   = Mat()
        val ringMask  = Mat.zeros(h, w, CvType.CV_8UC1)
        val colorMask = Mat()
        val combined  = Mat()

        return try {
            // HSV for colour detection
            Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsv,  hsv, Imgproc.COLOR_RGB2HSV)

            // Greyscale for Hough circle detection
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(9.0, 9.0), 2.0)

            Imgproc.HoughCircles(
                gray, circles, Imgproc.HOUGH_GRADIENT,
                /* dp        = */ 1.0,
                /* minDist   = */ h / 2.0,   // expect at most one large circle
                /* param1    = */ 100.0,
                /* param2    = */ 30.0,
                /* minRadius = */ (w * HOUGH_MIN_RADIUS_FRAC).toInt(),
                /* maxRadius = */ (w * HOUGH_MAX_RADIUS_FRAC).toInt()
            )

            // Pick the detected circle closest to the screen centre; fall back to defaults.
            val cx = w / 2.0
            val cy = h / 2.0
            val center: Point
            val outerR: Int
            val innerR: Int

            if (circles.cols() > 0) {
                var best = circles.get(0, 0)!!
                var bestDistSq = run { val dx = best[0] - cx; val dy = best[1] - cy; dx*dx + dy*dy }
                for (i in 1 until circles.cols()) {
                    val c = circles.get(0, i) ?: continue
                    val dx = c[0] - cx; val dy = c[1] - cy
                    val dSq = dx*dx + dy*dy
                    if (dSq < bestDistSq) { bestDistSq = dSq; best = c }
                }
                center = Point(best[0], best[1])
                outerR = best[2].toInt()
                innerR = (outerR * RING_INNER_OUTER_RATIO).toInt()
                Log.d(TAG, "Circle found: center=(${best[0].toInt()},${best[1].toInt()}) " +
                           "outerR=$outerR innerR=$innerR")
            } else {
                center = Point(cx, cy)
                outerR = (w * RING_OUTER_RADIUS_FRAC).toInt()
                innerR = (outerR * RING_INNER_OUTER_RATIO).toInt()
                Log.d(TAG, "No circle found via Hough; using defaults: outerR=$outerR innerR=$innerR")
            }

            // Build annular mask: filled outer circle minus filled inner circle.
            Imgproc.circle(ringMask, center, outerR, Scalar(255.0), -1)
            Imgproc.circle(ringMask, center, innerR, Scalar(  0.0), -1)
            val ringPixels = Core.countNonZero(ringMask).toFloat()

            // Check purple (spun)
            Core.inRange(hsv, spunHsvLower, spunHsvUpper, colorMask)
            Core.bitwise_and(colorMask, ringMask, combined)
            val purpleRatio = Core.countNonZero(combined) / ringPixels
            Log.d(TAG, "Purple ring ratio: $purpleRatio")
            if (purpleRatio > RING_DETECT_THRESHOLD) return SpinResult.PURPLE

            // Check cyan (ready to spin)
            Core.inRange(hsv, cyanHsvLower, cyanHsvUpper, colorMask)
            Core.bitwise_and(colorMask, ringMask, combined)
            val cyanRatio = Core.countNonZero(combined) / ringPixels
            Log.d(TAG, "Cyan ring ratio: $cyanRatio")
            if (cyanRatio > RING_DETECT_THRESHOLD) return SpinResult.CYAN

            SpinResult.ABSENT
        } finally {
            rgba.release()
            hsv.release()
            gray.release()
            circles.release()
            ringMask.release()
            colorMask.release()
            combined.release()
        }
    }

    /** Returns true if the spinner ring appears to have turned purple (spin succeeded). */
    fun isSpinSuccess(screenshot: Bitmap): Boolean =
        detectState(screenshot) == SpinResult.PURPLE

    companion object {
        private const val TAG = "Backpacker.SpinnerDetector"

        // Fallback ring geometry (fractions of normalised 720px width).
        // Measured on a 684px device: outer ≈ 43.9% of width.
        private const val RING_OUTER_RADIUS_FRAC = 0.439

        // Ratio inner/outer measured at ≈ 538/600 = 0.897; using 0.90.
        // Applied to both the Hough-detected radius and the fallback.
        private const val RING_INNER_OUTER_RATIO = 0.90

        // HoughCircles search window: spinner outer radius expected at 35–50% of width.
        private const val HOUGH_MIN_RADIUS_FRAC = 0.35
        private const val HOUGH_MAX_RADIUS_FRAC = 0.50

        // Minimum fraction of ring pixels that must match a colour to report that state.
        private const val RING_DETECT_THRESHOLD = 0.20f
    }
}
