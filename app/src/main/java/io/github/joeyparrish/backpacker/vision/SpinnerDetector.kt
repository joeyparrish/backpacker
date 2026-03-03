package io.github.joeyparrish.backpacker.vision

import android.graphics.Bitmap
import android.util.Log
import io.github.joeyparrish.backpacker.util.BitmapUtils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Determines the state of the Pokéstop spinner circle on the detail screen.
 *
 * Detection strategy:
 *   1. Convert frame to HSV.
 *   2. Apply colour masks (purple, cyan) — same technique as PokestopDetector.
 *   3. AND each mask with an annular (ring) mask centred on screen:
 *        outer radius ≈ 42.5% of width  (→ ~85% diameter)
 *        inner radius ≈ 36.0% of width  (ring is ~12.5% of width thick)
 *   4. If ≥ RING_DETECT_THRESHOLD of ring pixels match a colour → that state.
 *
 * All radius fractions are uncalibrated — tune via spinner debug mode and logcat.
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
        val ringMask  = Mat.zeros(h, w, CvType.CV_8UC1)
        val colorMask = Mat()
        val combined  = Mat()

        return try {
            Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsv,  hsv, Imgproc.COLOR_RGB2HSV)

            // Build annular mask: filled outer circle minus filled inner circle.
            val center = Point(w / 2.0, h / 2.0)
            val outerR = (w * RING_OUTER_RADIUS_FRAC).toInt()
            val innerR = (w * RING_INNER_RADIUS_FRAC).toInt()
            Imgproc.circle(ringMask, center, outerR, Scalar(255.0), -1)
            Imgproc.circle(ringMask, center, innerR, Scalar(  0.0), -1)

            val ringPixels = Core.countNonZero(ringMask).toFloat()
            Log.d(TAG, "Ring mask: outerR=$outerR innerR=$innerR pixels=${ringPixels.toInt()}")

            // Check purple
            Core.inRange(hsv, spunHsvLower, spunHsvUpper, colorMask)
            Core.bitwise_and(colorMask, ringMask, combined)
            val purpleRatio = Core.countNonZero(combined) / ringPixels
            Log.d(TAG, "Purple ring ratio: $purpleRatio")
            if (purpleRatio > RING_DETECT_THRESHOLD) return SpinResult.PURPLE

            // Check cyan
            Core.inRange(hsv, cyanHsvLower, cyanHsvUpper, colorMask)
            Core.bitwise_and(colorMask, ringMask, combined)
            val cyanRatio = Core.countNonZero(combined) / ringPixels
            Log.d(TAG, "Cyan ring ratio: $cyanRatio")
            if (cyanRatio > RING_DETECT_THRESHOLD) return SpinResult.CYAN

            SpinResult.ABSENT
        } finally {
            rgba.release()
            hsv.release()
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

        // Ring geometry — fractions of the normalised 720px width. Uncalibrated.
        private const val RING_OUTER_RADIUS_FRAC = 0.425  // outer radius → ~85% diameter
        private const val RING_INNER_RADIUS_FRAC = 0.360  // inner radius → ring ~12.5% of width thick

        // Minimum fraction of ring pixels that must match a colour to report that state.
        private const val RING_DETECT_THRESHOLD  = 0.20f
    }
}
