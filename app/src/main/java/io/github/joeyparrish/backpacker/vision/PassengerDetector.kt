// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

package io.github.joeyparrish.backpacker.vision

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Detects the "I'M A PASSENGER" speed-warning dialog (and any other dialog that uses the same
 * yellow-green → teal gradient pill button) in a 720p-normalised screenshot.
 *
 * Algorithm:
 *   1. Convert RGBA → HSV
 *   2. Threshold for yellow-green → teal (H 40–95, S > 150, V > 150)
 *   3. Morphological close to fill the white-text holes left inside the button
 *   4. Find contours; select the largest one with aspect ratio ≥ [MIN_ASPECT_RATIO]
 *      and area ≥ [MIN_AREA] — that is the pill button
 *   5. Return the bounding-rect centre as the tap target in 720p-normalised space
 *
 * The HSV range also covers the green background overlay that obscures the game behind the
 * dialog, but that blob has a screen-filling aspect ratio and is rejected by the aspect-ratio
 * filter.
 *
 * Other PoGO dialogs that share this button style (e.g. post-catch bonus) will also be matched
 * and dismissed; that is intentional.
 *
 * Pre-allocated Mats are reused across calls.  Call [release] when done.
 */
class PassengerDetector {

    // HSV range covering yellow-green (H≈40) through teal (H≈95) in OpenCV 0–180 scale.
    private val hsvLower = Scalar(40.0, 150.0, 150.0)
    private val hsvUpper = Scalar(95.0, 255.0, 255.0)

    // Minimum aspect ratio (width / height) for a pill-shaped button.
    // The background overlay blob has a portrait aspect ratio (~0.56) and is rejected here.
    private val minAspectRatio = 3.0f

    // Minimum contour area (720p px²) to reject small incidental green elements.
    private val minArea = 10_000.0

    // Morphological close kernel: large enough to bridge the white-text gaps inside the button.
    private val morphKernelSize = Size(15.0, 15.0)

    private val hsv         = Mat()
    private val mask        = Mat()
    private val morphed     = Mat()
    private val hierarchy   = Mat()
    private val morphKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, morphKernelSize)

    /**
     * Detect a green pill button in [screenshot] (720p RGBA Mat from ScreenshotService).
     * Returns the button centre in 720p-normalised space, or null if no pill found.
     * The caller retains ownership of [screenshot].
     */
    fun detect(screenshot: Mat): PointF? {
        Imgproc.cvtColor(screenshot, hsv, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)
        Core.inRange(hsv, hsvLower, hsvUpper, mask)
        Imgproc.morphologyEx(mask, morphed, Imgproc.MORPH_CLOSE, morphKernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            morphed, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        var bestCenter: PointF? = null
        var bestArea = 0.0

        for (contour in contours) {
            val bb = Imgproc.boundingRect(contour)
            val area = Imgproc.contourArea(contour)
            val aspect = if (bb.height > 0) bb.width.toFloat() / bb.height else 0f

            if (area >= minArea && aspect >= minAspectRatio) {
                Log.d(TAG, "Candidate pill: area=${area.toInt()} aspect=%.2f @ (${bb.x},${bb.y}) ${bb.width}×${bb.height}".format(aspect))
                if (area > bestArea) {
                    bestArea = area
                    bestCenter = PointF(bb.x + bb.width / 2f, bb.y + bb.height / 2f)
                }
            }
            contour.release()
        }

        if (bestCenter != null) {
            Log.i(TAG, "Pill button at (${bestCenter.x}, ${bestCenter.y}), area=${bestArea.toInt()}")
        } else {
            Log.d(TAG, "No pill button found (${contours.size} contours checked)")
        }

        return bestCenter
    }

    /**
     * Produce a debug visualization of the last [detect] call.
     * Must be called before [screenshot] is released.
     *
     * Pixels matching the green HSV range are shown in colour; all others are greyscaled.
     * A white circle marks [buttonCenter] if non-null.
     */
    fun visualize(screenshot: Mat, buttonCenter: PointF?): Bitmap {
        val gray1 = Mat()
        val gray4 = Mat()
        Imgproc.cvtColor(screenshot, gray1, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(gray1, gray4, Imgproc.COLOR_GRAY2RGBA)
        gray1.release()

        val viz = gray4.clone()
        gray4.release()
        screenshot.copyTo(viz, mask)

        if (buttonCenter != null) {
            Imgproc.circle(
                viz,
                Point(buttonCenter.x.toDouble(), buttonCenter.y.toDouble()),
                30,
                Scalar(255.0, 255.0, 255.0, 255.0),
                5
            )
        }

        val bitmap = Bitmap.createBitmap(viz.cols(), viz.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(viz, bitmap)
        viz.release()
        return bitmap
    }

    /** Release all pre-allocated Mats.  Call when the detector is no longer needed. */
    fun release() {
        hsv.release()
        mask.release()
        morphed.release()
        hierarchy.release()
        morphKernel.release()
    }

    companion object {
        private const val TAG = "Backpacker.PassengerDetector"
    }
}
