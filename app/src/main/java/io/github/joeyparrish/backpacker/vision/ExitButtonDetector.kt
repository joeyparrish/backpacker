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
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Detects the bottom-centre exit button used across multiple PoGO screens (gym, menu,
 * egg viewer, Pokémon viewer, etc.) and returns its centre as a tap target.
 *
 * The button is always a circle of fixed size at a fixed position, so detection uses
 * a pre-built circular mask rather than contour search.  Two colour variants exist:
 *
 *   White variant  — white fill, aqua outline + X icon (gym / raid / battle screens).
 *   Green variant  — aqua fill, yellow-green icon (menus, egg viewer, Pokémon viewer).
 *
 * Detection strategy:
 *   1. Build a filled circular mask at the known button position once per frame size.
 *   2. Convert RGBA → HSV.
 *   3. Measure the fraction of circle pixels that match the white range (low S, high V).
 *   4. Measure the fraction that match the aqua range.
 *   5. If either fraction exceeds [DETECT_THRESHOLD], return the button centre.
 *
 * Checked before [PassengerDetector] in the scan loop: the buddy screen shows both a
 * large "play" button (which PassengerDetector matches) and this exit button, so exit
 * detection must take priority.
 *
 * Pre-allocated Mats are reused across calls.  Call [release] when done.
 */
class ExitButtonDetector {

    /** HSV range for the white button fill (white variant). */
    private val whiteHsvLower = Scalar(  0.0,   0.0, 250.0)
    private val whiteHsvUpper = Scalar(180.0,   5.0, 255.0)

    /**
     * HSV range for the aqua colour shared by both variants:
     * the outline + X of the white button, and the background fill of the green button.
     * Measured: white-variant outline H=186/360, S=75/100, V=58/100;
     *           green-variant fill    H=188/360, S=80/100, V=59/100 — effectively identical.
     */
    private val aquaHsvLower  = Scalar( 91.0, 186.0, 143.0)
    private val aquaHsvUpper  = Scalar( 96.0, 209.0, 155.0)

    // Pre-allocated scratch Mats.
    private val hsv           = Mat()
    private val colorMask     = Mat()
    private val whiteCombined = Mat()
    private val aquaCombined  = Mat()
    private val combined      = Mat()   // OR of both; read by visualize()

    // buttonMask is built once from the first frame's dimensions and reused thereafter.
    private var buttonMask   = Mat()
    private var buttonPixels = 0f

    // Last-computed ratios from detect(); read by visualize() for the text overlay.
    private var lastWhiteRatio = 0f
    private var lastAquaRatio  = 0f

    /**
     * Detect the exit button in [screenshot] (720p RGBA Mat from ScreenshotService).
     * Returns the button centre in 720p-normalised space, or null if not found.
     * The caller retains ownership of [screenshot].
     */
    fun detect(screenshot: Mat): PointF? {
        val w = screenshot.cols()
        val h = screenshot.rows()

        if (buttonMask.rows() != h || buttonMask.cols() != w) {
            buttonMask.release()
            buttonMask = Mat.zeros(h, w, CvType.CV_8UC1)
            val cx = BUTTON_CENTER_NX * w
            val cy = BUTTON_CENTER_NY * h
            val r  = (BUTTON_RADIUS_NX * w).toInt()
            Imgproc.circle(buttonMask, Point(cx.toDouble(), cy.toDouble()), r, Scalar(255.0), -1)
            buttonPixels = Core.countNonZero(buttonMask).toFloat()
            Log.d(TAG, "Button mask built: center=(${cx.toInt()},${cy.toInt()}) r=$r pixels=${buttonPixels.toInt()}")
        }

        Imgproc.cvtColor(screenshot, hsv, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)

        Core.inRange(hsv, whiteHsvLower, whiteHsvUpper, colorMask)
        Core.bitwise_and(colorMask, buttonMask, whiteCombined)
        lastWhiteRatio = Core.countNonZero(whiteCombined) / buttonPixels

        Core.inRange(hsv, aquaHsvLower, aquaHsvUpper, colorMask)
        Core.bitwise_and(colorMask, buttonMask, aquaCombined)
        lastAquaRatio = Core.countNonZero(aquaCombined) / buttonPixels

        // Merge for visualize() regardless of outcome.
        Core.bitwise_or(whiteCombined, aquaCombined, combined)

        Log.d(TAG, "White ratio: $lastWhiteRatio  Aqua ratio: $lastAquaRatio")

        val found = lastWhiteRatio >= DETECT_THRESHOLD || lastAquaRatio >= DETECT_THRESHOLD
        if (found) {
            Log.i(TAG, "Exit button detected (white=%.1f%%  aqua=%.1f%%)".format(
                lastWhiteRatio * 100f, lastAquaRatio * 100f))
        }
        return if (found) PointF(BUTTON_CENTER_NX * w, BUTTON_CENTER_NY * h) else null
    }

    /**
     * Produce a debug visualization of the last [detect] call.
     * Must be called before [screenshot] is released.
     *
     * Pixels within the button circle that matched either the white or aqua range are
     * shown in their original colour; all other pixels are greyscaled.  The circle
     * outline is drawn in yellow if the button was found, red otherwise.
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

        if (!combined.empty()) {
            screenshot.copyTo(viz, combined)
        }

        if (!buttonMask.empty()) {
            val cx = BUTTON_CENTER_NX * w
            val cy = BUTTON_CENTER_NY * h
            val r  = (BUTTON_RADIUS_NX * w).toInt()
            val outlineColor = if (result != null)
                Scalar(255.0, 255.0,   0.0, 255.0)   // yellow — found
            else
                Scalar(255.0,   0.0,   0.0, 255.0)   // red — not found
            Imgproc.circle(viz, Point(cx.toDouble(), cy.toDouble()), r, outlineColor, 3)
        }

        val text = "white=%.1f%%  aqua=%.1f%%".format(lastWhiteRatio * 100f, lastAquaRatio * 100f)
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
        hsv.release()
        colorMask.release()
        whiteCombined.release()
        aquaCombined.release()
        combined.release()
        buttonMask.release()
    }

    companion object {
        private const val TAG = "Backpacker.ExitButtonDetector"

        // Button geometry in normalised coords (measured from a 1080×2400 screenshot).
        // Centre (540, 2259), diameter 104 px.
        private const val BUTTON_CENTER_NX = 0.500f   //  540 / 1080
        private const val BUTTON_CENTER_NY = 0.941f   // 2259 / 2400
        private const val BUTTON_RADIUS_NX = 0.048f   //   52 / 1080

        // Minimum fraction of circle pixels matching a colour to report a button present.
        // White variant: ~60-70% white fill.  Green variant: ~60-70% aqua fill.
        private const val DETECT_THRESHOLD = 0.40f
    }
}
