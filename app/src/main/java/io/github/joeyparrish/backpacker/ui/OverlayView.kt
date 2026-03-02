package io.github.joeyparrish.backpacker.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import io.github.joeyparrish.backpacker.R
import io.github.joeyparrish.backpacker.databinding.OverlayButtonBinding
import kotlin.math.abs

/**
 * Floating overlay button drawn by [TapperService] via WindowManager.
 *
 * Uses [LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] — no SYSTEM_ALERT_WINDOW permission needed.
 *
 * IMPORTANT: Views inflated inside a Service do not automatically receive the app's Material
 * theme. We must wrap the context in a [ContextThemeWrapper] before inflating, otherwise
 * FloatingActionButton throws "You need to use a Theme.MaterialComponents theme" at runtime.
 *
 * The button handles both tap (toggle) and drag (reposition) via a single touch listener
 * on the FAB. If the finger moves more than [DRAG_THRESHOLD_DP] before lifting, it's a drag;
 * otherwise it fires the toggle callback.
 */
class OverlayView(
    context: Context,
    private val onToggle: (currentlyRunning: Boolean) -> Unit
) {
    enum class State { IDLE, RUNNING, ERROR }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Wrap context with the Material theme before inflating.
    // Without this, inflating a FloatingActionButton from a Service context crashes with:
    //   "You need to use a Theme.MaterialComponents (or descendant) theme with this widget."
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_Backpacker)
    private val binding = OverlayButtonBinding.inflate(LayoutInflater.from(themedContext))

    private var isRunning = false
    private var isAttached = false

    private val layoutParams = LayoutParams(
        LayoutParams.WRAP_CONTENT,
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        x = 32
        y = 96
    }

    // Drag threshold in raw pixels (approx. 8dp; actual density not critical here)
    private val DRAG_THRESHOLD_DP = 12f

    init {
        updateAppearance()   // apply idle appearance before the view is first shown
        setupTouchHandler()
    }

    fun show() {
        if (isAttached) return
        windowManager.addView(binding.root, layoutParams)
        isAttached = true
    }

    fun hide() {
        if (!isAttached) return
        windowManager.removeView(binding.root)
        isAttached = false
    }

    fun setState(state: State) {
        isRunning = state == State.RUNNING
        updateAppearance()
    }

    private fun updateAppearance() {
        if (isRunning) {
            // RUNNING: black background, red icon, fully opaque — clearly "active"
            binding.fabToggle.alpha = 1.0f
            binding.fabToggle.backgroundTintList = ColorStateList.valueOf(Color.BLACK)
            binding.fabToggle.imageTintList = ColorStateList.valueOf(Color.RED)
        } else {
            // IDLE: black background, cyan icon, slightly dimmed — visually "inactive"
            binding.fabToggle.alpha = 0.50f
            binding.fabToggle.backgroundTintList = ColorStateList.valueOf(Color.BLACK)
            binding.fabToggle.imageTintList = null  // original icon colors (cyan from drawable)
        }
    }

    /**
     * Single touch listener on the FAB handles both drag and tap.
     *
     * - On ACTION_DOWN: record start position, consume event.
     * - On ACTION_MOVE: if past threshold, drag the overlay window.
     * - On ACTION_UP: if not dragging, treat as a toggle tap.
     *
     * Returning true on ACTION_DOWN ensures we receive all subsequent events in
     * the same gesture, which is required for reliable drag detection.
     */
    private fun setupTouchHandler() {
        var dragStartRawX = 0f
        var dragStartRawY = 0f
        var lpStartX = layoutParams.x
        var lpStartY = layoutParams.y
        var dragging = false

        binding.fabToggle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    lpStartX = layoutParams.x
                    lpStartY = layoutParams.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!dragging && (abs(dx) > DRAG_THRESHOLD_DP || abs(dy) > DRAG_THRESHOLD_DP)) {
                        dragging = true
                    }
                    if (dragging) {
                        // Gravity is BOTTOM|END: subtract dx to move right, subtract dy to move up
                        layoutParams.x = (lpStartX - dx).toInt()
                        layoutParams.y = (lpStartY - dy).toInt()
                        if (isAttached) {
                            try {
                                windowManager.updateViewLayout(binding.root, layoutParams)
                            } catch (e: Exception) {
                                Log.w(TAG, "updateViewLayout: $e")
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        isRunning = !isRunning
                        updateAppearance()
                        onToggle(isRunning)
                    }
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val TAG = "OverlayView"

        /** Pokéstop cyan — matches the app icon and the idle FAB icon color. */
        private val COLOR_CYAN = Color.parseColor("#FF17C8FF")
    }
}
