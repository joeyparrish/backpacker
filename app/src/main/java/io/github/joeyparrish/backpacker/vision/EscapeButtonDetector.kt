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
 * button's own alpha channel (loaded from res/raw/escape_icon.png) as the pixel mask.
 *
 * Two conditions must both be satisfied:
 *   1. A high fraction of the icon-mask pixels are white (low S, high V) — the icon.
 *   2. A low fraction of the bounding-box surround pixels (bounding box minus icon
 *      shape) are white — the background.  This blocks the white-background false
 *      positive: on the encounter screen the surround is blue-grey; on a plain white
 *      surface it is also white and fails this check.
 *
 * The icon and surround masks are rebuilt whenever the frame dimensions change.
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

    // Icon mask and its inverse-within-bbox, placed on a full frame — rebuilt on size change.
    private var iconMask    = Mat()
    private var surroundMask = Mat()
    private var iconPixels    = 0f
    private var surroundPixels = 0f

    // Pre-allocated scratch Mats.
    private val hsv       = Mat()
    private val colorMask = Mat()
    private val masked    = Mat()

    // Last-computed ratios from detect(); used by visualize().
    private var lastIconRatio    = 0f
    private var lastSurroundRatio = 0f

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
            surroundMask.release()
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

            // Surround mask = bounding box rectangle minus the icon shape.
            // Used to reject white-background false positives: on the encounter
            // screen these pixels are blue-grey; on a plain white surface they
            // are also white, making the surround ratio spike to ~100%.
            val bboxMask = Mat.zeros(h, w, CvType.CV_8UC1)
            Imgproc.rectangle(bboxMask,
                Point(x.toDouble(), y.toDouble()),
                Point((x + scaledW).toDouble(), (y + scaledH).toDouble()),
                Scalar(255.0), -1)
            val invertedIcon = Mat()
            Core.bitwise_not(iconMask, invertedIcon)
            surroundMask = Mat()
            Core.bitwise_and(invertedIcon, bboxMask, surroundMask)
            invertedIcon.release()
            bboxMask.release()
            surroundPixels = Core.countNonZero(surroundMask).toFloat()

            Log.d(TAG, "Masks built at (${x},${y}) ${scaledW}×${scaledH}: " +
                "icon=${iconPixels.toInt()} surround=${surroundPixels.toInt()}")
        }

        Imgproc.cvtColor(screenshot, hsv, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)

        Core.inRange(hsv, whiteHsvLower, whiteHsvUpper, colorMask)

        Core.bitwise_and(colorMask, iconMask, masked)
        lastIconRatio = Core.countNonZero(masked) / iconPixels

        Core.bitwise_and(colorMask, surroundMask, masked)
        lastSurroundRatio = Core.countNonZero(masked) / surroundPixels

        Log.d(TAG, "Escape button: icon=%.1f%%  surround=%.1f%%"
            .format(lastIconRatio * 100f, lastSurroundRatio * 100f))

        val found = lastIconRatio >= ICON_THRESHOLD && lastSurroundRatio <= SURROUND_MAX
        if (found) {
            Log.i(TAG, "Escape button detected (icon=%.1f%%  surround=%.1f%%)"
                .format(lastIconRatio * 100f, lastSurroundRatio * 100f))
        }
        return if (found) PointF(ICON_CENTER_NX * w, ICON_CENTER_NY * h) else null
    }

    /**
     * Produce a debug visualization of the last [detect] call.
     * Must be called before [screenshot] is released.
     *
     * Icon-mask pixels are shown in their original colour; surround-mask pixels are
     * tinted blue; everything else is greyscaled.  The bounding rectangle is drawn in
     * yellow (found) or red (not found).  Both ratios are shown as a text overlay.
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
            // Icon pixels in original colour.
            screenshot.copyTo(viz, iconMask)
            // Surround pixels tinted blue so the two regions are distinguishable.
            val blue = Mat(h, w, viz.type(), Scalar(0.0, 0.0, 255.0, 255.0))
            blue.copyTo(viz, surroundMask)
            blue.release()

            val x = (ICON_LEFT_NX * w).toInt()
            val y = (ICON_TOP_NY  * h).toInt()
            val r = ((ICON_LEFT_NX + ICON_NW) * w).toInt()
            val b = ((ICON_TOP_NY  + ICON_NH) * h).toInt()
            val color = if (result != null)
                Scalar(255.0, 255.0, 0.0, 255.0)   // yellow — found
            else
                Scalar(255.0, 0.0, 0.0, 255.0)     // red — not found
            Imgproc.rectangle(viz, Point(x.toDouble(), y.toDouble()),
                Point(r.toDouble(), b.toDouble()), color, 3)
        }

        val text = "icon=%.1f%%  surround=%.1f%%"
            .format(lastIconRatio * 100f, lastSurroundRatio * 100f)
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
        surroundMask.release()
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

        // Minimum fraction of icon-mask pixels that must be white (the running figure).
        private const val ICON_THRESHOLD  = 0.40f
        // Maximum fraction of surround-mask pixels that may be white (the background).
        // Plain white background gives ~100%; encounter screen gives ~0% (blue-grey).
        // Calibrate from debug output; start permissive.
        private const val SURROUND_MAX    = 0.30f
    }
}
