// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

package io.github.joeyparrish.backpacker.vision

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Detects ready (cyan/blue) Pokéstop discs in a 720p-normalized map screenshot.
 *
 * Algorithm (see PLAN.md §3a):
 *   1. Convert RGBA → HSV
 *   2. Threshold with a cyan HSV mask
 *   3. Morphological close to fill gaps
 *   4. Find contours; filter by bounding-box height and area (width varies with disc rotation)
 *   5. Return centroids sorted by distance from screen centre
 *
 * The [Mat] passed to [detect] must be in RGBA format at 720p (as produced by ScreenshotService).
 * The caller retains ownership and must call [Mat.release] after [detect] returns.
 *
 * Intermediate Mats ([hsv], [mask], [morphed], [hierarchy], [morphKernel], [contourMask]) are
 * pre-allocated as instance fields and reused across calls to avoid per-scan JNI allocation
 * pressure, which matters in CAR mode (1 s scan interval).  Call [release] when the detector
 * is no longer needed.
 */
class PokestopDetector {

    // HSV lower/upper bounds for the cyan disc colour (OpenCV: H 0-180, S/V 0-255)
    // NOTE: Having a wider color range can result in more pixels being
    // included in a blob, which can result in a greater height or area
    // observed.
    private val hsvLower = Scalar( 85.0, 150.0, 185.0)
    private val hsvUpper = Scalar(105.0, 225.0, 255.0)

    // Min/max bounding-box height (VD px, i.e. 720-wide-normalised) for a valid disc contour.
    // minDiscHeight is intentionally low (≈54px at 2400 device height → 36 VD px) to catch stops
    // that render in "collapsed" style even when just inside spin range.  The spin-radius check
    // is the authoritative range gate; the height filter only rejects things too small/large to
    // plausibly be a stop disc.
    private val minDiscHeight = 36
    private val maxDiscHeight = 110

    private val morphKernelSize = Size(5.0, 5.0)

    // Pre-allocated scratch Mats — reused across detect() calls to avoid per-scan JNI allocs.
    private val hsv         = Mat()
    private val mask        = Mat()
    private val morphed     = Mat()
    private val hierarchy   = Mat()
    private val morphKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, morphKernelSize)
    // contourMask is lazy: sized to the first frame's dimensions and reused thereafter.
    private var contourMask = Mat()

    /** A single detection: moment centroid + bounding box, both in 720p-normalised space. */
    data class Disc(val centroid: PointF, val bounds: RectF)

    /**
     * @param passed         Contours that passed all filters, nearest-centre-first.
     * @param rejectedBounds Bounding boxes of contours that failed the height or area filter.
     */
    data class DetectionResult(
        val passed: List<Disc>,
        val rejectedBounds: List<RectF>
    )

    /**
     * Detect Pokéstop discs in [screenshot] (720p RGBA Mat from ScreenshotService).
     * Returns a [DetectionResult] with passing detections and all/rejected bounding boxes
     * (both in 720p-normalised space) for debug overlay rendering.
     * The caller retains ownership of [screenshot].
     */
    fun detect(screenshot: Mat): DetectionResult {
        val normWidth  = screenshot.cols()
        val normHeight = screenshot.rows()

        // screenshot is already at 720p RGBA — no scaling needed.
        Imgproc.cvtColor(screenshot, hsv, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)
        Core.inRange(hsv, hsvLower, hsvUpper, mask)
        Imgproc.morphologyEx(mask, morphed, Imgproc.MORPH_CLOSE, morphKernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            morphed, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        // Ensure contourMask is the right size for this frame (constant after first call at 720p).
        if (contourMask.rows() != normHeight || contourMask.cols() != normWidth) {
            contourMask.release()
            contourMask = Mat.zeros(normHeight, normWidth, CvType.CV_8UC1)
        }

        val screenCx = normWidth / 2f
        val screenCy = normHeight / 2f
        val detections = mutableListOf<Disc>()
        val rejectedBounds = mutableListOf<RectF>()

        try {
            for (contour in contours) {
                val bb: Rect = Imgproc.boundingRect(contour)
                val area = Imgproc.contourArea(contour)

                // Log all non-trivial contours so thresholds can be calibrated from logcat.
                if (area >= 50) {
                    // Measure mean HSV within this contour so we can calibrate the hue filter.
                    // Reuse contourMask: clear to zeros, draw this contour, measure mean, repeat.
                    contourMask.setTo(Scalar(0.0))
                    Imgproc.drawContours(contourMask, listOf(contour), 0, Scalar(255.0), -1)
                    val meanHsv = Core.mean(hsv, contourMask)
                    val mH = meanHsv.`val`[0].toInt()
                    val mS = meanHsv.`val`[1].toInt()
                    val mV = meanHsv.`val`[2].toInt()

                    val discCenterNx = (bb.x + bb.width  / 2f) / normWidth
                    val discCenterNy = (bb.y + bb.height / 2f) / normHeight
                    // Spin-radius check: X is normalised by width, Y by height.  The circle in
                    // pixel space becomes an ellipse in this non-square normalised space, so we
                    // use the ellipse membership formula: (dx/rx)² + (dy/ry)² ≤ 1.
                    val sdx = (discCenterNx - SPIN_CENTER_NX) / SPIN_RADIUS_NX
                    val sdy = (discCenterNy - SPIN_CENTER_NY) / SPIN_RADIUS_NY
                    val inSpinRadius = sdx * sdx + sdy * sdy <= 1.0f
                    val exclusionZone = EXCLUSION_ZONES.firstOrNull { it.contains(discCenterNx, discCenterNy) }
                    val verdict = when {
                        bb.height !in minDiscHeight..maxDiscHeight ->
                            "SKIP (h=${bb.height} not in $minDiscHeight..$maxDiscHeight)"
                        !inSpinRadius ->
                            "SKIP (centre %.2f,%.2f outside spin radius)".format(discCenterNx, discCenterNy)
                        exclusionZone != null ->
                            "SKIP (centre %.2f,%.2f in exclusion zone ${exclusionZone.label})".format(discCenterNx, discCenterNy)
                        else -> "PASS"
                    }
                    Log.d(TAG, "Contour @ (${bb.x},${bb.y}): " +
                            "h=${bb.height} w=${bb.width} area=${area.toInt()} " +
                            "meanHSV=($mH,$mS,$mV) " +
                            "→ $verdict")

                    val bounds = RectF(
                        bb.x.toFloat(), bb.y.toFloat(),
                        (bb.x + bb.width).toFloat(), (bb.y + bb.height).toFloat()
                    )

                    val passes = bb.height in minDiscHeight..maxDiscHeight
                            && inSpinRadius
                            && exclusionZone == null
                    if (!passes) {
                        rejectedBounds.add(bounds)
                    } else {
                        val M = Imgproc.moments(contour)
                        if (M.m00 > 0) {
                            val cx = (M.m10 / M.m00).toFloat()
                            val cy = (M.m01 / M.m00).toFloat()
                            detections.add(Disc(PointF(cx, cy), bounds))
                        }
                    }
                }
            }
        } finally {
            // Release all MatOfPoint native memory.  findContours() allocates these; we must
            // free them explicitly.  The finally block ensures release even if an exception
            // interrupts the loop mid-way.
            contours.forEach { it.release() }
        }

        detections.sortBy { d ->
            val dx = d.centroid.x - screenCx
            val dy = d.centroid.y - screenCy
            dx * dx + dy * dy
        }

        Log.d(TAG, "Detected ${detections.size} Pokéstop disc(s) (of ${contours.size} total contours)")
        return DetectionResult(detections, rejectedBounds)
    }

    /**
     * Produce a debug visualization of the last [detect] result.
     * Must be called before [screenshot] is released.
     * The [mask] field is populated by [detect] and reused here.
     *
     * Pixels that matched the cyan HSV range are shown in their original colour;
     * all other pixels are greyscaled.  Yellow boxes mark passing contours,
     * red boxes mark rejected ones.
     */
    fun visualize(screenshot: Mat, result: DetectionResult): Bitmap {
        val gray1 = Mat()
        val gray4 = Mat()
        Imgproc.cvtColor(screenshot, gray1, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(gray1, gray4, Imgproc.COLOR_GRAY2RGBA)
        gray1.release()

        // Start with a fully-greyscale copy, then paint colour back where the mask is set.
        val viz = gray4.clone()
        gray4.release()
        screenshot.copyTo(viz, mask)

        val w = viz.cols()
        val h = viz.rows()
        val thickness = 3

        // Draw the spin-radius ellipse in green.
        Imgproc.ellipse(
            viz,
            Point((SPIN_CENTER_NX * w).toDouble(), (SPIN_CENTER_NY * h).toDouble()),
            org.opencv.core.Size((SPIN_RADIUS_NX * w).toDouble(), (SPIN_RADIUS_NY * h).toDouble()),
            0.0, 0.0, 360.0,
            Scalar(0.0, 255.0, 0.0, 255.0), thickness
        )

        for (disc in result.passed) {
            Imgproc.rectangle(
                viz,
                Point(disc.bounds.left.toDouble(), disc.bounds.top.toDouble()),
                Point(disc.bounds.right.toDouble(), disc.bounds.bottom.toDouble()),
                Scalar(255.0, 255.0, 0.0, 255.0), thickness
            )
        }
        for (bounds in result.rejectedBounds) {
            Imgproc.rectangle(
                viz,
                Point(bounds.left.toDouble(), bounds.top.toDouble()),
                Point(bounds.right.toDouble(), bounds.bottom.toDouble()),
                Scalar(255.0, 0.0, 0.0, 255.0), thickness
            )
        }

        val bitmap = Bitmap.createBitmap(viz.cols(), viz.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(viz, bitmap)
        viz.release()
        return bitmap
    }

    /** Release all pre-allocated Mats. Call when the detector is no longer needed. */
    fun release() {
        hsv.release()
        mask.release()
        morphed.release()
        hierarchy.release()
        morphKernel.release()
        contourMask.release()
    }

    companion object {
        private const val TAG = "Backpacker.PokestopDetector"

        private data class ExclusionZone(
            val label: String,
            val x1: Float, val y1: Float,
            val x2: Float, val y2: Float
        ) {
            fun contains(nx: Float, ny: Float) = nx in x1..x2 && ny in y1..y2
        }

        // Spin radius: character position and radius in natural normalised coords
        // (X by width, Y by height).  The circle in pixel space is expressed as an
        // ellipse in this non-square space; use (dx/rx)²+(dy/ry)²≤1 to test membership.
        // Measured from a 1080×2400 screenshot: character at (540,1520), spin radius top at Y=1080.
        // The detected disc centroid sits ~118px above the physical base of the stop (pole offset),
        // so the radius is expanded by 118px to avoid excluding stops whose base is in range.
        private const val SPIN_CENTER_NX = 0.500f   //  540 / 1080
        private const val SPIN_CENTER_NY = 0.633f   // 1520 / 2400
        private const val SPIN_RADIUS_NX = 0.516f   //  558 / 1080  (440 + 118)
        private const val SPIN_RADIUS_NY = 0.233f   //  558 / 2400  (440 + 118)

        // Normalised (0–1) rectangles covering PoGO UI elements that share Pokéstop cyan.
        // Measured from a 1080×2400 screenshot.
        private val EXCLUSION_ZONES = listOf(
            ExclusionZone("top-right shortcuts",    0.856f, 0.000f, 1.000f, 0.292f),
            ExclusionZone("bottom-left avatar",     0.000f, 0.870f, 0.308f, 1.000f),
            ExclusionZone("bottom-center pokeball", 0.364f, 0.903f, 0.636f, 1.000f),
            ExclusionZone("bottom-right nearby",    0.694f, 0.910f, 1.000f, 1.000f),
            ExclusionZone("above-bottom-right",     0.861f, 0.850f, 1.000f, 0.921f)
        )
    }
}
