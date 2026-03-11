// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

package io.github.joeyparrish.backpacker.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Persistent two-line status HUD drawn over other apps via TYPE_ACCESSIBILITY_OVERLAY.
 *
 * Line 1 ([tvStatus]) shows the latest action ("Pokéstops: 2", "Spin succeeded", …).
 * Line 2 ([tvStats])  shows session-level stats ("12 spins (3.4/hr)").
 *
 * The window is FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE so all input passes through
 * to the game underneath.  Show/hide is driven by FAB state (hidden in IDLE).
 *
 * [update] may be called from any thread; it posts to the main thread internally.
 */
class HudView(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private fun Int.dp() = (this * density + 0.5f).toInt()

    private val tvStatus = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
        text = "Idle"
    }

    private val tvStats = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
        text = "No stats"
    }

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val hPad = 10.dp()
        val vPad = 6.dp()
        setPadding(hPad, vPad, hPad, vPad)
        background = GradientDrawable().apply {
            setColor(Color.argb(180, 0, 0, 0))
            cornerRadius = 8.dp().toFloat()
        }
        addView(tvStatus)
        addView(tvStats)
    }

    private var isAttached = false

    // FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE: input falls through to the game.
    // No FLAG_LAYOUT_IN_SCREEN: system respects nav-bar insets so y=0 sits
    // right above the nav bar.
    private val layoutParams = LayoutParams(
        LayoutParams.WRAP_CONTENT,
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        x = 16.dp()
        y = 16.dp()
    }

    /** Show the HUD.  No-op if already attached. */
    fun show() {
        if (!isAttached) {
            try {
                windowManager.addView(container, layoutParams)
                isAttached = true
            } catch (e: Exception) {
                Log.e(TAG, "addView: $e")
            }
        }
    }

    /** Hide the HUD.  No-op if not attached. */
    fun hide() {
        if (isAttached) {
            try {
                windowManager.removeView(container)
            } catch (e: Exception) {
                Log.w(TAG, "removeView: $e")
            }
            isAttached = false
        }
    }

    /**
     * Update both HUD lines.  Must be called on the main thread
     * (callers in AutomationEngine use withContext(Dispatchers.Main)).
     */
    fun update(status: String, stats: String) {
        tvStatus.text = status
        tvStats.text = stats
    }

    /** Clear only the status (line 1), leaving stats (line 2) intact. */
    fun clearStatus() {
        tvStatus.text = ""
    }

    companion object {
        private const val TAG = "Backpacker.HudView"
    }
}
