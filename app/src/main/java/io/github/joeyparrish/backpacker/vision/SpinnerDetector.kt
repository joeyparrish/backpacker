// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

package io.github.joeyparrish.backpacker.vision

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
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
    private val spunHsvLower = Scalar(114.0,  75.0, 165.0)
    private val spunHsvUpper = Scalar(155.0, 201.0, 255.0)

    /** HSV bounds for the ready-to-spin (cyan/blue) colour. */
    private val cyanHsvLower = Scalar( 90.0, 175.0, 155.0)
    private val cyanHsvUpper = Scalar(115.0, 240.0, 255.0)

    // Pre-allocated scratch Mats — reused across detectState() calls.
    private val hsv       = Mat()
    private val colorMask = Mat()
    private val combined  = Mat()

    // ringMask is built once from the first frame's dimensions and reused thereafter.
    // ringPixels caches countNonZero(ringMask) so we don't recompute it every call.
    private var ringMask   = Mat()
    private var ringPixels = 0f

    // purpleRingMask is the ring mask intersected with a narrow vertical center strip.
    // purpleRingPixels caches countNonZero(purpleRingMask).
    private var purpleRingMask   = Mat()
    private var purpleRingPixels = 0f

    // Last-computed ratios from detectState(); read by visualize() to draw the text overlay.
    private var lastPurpleRatio = 0f
    private var lastCyanRatio   = 0f

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

            // Build purple ring mask: ring mask intersected with a narrow vertical
            // center strip (PURPLE_CENTER_WIDTH_FRAC wide, full height).
            purpleRingMask.release()
            val stripHalf = (w * PURPLE_CENTER_WIDTH_FRAC / 2.0).toInt()
            val stripL    = (w / 2) - stripHalf
            val stripR    = (w / 2) + stripHalf
            purpleRingMask = Mat.zeros(h, w, CvType.CV_8UC1)
            Imgproc.rectangle(purpleRingMask,
                Point(stripL.toDouble(), 0.0),
                Point(stripR.toDouble(), h.toDouble()),
                Scalar(255.0), -1)
            Core.bitwise_and(purpleRingMask, ringMask, purpleRingMask)
            purpleRingPixels = Core.countNonZero(purpleRingMask).toFloat()

            Log.d(TAG, "Ring mask built: center=(${cx.toInt()},${cy.toInt()}) " +
                       "outerR=$outerR innerR=$innerR pixels=${ringPixels.toInt()} " +
                       "purpleStripX=[$stripL,$stripR] purplePixels=${purpleRingPixels.toInt()}")
        }

        // Check purple (spun) — only within the narrow vertical center strip of the ring.
        Core.inRange(hsv, spunHsvLower, spunHsvUpper, colorMask)
        Core.bitwise_and(colorMask, purpleRingMask, combined)
        val purpleRatio = Core.countNonZero(combined) / purpleRingPixels
        lastPurpleRatio = purpleRatio
        Log.d(TAG, "Purple ring ratio: $purpleRatio")
        if (purpleRatio > RING_DETECT_THRESHOLD) {
            lastCyanRatio = 0f
            return SpinResult.PURPLE
        }

        // Check cyan (ready to spin).
        Core.inRange(hsv, cyanHsvLower, cyanHsvUpper, colorMask)
        Core.bitwise_and(colorMask, ringMask, combined)
        val cyanRatio = Core.countNonZero(combined) / ringPixels
        lastCyanRatio = cyanRatio
        Log.d(TAG, "Cyan ring ratio: $cyanRatio")
        if (cyanRatio > RING_DETECT_THRESHOLD) return SpinResult.CYAN

        return SpinResult.ABSENT
    }

    /**
     * Produce a debug visualization of the spinner ring region.
     * Must be called after [detectState] (which builds [ringMask]) and before
     * [screenshot] is released.
     *
     * Pixels inside the ring mask are shown in their original colour; all other
     * pixels are greyscaled.  White circles mark the inner and outer ring boundaries.
     */
    fun visualize(screenshot: Mat): Bitmap {
        val w = screenshot.cols()
        val h = screenshot.rows()

        val gray1 = Mat()
        val gray4 = Mat()
        Imgproc.cvtColor(screenshot, gray1, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(gray1, gray4, Imgproc.COLOR_GRAY2RGBA)
        gray1.release()

        val viz = gray4.clone()
        gray4.release()

        if (!ringMask.empty()) {
            // Only restore original colour for ring pixels that matched the detected colour.
            // Non-matching ring pixels stay grey, making HSV threshold tuning easier.
            screenshot.copyTo(viz, combined)

            val center = Point(w * RING_CENTER_X, h * RING_CENTER_Y)
            val outerR = (w * RING_OUTER_RADIUS_FRAC).toInt()
            val innerR = (w * RING_INNER_RADIUS_FRAC).toInt()
            val white  = Scalar(255.0, 255.0, 255.0, 255.0)
            Imgproc.circle(viz, center, outerR, white, 3)
            Imgproc.circle(viz, center, innerR, white, 3)

            // Draw the purple center-strip boundaries in yellow.
            val stripHalf = (w * PURPLE_CENTER_WIDTH_FRAC / 2.0).toInt()
            val stripL    = (w / 2) - stripHalf
            val stripR    = (w / 2) + stripHalf
            val yellow    = Scalar(255.0, 255.0, 0.0, 255.0)
            Imgproc.line(viz, Point(stripL.toDouble(), 0.0), Point(stripL.toDouble(), h.toDouble()), yellow, 2)
            Imgproc.line(viz, Point(stripR.toDouble(), 0.0), Point(stripR.toDouble(), h.toDouble()), yellow, 2)
        }

        // Draw ratio text at bottom in white on a black box.
        val text = "cyan=%.1f%%  purple=%.1f%%".format(lastCyanRatio * 100f, lastPurpleRatio * 100f)
        val fontFace  = Imgproc.FONT_HERSHEY_SIMPLEX
        val fontScale = 1.2
        val fontThick = 2
        val baseLine  = IntArray(1)
        val textSize  = Imgproc.getTextSize(text, fontFace, fontScale, fontThick, baseLine)
        val pad       = 12
        val textX     = pad
        val textY     = h - pad - baseLine[0]
        val boxTop    = h - textSize.height.toInt() - baseLine[0] - pad * 2
        Imgproc.rectangle(viz,
            Point(0.0, boxTop.toDouble()),
            Point((textSize.width + pad * 2).toDouble(), h.toDouble()),
            Scalar(0.0, 0.0, 0.0, 200.0), -1)
        Imgproc.putText(viz, text,
            Point(textX.toDouble(), textY.toDouble()),
            fontFace, fontScale, Scalar(255.0, 255.0, 255.0, 255.0), fontThick)

        val bitmap = Bitmap.createBitmap(viz.cols(), viz.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(viz, bitmap)
        viz.release()
        return bitmap
    }

    /** Release all pre-allocated Mats. Call when the detector is no longer needed. */
    fun release() {
        hsv.release()
        colorMask.release()
        combined.release()
        ringMask.release()
        purpleRingMask.release()
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
        private const val RING_CENTER_Y         = 0.480
        private const val RING_OUTER_RADIUS_FRAC = 0.4385
        private const val RING_INNER_RADIUS_FRAC = 0.3935

        // Minimum fraction of ring pixels that must match a colour to report that state.
        private const val RING_DETECT_THRESHOLD = 0.70f

        // Width of the vertical center strip used to constrain purple detection,
        // as a fraction of frame width.  Only the ring pixels within this strip
        // are checked for purple; the ratio is computed against strip-ring pixels only.
        private const val PURPLE_CENTER_WIDTH_FRAC = 0.08
    }
}
