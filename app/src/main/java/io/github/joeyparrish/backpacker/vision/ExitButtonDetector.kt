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
 * The button is always a circle of fixed size, but its Y position varies by screen type:
 *
 *   Standard position (NY=0.941) — gym, spinner screen, most views.
 *   Menu position    (NY=0.929) — main menu, bag, egg viewer, Pokémon viewer.
 *
 * Two colour variants exist:
 *
 *   White variant  — white fill, aqua outline + X icon (gym / raid / battle screens).
 *   Green variant  — aqua fill, yellow-green icon (menus, egg viewer, Pokémon viewer).
 *
 * Detection strategy:
 *   1. Build a filled circular mask for each candidate position once per frame size.
 *   2. Convert RGBA → HSV.
 *   3. For each candidate, measure the fraction of circle pixels matching the white
 *      range (low S, high V) and the aqua range.
 *   4. Return the centre of the first candidate whose fraction exceeds [DETECT_THRESHOLD]
 *      so the tap lands on the actual button.
 *
 * Checked before [PassengerDetector] in the scan loop: the buddy screen shows both a
 * large "play" button (which PassengerDetector matches) and this exit button, so exit
 * detection must take priority.
 *
 * Pre-allocated Mats are reused across calls.  Call [release] when done.
 */
class ExitButtonDetector {

    /**
     * HSV range for the white button fill (white variant).
     * The button is an off-white/light-grey; saturation up to ~30 is needed.
     */
    private val whiteHsvLower = Scalar(  0.0,   0.0, 235.0)
    private val whiteHsvUpper = Scalar(180.0,  30.0, 255.0)

    /**
     * HSV range for the aqua colour shared by both variants:
     * the outline + X of the white button, and the background fill of the green button.
     * Measured: white-variant outline H=186/360, S=75/100, V=58/100;
     *           green-variant fill    H=188/360, S=80/100, V=59/100 — effectively identical.
     */
    private val aquaHsvLower  = Scalar( 90.0, 200.0, 145.0)
    private val aquaHsvUpper  = Scalar( 98.0, 212.0, 157.0)

    /** Per-candidate state: mask, pixel count, last-computed ratios. */
    private inner class Candidate(val centerNX: Float, val centerNY: Float) {
        var mask       = Mat()
        var pixels     = 0f
        var whiteRatio = 0f
        var aquaRatio  = 0f
    }

    private val candidates = listOf(
        Candidate(BUTTON_CENTER_NX, BUTTON_CENTER_NY_STANDARD),
        Candidate(BUTTON_CENTER_NX, BUTTON_CENTER_NY_MENU),
    )

    /** Union of all candidate masks — used by [visualize] to colour all circle interiors. */
    private var allMask = Mat()

    // Pre-allocated scratch Mats.
    private val hsv       = Mat()
    private val colorMask = Mat()
    private val masked    = Mat()

    /** Index into [candidates] of the last match, or -1 if none. */
    private var lastMatchIndex = -1

    /**
     * Detect the exit button in [screenshot] (720p RGBA Mat from ScreenshotService).
     * Returns the button centre in 720p-normalised space, or null if not found.
     * The caller retains ownership of [screenshot].
     */
    fun detect(screenshot: Mat): PointF? {
        val w = screenshot.cols()
        val h = screenshot.rows()

        if (allMask.rows() != h || allMask.cols() != w) {
            allMask.release()
            allMask = Mat.zeros(h, w, CvType.CV_8UC1)
            for (c in candidates) {
                c.mask.release()
                c.mask = Mat.zeros(h, w, CvType.CV_8UC1)
                val cx = c.centerNX * w
                val cy = c.centerNY * h
                val r  = (BUTTON_RADIUS_NX * w).toInt()
                Imgproc.circle(c.mask, Point(cx.toDouble(), cy.toDouble()), r, Scalar(255.0), -1)
                c.pixels = Core.countNonZero(c.mask).toFloat()
                Core.bitwise_or(allMask, c.mask, allMask)
                Log.d(TAG, "Candidate mask built: center=(${cx.toInt()},${cy.toInt()}) r=$r pixels=${c.pixels.toInt()}")
            }
        }

        Imgproc.cvtColor(screenshot, hsv, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)

        for ((idx, c) in candidates.withIndex()) {
            Core.inRange(hsv, whiteHsvLower, whiteHsvUpper, colorMask)
            Core.bitwise_and(colorMask, c.mask, masked)
            c.whiteRatio = Core.countNonZero(masked) / c.pixels

            Core.inRange(hsv, aquaHsvLower, aquaHsvUpper, colorMask)
            Core.bitwise_and(colorMask, c.mask, masked)
            c.aquaRatio = Core.countNonZero(masked) / c.pixels

            Log.d(TAG, "Candidate $idx (NY=%.3f): white=${c.whiteRatio}  aqua=${c.aquaRatio}".format(c.centerNY))
        }

        // Pick the candidate with the strongest signal (max of white/aqua ratios) that
        // also clears the threshold.  Using the first-over-threshold candidate would
        // mis-select when a lower candidate partially overlaps a button centred on a
        // higher one (e.g. menu position at NY=0.929 bleeds into the NY=0.941 circle).
        lastMatchIndex = candidates.indices
            .filter { idx ->
                val c = candidates[idx]
                c.whiteRatio >= DETECT_THRESHOLD || c.aquaRatio >= DETECT_THRESHOLD
            }
            .maxByOrNull { idx ->
                val c = candidates[idx]
                maxOf(c.whiteRatio, c.aquaRatio)
            } ?: -1

        if (lastMatchIndex >= 0) {
            val c = candidates[lastMatchIndex]
            Log.i(TAG, "Exit button detected at candidate $lastMatchIndex " +
                "(white=%.1f%%  aqua=%.1f%%)".format(c.whiteRatio * 100f, c.aquaRatio * 100f))
        }

        return if (lastMatchIndex >= 0) {
            val c = candidates[lastMatchIndex]
            PointF(c.centerNX * w, c.centerNY * h)
        } else null
    }

    /**
     * Produce a debug visualization of the last [detect] call.
     * Must be called before [screenshot] is released.
     *
     * All pixels within any candidate circle are shown in their original colour.
     * Each circle is outlined in yellow if it was the matched candidate, red otherwise.
     * Per-candidate ratio text is shown at the bottom.
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

        if (!allMask.empty()) {
            // Show all pixels inside any candidate circle in their original colour so
            // the actual button colours are visible regardless of whether they matched.
            screenshot.copyTo(viz, allMask)

            for ((idx, c) in candidates.withIndex()) {
                val cx = c.centerNX * w
                val cy = c.centerNY * h
                val r  = (BUTTON_RADIUS_NX * w).toInt()
                val color = if (idx == lastMatchIndex)
                    Scalar(255.0, 255.0, 0.0, 255.0)   // yellow — matched
                else
                    Scalar(255.0, 0.0, 0.0, 255.0)     // red — not matched
                Imgproc.circle(viz, Point(cx.toDouble(), cy.toDouble()), r, color, 3)
            }
        }

        // Build one text line per candidate; prefix matched line with "*".
        val lines = candidates.mapIndexed { idx, c ->
            val tag = if (idx == lastMatchIndex) "*" else " "
            "$tag[${idx}] white=%.1f%%  aqua=%.1f%%".format(c.whiteRatio * 100f, c.aquaRatio * 100f)
        }

        val fontFace  = Imgproc.FONT_HERSHEY_SIMPLEX
        val fontScale = 1.0
        val fontThick = 2
        val pad       = 12
        val baseLine  = IntArray(1)
        val lineHeight = Imgproc.getTextSize("X", fontFace, fontScale, fontThick, baseLine)
            .height.toInt() + baseLine[0] + pad

        val boxHeight = lineHeight * lines.size + pad
        Imgproc.rectangle(viz,
            Point(0.0, (h - boxHeight).toDouble()),
            Point(w.toDouble(), h.toDouble()),
            Scalar(0.0, 0.0, 0.0, 200.0), -1)

        for ((i, line) in lines.withIndex()) {
            val textY = h - boxHeight + pad + lineHeight * i + lineHeight - baseLine[0]
            Imgproc.putText(viz, line,
                Point(pad.toDouble(), textY.toDouble()),
                fontFace, fontScale, Scalar(255.0, 255.0, 255.0, 255.0), fontThick)
        }

        val bitmap = Bitmap.createBitmap(viz.cols(), viz.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(viz, bitmap)
        viz.release()
        return bitmap
    }

    /** Release all pre-allocated Mats.  Call when the detector is no longer needed. */
    fun release() {
        hsv.release()
        colorMask.release()
        masked.release()
        allMask.release()
        for (c in candidates) {
            c.mask.release()
        }
    }

    companion object {
        private const val TAG = "Backpacker.ExitButtonDetector"

        // Button geometry in normalised coords (measured from a 1080×2400 screenshot).
        // Diameter 104 px → radius 52 px.
        private const val BUTTON_CENTER_NX           = 0.500f  //  540 / 1080
        private const val BUTTON_CENTER_NY_STANDARD  = 0.941f  // 2259 / 2400 — gym, spinner, most views
        private const val BUTTON_CENTER_NY_MENU      = 0.929f  // 2230 / 2400 — menu, bag, egg, Pokémon viewer
        private const val BUTTON_RADIUS_NX           = 0.048f  //   52 / 1080

        // Minimum fraction of circle pixels matching a colour to report a button present.
        // White variant: ~60-70% white fill.  Green variant: ~60-70% aqua fill.
        private const val DETECT_THRESHOLD = 0.40f
    }
}
