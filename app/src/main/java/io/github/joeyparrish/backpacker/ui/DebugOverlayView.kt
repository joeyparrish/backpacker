package io.github.joeyparrish.backpacker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams

/**
 * Fullscreen transparent WindowManager overlay that draws red X markers at
 * specified device-pixel coordinates, then auto-hides after [DISPLAY_MS] ms.
 *
 * Used by debug scan mode to visualise detected Pokéstop centroids.
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

    /** Show X markers at [points] (device pixels) and auto-hide after [DISPLAY_MS] ms. */
    fun showMarkers(points: List<PointF>) {
        handler.removeCallbacks(hideRunnable)
        markerView.points = points
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

        var points: List<PointF> = emptyList()
            set(value) {
                field = value
                invalidate()
            }

        private val density = resources.displayMetrics.density
        private val arm = 22f * density

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            strokeWidth = 5f * density
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            for (pt in points) {
                canvas.drawLine(pt.x - arm, pt.y - arm, pt.x + arm, pt.y + arm, paint)
                canvas.drawLine(pt.x + arm, pt.y - arm, pt.x - arm, pt.y + arm, paint)
            }
        }
    }

    companion object {
        private const val TAG = "Backpacker.DebugOverlayView"
        private const val DISPLAY_MS = 2000L
    }
}
