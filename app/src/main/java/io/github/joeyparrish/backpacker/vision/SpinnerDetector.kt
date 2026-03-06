// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

package io.github.joeyparrish.backpacker.vision

import android.util.Log
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
 *   2. Build a fixed-geometry annular mask over the region where the spinner
 *      ring always appears.  The ring position and size are defined as fractions
 *      of the frame dimensions (see companion constants).  The mask is computed
 *      once from the first frame's dimensions and reused on every subsequent call.
 *   3. AND the ring mask against purple / cyan HSV colour masks in turn.
 *   4. Report the colour whose ring-pixel fraction exceeds RING_DETECT_THRESHOLD.
 *      If neither colour meets the threshold, return ABSENT.
 *
 * Using a fixed mask rather than HoughCircles eliminates the failure mode where
 * Hough finds a "circle" on the map or elsewhere when the detail view is not open.
 *
 * The [Mat] passed to [detectState] must be in RGBA format at 720p (as produced by
 * ScreenshotService). The caller retains ownership and must call [Mat.release] after use.
 *
 * Intermediate Mats are pre-allocated as instance fields and reused across calls to avoid
 * per-scan JNI allocation pressure in CAR mode (2 s scan interval).
 * Call [release] when the detector is no longer needed.
 */
class SpinnerDetector {

    /** Possible states of the spinner circle. */
    enum class SpinResult { CYAN, PURPLE, ABSENT }

    /** HSV bounds for the spun (purple) colour. */
    private val spunHsvLower = Scalar(120.0, 100.0,  80.0)
    private val spunHsvUpper = Scalar(160.0, 255.0, 255.0)

    /** HSV bounds for the ready-to-spin (cyan/blue) colour. */
    private val cyanHsvLower = Scalar( 85.0, 100.0, 100.0)
    private val cyanHsvUpper = Scalar(130.0, 255.0, 255.0)

    // Pre-allocated scratch Mats — reused across detectState() calls.
    private val hsv       = Mat()
    private val colorMask = Mat()
    private val combined  = Mat()

    // ringMask is built once from the first frame's dimensions and reused thereafter.
    // ringPixels caches countNonZero(ringMask) so we don't recompute it every call.
    private var ringMask   = Mat()
    private var ringPixels = 0f

    /**
     * Detect the state of the spinner ring.
     * Returns [SpinResult.PURPLE] if spun, [SpinResult.CYAN] if ready, [SpinResult.ABSENT] otherwise.
     * The caller retains ownership of [screenshot].
     */
    fun detectState(screenshot: Mat): SpinResult {
        val w = screenshot.cols()
        val h = screenshot.rows()

        // Convert to HSV for colour detection.
        Imgproc.cvtColor(screenshot, hsv, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)

        // Build fixed-geometry ring mask on first call (or if frame size changes).
        if (ringMask.rows() != h || ringMask.cols() != w) {
            ringMask.release()
            ringMask = Mat.zeros(h, w, CvType.CV_8UC1)

            val cx     = w * RING_CENTER_X
            val cy     = h * RING_CENTER_Y
            val outerR = (w * RING_OUTER_RADIUS_FRAC).toInt()
            val innerR = (w * RING_INNER_RADIUS_FRAC).toInt()
            val center = Point(cx, cy)

            Imgproc.circle(ringMask, center, outerR, Scalar(255.0), -1)
            Imgproc.circle(ringMask, center, innerR, Scalar(  0.0), -1)
            ringPixels = Core.countNonZero(ringMask).toFloat()

            Log.d(TAG, "Ring mask built: center=(${cx.toInt()},${cy.toInt()}) " +
                       "outerR=$outerR innerR=$innerR pixels=${ringPixels.toInt()}")
        }

        // Check purple (spun).
        Core.inRange(hsv, spunHsvLower, spunHsvUpper, colorMask)
        Core.bitwise_and(colorMask, ringMask, combined)
        val purpleRatio = Core.countNonZero(combined) / ringPixels
        Log.d(TAG, "Purple ring ratio: $purpleRatio")
        if (purpleRatio > RING_DETECT_THRESHOLD) return SpinResult.PURPLE

        // Check cyan (ready to spin).
        Core.inRange(hsv, cyanHsvLower, cyanHsvUpper, colorMask)
        Core.bitwise_and(colorMask, ringMask, combined)
        val cyanRatio = Core.countNonZero(combined) / ringPixels
        Log.d(TAG, "Cyan ring ratio: $cyanRatio")
        if (cyanRatio > RING_DETECT_THRESHOLD) return SpinResult.CYAN

        return SpinResult.ABSENT
    }

    /** Release all pre-allocated Mats. Call when the detector is no longer needed. */
    fun release() {
        hsv.release()
        colorMask.release()
        combined.release()
        ringMask.release()
    }

    companion object {
        private const val TAG = "Backpacker.SpinnerDetector"

        // Fixed ring geometry as fractions of the frame dimensions.
        // The spinner ring is horizontally centred and sits slightly above
        // the vertical centre of the detail view.
        //
        // Measured from device captures (720p normalised):
        //   centre:        50.0% x,  48.9% y
        //   outer diameter: 87.7% of width  → radius ≈ 43.85%
        //   inner diameter: 78.7% of width  → radius ≈ 39.35%
        private const val RING_CENTER_X         = 0.500
        private const val RING_CENTER_Y         = 0.489
        private const val RING_OUTER_RADIUS_FRAC = 0.4385
        private const val RING_INNER_RADIUS_FRAC = 0.3935

        // Minimum fraction of ring pixels that must match a colour to report that state.
        private const val RING_DETECT_THRESHOLD = 0.20f
    }
}
