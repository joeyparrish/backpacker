package io.github.joeyparrish.backpacker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams

/**
 * Fullscreen transparent WindowManager overlay that draws coloured bounding
 * boxes over detected contours, then auto-hides after [DISPLAY_MS] ms.
 *
 * Yellow = passed all filters (detected Pokéstop).
 * Red    = failed height or area filter (rejected).
 */
class DebugOverlayView(context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val markerView = MarkerView(context)
    private var isAttached = false

    private val layoutParams = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private val hideRunnable = Runnable { hide() }

    /** Show coloured bounding boxes (device pixels) and auto-hide after [DISPLAY_MS] ms. */
    fun showMarkers(passedBounds: List<RectF>, rejectedBounds: List<RectF>) {
        handler.removeCallbacks(hideRunnable)
        markerView.passedBounds = passedBounds
        markerView.rejectedBounds = rejectedBounds
        if (!isAttached) {
            try {
                windowManager.addView(markerView, layoutParams)
                isAttached = true
            } catch (e: Exception) {
                Log.e(TAG, "addView: $e")
                return
            }
        } else {
            markerView.invalidate()
        }
        handler.postDelayed(hideRunnable, DISPLAY_MS)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        if (isAttached) {
            try {
                windowManager.removeView(markerView)
            } catch (e: Exception) {
                Log.w(TAG, "removeView: $e")
            }
            isAttached = false
        }
    }

    private class MarkerView(context: Context) : View(context) {

        var passedBounds: List<RectF> = emptyList()
            set(value) { field = value; invalidate() }

        var rejectedBounds: List<RectF> = emptyList()
            set(value) { field = value; invalidate() }

        private val density = resources.displayMetrics.density

        // The TYPE_ACCESSIBILITY_OVERLAY window's content area starts below the status bar
        // even with FLAG_LAYOUT_IN_SCREEN, but the VirtualDisplay captures from physical y=0.
        // Translate the canvas up by status bar height so coordinates align.
        private val statusBarOffset: Float = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            (if (id > 0) resources.getDimensionPixelSize(id) else 0).toFloat()
        }

        private val passedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            strokeWidth = 3f * density
            style = Paint.Style.STROKE
        }

        private val rejectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            strokeWidth = 3f * density
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(0f, -statusBarOffset)
            for (r in passedBounds) {
                canvas.drawRect(r, passedPaint)
            }
            for (r in rejectedBounds) {
                canvas.drawRect(r, rejectedPaint)
            }
            canvas.restore()
        }
    }

    companion object {
        private const val TAG = "Backpacker.DebugOverlayView"
        private const val DISPLAY_MS = 2000L
    }
}
