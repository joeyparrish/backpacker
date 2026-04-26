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
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Detects the top-left "run away" escape button shown during a Pokémon encounter.
 *
 * The button is a white running-figure icon at a fixed position.  Detection uses the
 * button's own alpha channel (loaded from res/raw/escape_icon.png) as the pixel mask,
 * so only the exact icon shape is tested rather than a bounding rectangle.  This avoids
 * false positives from unrelated bright elements elsewhere in the encounter screen.
 *
 * Detection strategy:
 *   1. On first use, load the icon PNG, threshold its alpha channel to a binary mask,
 *      and rescale it to the current frame dimensions.
 *   2. Convert RGBA → HSV.
 *   3. Check what fraction of the icon-mask pixels are white (low S, high V).
 *   4. If that fraction exceeds [DETECT_THRESHOLD], return the button centre.
 *
 * The icon mask is rebuilt whenever the frame dimensions change.
 *
 * Pre-allocated Mats are reused across calls.  Call [release] when done.
 */
class EscapeButtonDetector(context: Context) {

    /**
     * HSV range for the white icon.  Same range as the white exit button — the icon
     * can render slightly off-white against the encounter overlay.
     */
    private val whiteHsvLower = Scalar(  0.0,   0.0, 200.0)
    private val whiteHsvUpper = Scalar(180.0,  40.0, 255.0)

    // The icon's alpha channel thresholded to binary, at the source PNG resolution.
    private val iconAlpha: Mat

    // Icon mask placed on a full frame at the correct position — rebuilt on size change.
    private var iconMask   = Mat()
    private var iconPixels = 0f

    // Pre-allocated scratch Mats.
    private val hsv       = Mat()
    private val colorMask = Mat()
    private val masked    = Mat()

    // Last-computed ratio from detect(); used by visualize().
    private var lastRatio = 0f

    init {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeResource(context.resources, R.raw.escape_icon, opts)
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        bitmap.recycle()

        // Extract the alpha channel as the icon shape mask.
        val channels = mutableListOf<Mat>()
        Core.split(rgba, channels)
        iconAlpha = channels[3]
        Imgproc.threshold(iconAlpha, iconAlpha, 128.0, 255.0, Imgproc.THRESH_BINARY)
        channels.forEachIndexed { i, m -> if (i != 3) m.release() }
        rgba.release()

        Log.d(TAG, "Icon loaded: ${iconAlpha.cols()}×${iconAlpha.rows()} " +
            "icon pixels=${Core.countNonZero(iconAlpha)}")
    }

    /**
     * Detect the escape button in [screenshot] (720p RGBA Mat from ScreenshotService).
     * Returns the button centre in 720p-normalised space, or null if not found.
     * The caller retains ownership of [screenshot].
     */
    fun detect(screenshot: Mat): PointF? {
        val w = screenshot.cols()
        val h = screenshot.rows()

        if (iconMask.rows() != h || iconMask.cols() != w) {
            iconMask.release()
            iconMask = Mat.zeros(h, w, CvType.CV_8UC1)

            val scaledW = (ICON_NW * w).toInt()
            val scaledH = (ICON_NH * h).toInt()
            val scaled  = Mat()
            Imgproc.resize(iconAlpha, scaled, Size(scaledW.toDouble(), scaledH.toDouble()),
                0.0, 0.0, Imgproc.INTER_AREA)
            // Re-threshold after resize to keep the mask binary.
            Imgproc.threshold(scaled, scaled, 128.0, 255.0, Imgproc.THRESH_BINARY)

            val x = (ICON_LEFT_NX * w).toInt()
            val y = (ICON_TOP_NY  * h).toInt()
            val dstW = minOf(scaledW, w - x)
            val dstH = minOf(scaledH, h - y)
            scaled.submat(0, dstH, 0, dstW).copyTo(iconMask.submat(y, y + dstH, x, x + dstW))
            scaled.release()

            iconPixels = Core.countNonZero(iconMask).toFloat()
            Log.d(TAG, "Icon mask placed at (${x},${y}) ${scaledW}×${scaledH}: pixels=${iconPixels.toInt()}")
        }

        Imgproc.cvtColor(screenshot, hsv, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)

        Core.inRange(hsv, whiteHsvLower, whiteHsvUpper, colorMask)
        Core.bitwise_and(colorMask, iconMask, masked)
        lastRatio = Core.countNonZero(masked) / iconPixels

        Log.d(TAG, "Escape button ratio: $lastRatio")

        val found = lastRatio >= DETECT_THRESHOLD
        if (found) {
            Log.i(TAG, "Escape button detected (ratio=%.1f%%)".format(lastRatio * 100f))
        }
        return if (found) PointF(ICON_CENTER_NX * w, ICON_CENTER_NY * h) else null
    }

    /**
     * Produce a debug visualization of the last [detect] call.
     * Must be called before [screenshot] is released.
     *
     * Pixels inside the icon mask are shown in their original colour; everything else
     * is greyscaled.  A bounding rectangle is drawn in yellow (found) or red (not found).
     * The white ratio is shown as a text overlay.
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

        if (!iconMask.empty()) {
            screenshot.copyTo(viz, iconMask)

            val x = (ICON_LEFT_NX * w).toInt()
            val y = (ICON_TOP_NY  * h).toInt()
            val r = (ICON_LEFT_NX * w + ICON_NW * w).toInt()
            val b = (ICON_TOP_NY  * h + ICON_NH * h).toInt()
            val color = if (result != null)
                Scalar(255.0, 255.0, 0.0, 255.0)   // yellow — found
            else
                Scalar(255.0, 0.0, 0.0, 255.0)     // red — not found
            Imgproc.rectangle(viz, Point(x.toDouble(), y.toDouble()),
                Point(r.toDouble(), b.toDouble()), color, 3)
        }

        val text = "escape=%.1f%%".format(lastRatio * 100f)
        val fontFace  = Imgproc.FONT_HERSHEY_SIMPLEX
        val fontScale = 1.2
        val fontThick = 2
        val baseLine  = IntArray(1)
        val textSize  = Imgproc.getTextSize(text, fontFace, fontScale, fontThick, baseLine)
        val pad    = 12
        val textX  = pad
        val textY  = h - pad - baseLine[0]
        val boxTop = h - textSize.height.toInt() - baseLine[0] - pad * 2
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

    /** Release all pre-allocated Mats.  Call when the detector is no longer needed. */
    fun release() {
        iconAlpha.release()
        iconMask.release()
        hsv.release()
        colorMask.release()
        masked.release()
    }

    companion object {
        private const val TAG = "Backpacker.EscapeButtonDetector"

        // Button position in normalised coords (extracted from a 1080×2400 screenshot).
        // Region: x=65, y=117, size=92×86.
        private const val ICON_LEFT_NX   =  65f / 1080f  // 0.0602
        private const val ICON_TOP_NY    = 117f / 2400f  // 0.0488
        private const val ICON_NW        =  92f / 1080f  // 0.0852  (width  fraction of screen width)
        private const val ICON_NH        =  86f / 2400f  // 0.0358  (height fraction of screen height)
        private const val ICON_CENTER_NX = (65f + 46f) / 1080f  // 0.1028
        private const val ICON_CENTER_NY = (117f + 43f) / 2400f // 0.0667

        // Minimum fraction of icon pixels that must be white to report the button present.
        // Start conservative; calibrate from debug output.
        private const val DETECT_THRESHOLD = 0.40f
    }
}
