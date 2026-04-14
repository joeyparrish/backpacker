// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

package io.github.joeyparrish.backpacker.vision

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Detects the "I'M A PASSENGER" speed-warning dialog (and any other dialog that uses the same
 * yellow-green → teal gradient pill button) in a 720p-normalised screenshot.
 *
 * Algorithm:
 *   1. Find the white dialog box by thresholding for near-white pixels and taking the
 *      largest bright contour.  If no dialog is present, return null immediately.
 *   2. Convert RGBA → HSV; threshold for yellow-green → teal (H 35–110, S > 60, V > 120).
 *   3. Zero out the green mask outside the dialog bounds so the background overlay
 *      cannot merge with the button into a single contour.
 *   4. Find contours inside the dialog; select the largest one with aspect ratio ≥
 *      [MIN_ASPECT_RATIO] and area ≥ [MIN_AREA] — that is the pill button.
 *   5. Return the bounding-rect centre as the tap target in 720p-normalised space.
 *
 * Requiring a white dialog as a prerequisite also improves specificity: the button is
 * only ever present inside such a dialog.
 *
 * Other PoGO dialogs that share this button style (e.g. post-catch bonus) will also be
 * matched and dismissed; that is intentional.
 *
 * Pre-allocated Mats are reused across calls.  Call [release] when done.
 */
class PassengerDetector {

    // HSV range covering yellow-green (H≈40) through teal (H≈105) in OpenCV 0–180 scale.
    // S lower bound is loose (60) to catch the pale/desaturated left side of the gradient.
    private val hsvLower = Scalar(35.0, 60.0, 120.0)
    private val hsvUpper = Scalar(110.0, 255.0, 255.0)

    // Minimum aspect ratio (width / height) for a pill-shaped button.
    private val minAspectRatio = 3.0f

    // Minimum contour area (720p px²) to reject small incidental green elements.
    private val minArea = 10_000.0

    // Greyscale threshold for "near-white" dialog background detection.
    private val whiteThreshold = 230.0

    // Minimum fraction of screen area a white region must cover to be the dialog.
    private val minDialogAreaFrac = 0.12

    private val gray         = Mat()
    private val whiteMask    = Mat()
    private val hsv          = Mat()
    private val mask         = Mat()
    // restrictedMask is sized lazily to the first frame and reused thereafter.
    private var restrictedMask = Mat()
    private val hierarchy    = Mat()

    /**
     * Detect a green pill button in [screenshot] (720p RGBA Mat from ScreenshotService).
     * Returns the button centre in 720p-normalised space, or null if no dialog or pill found.
     * The caller retains ownership of [screenshot].
     */
    fun detect(screenshot: Mat): PointF? {
        // Step 1: locate the white dialog box.  Without it there is no button.
        val dialogRect = findDialogRect(screenshot) ?: return null

        // Step 2: build the green mask for the full image.
        Imgproc.cvtColor(screenshot, hsv, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)
        Core.inRange(hsv, hsvLower, hsvUpper, mask)

        // Step 3: zero the mask outside the dialog so background and button cannot merge.
        if (restrictedMask.rows() != screenshot.rows() || restrictedMask.cols() != screenshot.cols()) {
            restrictedMask.release()
            restrictedMask = Mat.zeros(screenshot.rows(), screenshot.cols(), CvType.CV_8UC1)
        } else {
            restrictedMask.setTo(Scalar(0.0))
        }
        mask.submat(dialogRect).copyTo(restrictedMask.submat(dialogRect))

        // Step 4: find contours inside the restricted mask.
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            restrictedMask, contours, hierarchy,
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
            Log.d(TAG, "No pill button found (${contours.size} contours inside dialog)")
        }

        return bestCenter
    }

    /**
     * Find the bounding rect of the white dialog box in [screenshot].
     * Returns null if no sufficiently large bright region is found.
     */
    private fun findDialogRect(screenshot: Mat): Rect? {
        Imgproc.cvtColor(screenshot, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.threshold(gray, whiteMask, whiteThreshold, 255.0, Imgproc.THRESH_BINARY)

        val contours = mutableListOf<MatOfPoint>()
        val tmpHierarchy = Mat()
        Imgproc.findContours(
            whiteMask, contours, tmpHierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        tmpHierarchy.release()

        val minArea = screenshot.cols() * screenshot.rows() * minDialogAreaFrac
        var bestRect: Rect? = null
        var bestArea = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area >= minArea && area > bestArea) {
                bestArea = area
                bestRect = Imgproc.boundingRect(contour)
            }
            contour.release()
        }

        if (bestRect != null) {
            Log.d(TAG, "Dialog at $bestRect (area=${bestArea.toInt()})")
        } else {
            Log.d(TAG, "No dialog found")
        }
        return bestRect
    }

    /**
     * Produce a debug visualization of the last [detect] call.
     * Must be called before [screenshot] is released.
     *
     * Green-matched pixels within the dialog area are shown in colour; all others are
     * greyscaled.  A white circle marks [buttonCenter] if non-null.
     */
    fun visualize(screenshot: Mat, buttonCenter: PointF?): Bitmap {
        val gray1 = Mat()
        val gray4 = Mat()
        Imgproc.cvtColor(screenshot, gray1, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(gray1, gray4, Imgproc.COLOR_GRAY2RGBA)
        gray1.release()

        val viz = gray4.clone()
        gray4.release()
        // Use restrictedMask so the visualisation reflects what the detector actually saw.
        if (!restrictedMask.empty()) {
            screenshot.copyTo(viz, restrictedMask)
        }

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
        gray.release()
        whiteMask.release()
        hsv.release()
        mask.release()
        restrictedMask.release()
        hierarchy.release()
    }

    companion object {
        private const val TAG = "Backpacker.PassengerDetector"
    }
}
