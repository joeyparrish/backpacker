// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

package io.github.joeyparrish.backpacker.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.util.Log
import io.github.joeyparrish.backpacker.R
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Detects the top-left "run away" escape button shown during a Pokémon encounter.
 *
 * The button is a white running-figure icon against a dark drop shadow and a blue-grey
 * background.  Simple white-pixel counting does not work: any white region at the button
 * position (e.g. a plain white test background) gives a high ratio even with no icon.
 *
 * Instead, detection is edge-based:
 *   1. At initialisation, load the reference image (res/raw/escape_icon.png — the
 *      original, unmodified crop that includes the shadow and background), convert to
 *      greyscale, and compute Canny edges.  This captures the distinctive outline of
 *      the running figure where white meets dark.
 *   2. Rescale the edge template once per frame-size change.
 *   3. In detect(), extract a slightly padded greyscale ROI from the screenshot at
 *      the known button position, compute its Canny edges, and run
 *      matchTemplate(TM_CCOEFF_NORMED) to score the shape match.
 *   4. Return the button centre if the best match score exceeds [MATCH_THRESHOLD].
 *
 * On a plain white background the ROI has no edges, giving a near-zero score.
 * On the encounter screen the icon outline produces a strong match.
 *
 * Pre-allocated Mats are reused across calls.  Call [release] when done.
 */
class EscapeButtonDetector(context: Context) {

    // Reference grayscale image at source PNG resolution — kept for rescaling.
    // Canny is recomputed after scaling so edge detection runs at the correct
    // output resolution rather than on a blurred-and-thresholded edge image.
    private val templateGray: Mat

    // Scaled edge template for the current frame size — rebuilt on size change.
    private var scaledEdges = Mat()
    private var scaledW     = 0
    private var scaledH     = 0

    // Pre-allocated scratch Mats.
    private val grayFrame = Mat()
    private val roiEdges  = Mat()
    private val matchResult = Mat()

    // Last score from detect(); used by visualize().
    private var lastScore = 0f

    init {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeResource(context.resources, R.raw.escape_icon, opts)
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        bitmap.recycle()

        templateGray = Mat()
        Imgproc.cvtColor(rgba, templateGray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()

        Log.d(TAG, "Template loaded: ${templateGray.cols()}×${templateGray.rows()}")
    }

    /**
     * Detect the escape button in [screenshot] (720p RGBA Mat from ScreenshotService).
     * Returns the button centre in 720p-normalised space, or null if not found.
     * The caller retains ownership of [screenshot].
     */
    fun detect(screenshot: Mat): PointF? {
        val w = screenshot.cols()
        val h = screenshot.rows()

        val tW = (ICON_NW * w).toInt()
        val tH = (ICON_NH * h).toInt()
        if (scaledW != tW || scaledH != tH) {
            // Scale the grayscale source first, then apply Canny at the target
            // resolution.  Scaling an already-computed edge image blurs the
            // single-pixel edges to sub-threshold values; recomputing at the
            // correct size preserves them.
            val grayResized = Mat()
            Imgproc.resize(templateGray, grayResized,
                Size(tW.toDouble(), tH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.Canny(grayResized, scaledEdges,
                CANNY_LOW.toDouble(), CANNY_HIGH.toDouble())
            grayResized.release()
            scaledW = tW
            scaledH = tH
            Log.d(TAG, "Scaled template: ${tW}×${tH}  edges=${Core.countNonZero(scaledEdges)}")
        }

        // Extract a padded ROI so matchTemplate has a small search window and the
        // result is not degenerate (source must be >= template size).
        val x0 = maxOf(0, (ICON_LEFT_NX * w).toInt() - SEARCH_PAD)
        val y0 = maxOf(0, (ICON_TOP_NY  * h).toInt() - SEARCH_PAD)
        val x1 = minOf(w, x0 + tW + SEARCH_PAD * 2)
        val y1 = minOf(h, y0 + tH + SEARCH_PAD * 2)

        Imgproc.cvtColor(screenshot, grayFrame, Imgproc.COLOR_RGBA2GRAY)
        val roi = grayFrame.submat(y0, y1, x0, x1)
        Imgproc.Canny(roi, roiEdges, CANNY_LOW.toDouble(), CANNY_HIGH.toDouble())

        Imgproc.matchTemplate(roiEdges, scaledEdges, matchResult, Imgproc.TM_CCOEFF_NORMED)

        val mm = Core.minMaxLoc(matchResult)
        lastScore = mm.maxVal.toFloat()

        Log.d(TAG, "Escape button score: $lastScore")

        val found = lastScore >= MATCH_THRESHOLD
        if (found) {
            Log.i(TAG, "Escape button detected (score=%.3f)".format(lastScore))
        }
        return if (found) PointF(ICON_CENTER_NX * w, ICON_CENTER_NY * h) else null
    }

    /**
     * Produce a debug visualization of the last [detect] call.
     * Must be called before [screenshot] is released.
     *
     * The screenshot is greyscaled.  Within the icon bounding box the Canny edges
     * detected in the screenshot are drawn in blue, the scaled reference edges are
     * drawn in yellow, and pixels where both agree are drawn in green, so alignment
     * is immediately visible.  The bounding box is outlined in yellow (found) or
     * red (not found).  The match score is shown as text.
     */
    fun visualize(screenshot: Mat, result: PointF?): Bitmap {
        val w = screenshot.cols()
        val h = screenshot.rows()

        val gray1 = Mat()
        val gray4 = Mat()
        Imgproc.cvtColor(screenshot, gray1, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(gray1, gray4, Imgproc.COLOR_GRAY2RGBA)
        gray1.release()
        val viz = gray4.clone()
        gray4.release()

        if (scaledW > 0) {
            val x = (ICON_LEFT_NX * w).toInt()
            val y = (ICON_TOP_NY  * h).toInt()

            // Screenshot edges in cyan within the icon region.
            Imgproc.cvtColor(screenshot, grayFrame, Imgproc.COLOR_RGBA2GRAY)
            val roi = grayFrame.submat(y, minOf(h, y + scaledH), x, minOf(w, x + scaledW))
            Imgproc.Canny(roi, roiEdges, CANNY_LOW.toDouble(), CANNY_HIGH.toDouble())

            val vizRoi = viz.submat(y, minOf(h, y + scaledH), x, minOf(w, x + scaledW))
            val overlap = Mat()
            Core.bitwise_and(roiEdges, scaledEdges, overlap)
            val blue   = Mat(vizRoi.size(), vizRoi.type(), Scalar(  0.0,   0.0, 255.0, 255.0))
            val yellow = Mat(vizRoi.size(), vizRoi.type(), Scalar(255.0, 255.0,   0.0, 255.0))
            val green  = Mat(vizRoi.size(), vizRoi.type(), Scalar(  0.0, 255.0,   0.0, 255.0))
            // Blue = screenshot edges only; yellow = template edges only; green = overlap.
            blue.copyTo(vizRoi, roiEdges)
            yellow.copyTo(vizRoi, scaledEdges)
            green.copyTo(vizRoi, overlap)
            overlap.release()
            blue.release()
            yellow.release()
            green.release()
            vizRoi.release()

            val boxColor = if (result != null)
                Scalar(255.0, 255.0, 0.0, 255.0) else Scalar(255.0, 0.0, 0.0, 255.0)
            Imgproc.rectangle(viz,
                Point(x.toDouble(), y.toDouble()),
                Point((x + scaledW).toDouble(), (y + scaledH).toDouble()),
                boxColor, 3)
        }

        val text = "score=%.3f".format(lastScore)
        val fontFace  = Imgproc.FONT_HERSHEY_SIMPLEX
        val fontScale = 1.2
        val fontThick = 2
        val baseLine  = IntArray(1)
        val textSize  = Imgproc.getTextSize(text, fontFace, fontScale, fontThick, baseLine)
        val pad       = 12
        val textY     = h - pad - baseLine[0]
        val boxTop    = h - textSize.height.toInt() - baseLine[0] - pad * 2
        Imgproc.rectangle(viz,
            Point(0.0, boxTop.toDouble()),
            Point((textSize.width + pad * 2).toDouble(), h.toDouble()),
            Scalar(0.0, 0.0, 0.0, 200.0), -1)
        Imgproc.putText(viz, text,
            Point(pad.toDouble(), textY.toDouble()),
            fontFace, fontScale, Scalar(255.0, 255.0, 255.0, 255.0), fontThick)

        val bitmap = Bitmap.createBitmap(viz.cols(), viz.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(viz, bitmap)
        viz.release()
        return bitmap
    }

    /** Release all pre-allocated Mats.  Call when the detector is no longer needed. */
    fun release() {
        templateGray.release()
        scaledEdges.release()
        grayFrame.release()
        roiEdges.release()
        matchResult.release()
    }

    companion object {
        private const val TAG = "Backpacker.EscapeButtonDetector"

        // Button position in normalised coords (extracted from a 1080×2400 screenshot).
        // Region: x=65, y=117, size=92×86.
        private const val ICON_LEFT_NX   =  65f / 1080f  // 0.0602
        private const val ICON_TOP_NY    = 117f / 2400f  // 0.0488
        private const val ICON_NW        =  92f / 1080f  // 0.0852
        private const val ICON_NH        =  86f / 2400f  // 0.0358
        private const val ICON_CENTER_NX = (65f + 46f) / 1080f  // 0.1028
        private const val ICON_CENTER_NY = (117f + 43f) / 2400f // 0.0667

        // Canny edge detection thresholds.  Applied to both template and screenshot ROI.
        private const val CANNY_LOW  = 50
        private const val CANNY_HIGH = 150

        // Half-width of the matchTemplate search window around the expected position.
        private const val SEARCH_PAD = 6

        // Minimum TM_CCOEFF_NORMED score to report the button present.
        // Range is [-1, 1]; a uniform/white region scores near 0.  Calibrate from debug output.
        // Observed: negatives 0.000/0.016/0.052/-0.016; positives 0.457/0.485/0.497/0.516.
        // Large gap between ~0.052 and ~0.457 — threshold set in the middle.
        private const val MATCH_THRESHOLD = 0.4f
    }
}
