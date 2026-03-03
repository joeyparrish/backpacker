package io.github.joeyparrish.backpacker.vision

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import io.github.joeyparrish.backpacker.util.BitmapUtils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
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
 *   4. Find contours; filter by bounding-box HEIGHT only (width varies with disc rotation)
 *   5. Return centroids sorted by distance from screen centre
 *
 * All threshold constants are initial guesses — calibrate against real screenshots.
 */
class PokestopDetector {

    // HSV lower/upper bounds for the cyan disc colour (OpenCV: H 0-180, S/V 0-255)
    private val hsvLower = Scalar(90.0, 150.0, 150.0)
    private val hsvUpper = Scalar(102.0, 255.0, 255.0)

    /** Min bounding-box height (720p px) for a valid disc contour. Calibrate from screenshots. */
    private val minDiscHeight = 80

    /** Max bounding-box height (720p px). Discard tall UI chrome. */
    private val maxDiscHeight = 110

    /** Min contour area (720p px²). Filters small noise that passes the height check. */
    private val minArea = 1000

    private val morphKernelSize = Size(5.0, 5.0)

    /** A single detection: moment centroid + bounding box, both in 720p-normalised space. */
    data class Disc(val centroid: PointF, val bounds: RectF)

    /**
     * Detect Pokéstop disc centroids in [screenshot].
     * Returns a list of [Disc] (centroid + bounding box) in 720p-normalised space,
     * nearest-centre-first.
     */
    fun detect(screenshot: Bitmap): List<Disc> {
        val scaled = BitmapUtils.scaleTo720p(screenshot)
        val normWidth = scaled.width
        val normHeight = scaled.height

        val rgba = BitmapUtils.bitmapToMat(scaled)
        val hsv = Mat()
        val mask = Mat()
        val morphed = Mat()

        try {
            Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)
            Core.inRange(hsv, hsvLower, hsvUpper, mask)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, morphKernelSize)
            Imgproc.morphologyEx(mask, morphed, Imgproc.MORPH_CLOSE, kernel)

            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                morphed, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            hierarchy.release()

            val screenCx = normWidth / 2f
            val screenCy = normHeight / 2f
            val detections = mutableListOf<Disc>()

            for (contour in contours) {
                val bb: Rect = Imgproc.boundingRect(contour)
                val area = Imgproc.contourArea(contour)

                // Log all non-trivial contours so thresholds can be calibrated from logcat.
                if (area >= 50) {
                    // Measure mean HSV within this contour so we can calibrate the hue filter.
                    val contourMask = Mat.zeros(normHeight, normWidth, CvType.CV_8UC1)
                    Imgproc.drawContours(contourMask, listOf(contour), 0, Scalar(255.0), -1)
                    val meanHsv = Core.mean(hsv, contourMask)
                    contourMask.release()
                    val mH = meanHsv.`val`[0].toInt()
                    val mS = meanHsv.`val`[1].toInt()
                    val mV = meanHsv.`val`[2].toInt()

                    val verdict = when {
                        bb.height !in minDiscHeight..maxDiscHeight ->
                            "SKIP (h=${bb.height} not in $minDiscHeight..$maxDiscHeight)"
                        area < minArea ->
                            "SKIP (area=${area.toInt()} < $minArea)"
                        else -> "PASS"
                    }
                    Log.d(TAG, "Contour @ (${bb.x},${bb.y}): " +
                            "h=${bb.height} w=${bb.width} area=${area.toInt()} " +
                            "meanHSV=($mH,$mS,$mV) " +
                            "[H filter: ${hsvLower.`val`[0].toInt()}..${hsvUpper.`val`[0].toInt()}] " +
                            "→ $verdict")
                }

                if (bb.height in minDiscHeight..maxDiscHeight && area >= minArea) {
                    val M = Imgproc.moments(contour)
                    if (M.m00 > 0) {
                        val cx = (M.m10 / M.m00).toFloat()
                        val cy = (M.m01 / M.m00).toFloat()
                        val bounds = RectF(
                            bb.x.toFloat(), bb.y.toFloat(),
                            (bb.x + bb.width).toFloat(), (bb.y + bb.height).toFloat()
                        )
                        detections.add(Disc(PointF(cx, cy), bounds))
                    }
                }
                contour.release()
            }

            detections.sortBy { d ->
                val dx = d.centroid.x - screenCx
                val dy = d.centroid.y - screenCy
                dx * dx + dy * dy
            }

            Log.d(TAG, "Detected ${detections.size} Pokéstop disc(s) (of ${contours.size} total contours)")
            return detections

        } finally {
            rgba.release()
            hsv.release()
            mask.release()
            morphed.release()
        }
    }

    companion object {
        private const val TAG = "Backpacker.PokestopDetector"
    }
}
