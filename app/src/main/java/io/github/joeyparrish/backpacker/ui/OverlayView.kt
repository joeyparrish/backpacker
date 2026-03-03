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
 * The button cycles through three states on each tap:
 *   IDLE  → HOUSE → CAR → IDLE → …
 *
 * Drag to reposition; lift without dragging to advance the state.
 */
class OverlayView(
    context: Context,
    private val onStateChange: (State) -> Unit
) {
    enum class State { IDLE, HOUSE, CAR }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val themedContext = ContextThemeWrapper(context, R.style.Theme_Backpacker)
    private val binding = OverlayButtonBinding.inflate(LayoutInflater.from(themedContext))

    private var state = State.IDLE
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

    private val DRAG_THRESHOLD_DP = 12f

    init {
        updateAppearance()
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

    fun setState(newState: State) {
        state = newState
        updateAppearance()
    }

    private fun updateAppearance() {
        when (state) {
            State.IDLE -> {
                binding.fabToggle.alpha = 0.50f
                binding.fabToggle.backgroundTintList = ColorStateList.valueOf(Color.BLACK)
                binding.fabToggle.setImageResource(R.drawable.ic_fab_pokestop)
                binding.fabToggle.imageTintList = null   // show original cyan
            }
            State.HOUSE -> {
                binding.fabToggle.alpha = 1.0f
                binding.fabToggle.backgroundTintList = ColorStateList.valueOf(Color.BLACK)
                binding.fabToggle.setImageResource(R.drawable.ic_fab_house)
                binding.fabToggle.imageTintList = ColorStateList.valueOf(Color.RED)
            }
            State.CAR -> {
                binding.fabToggle.alpha = 1.0f
                binding.fabToggle.backgroundTintList = ColorStateList.valueOf(Color.BLACK)
                binding.fabToggle.setImageResource(R.drawable.ic_fab_car)
                binding.fabToggle.imageTintList = ColorStateList.valueOf(Color.RED)
            }
        }
    }

    /**
     * Single touch listener handles both drag and tap.
     * Tap cycles: IDLE → HOUSE → CAR → IDLE
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
                        state = when (state) {
                            State.IDLE  -> State.HOUSE
                            State.HOUSE -> State.CAR
                            State.CAR   -> State.IDLE
                        }
                        updateAppearance()
                        onStateChange(state)
                    }
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val TAG = "Backpacker.OverlayView"
    }
}
